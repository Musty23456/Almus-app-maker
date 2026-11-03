'use strict';
const { HttpError } = require('./validate');
const { taskInfo } = require('./prompts');
const { SseParser } = require('./sse');
const { redactText } = require('./redact');

const RETRYABLE_STATUS = new Set([429, 500, 502, 503, 504]);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Turns an upstream (Google) failure into an error for OUR client. Never forwards credentials or raw bodies. */
function mapUpstream(status, bodyText, retryAfterHeader) {
  let message = '';
  try { message = JSON.parse(bodyText)?.error?.message || ''; } catch { /* not JSON */ }
  message = redactText(String(message)).text.slice(0, 300);
  const retryAfter = Number(retryAfterHeader);
  const retryAfterSeconds = Number.isFinite(retryAfter) && retryAfter > 0 ? Math.min(Math.ceil(retryAfter), 3600) : undefined;
  if (status === 429) return new HttpError(429, 'upstream_rate_limited', 'The AI service is busy or its quota is used up. Try again in a moment.', { retryable: true, retryAfterSeconds: retryAfterSeconds ?? 20 });
  if (status === 401 || status === 403 || (status === 400 && /api key|permission|billing/i.test(message))) {
    return new HttpError(502, 'upstream_auth', 'The AI service is not configured correctly on the server. Tell the administrator.', { retryable: false });
  }
  if (status === 404) return new HttpError(502, 'upstream_model', 'The selected model is not available from the AI service.', { retryable: false });
  if (status === 400) return new HttpError(400, 'upstream_rejected', `The AI service rejected the request${message ? `: ${message}` : '.'}`, { retryable: false });
  return new HttpError(503, 'upstream_unavailable', 'The AI service is temporarily unavailable. Try again.', { retryable: true, retryAfterSeconds });
}

function buildBody(req, config) {
  const info = taskInfo(req.task);
  const generationConfig = { maxOutputTokens: config.maxOutputTokens };
  if (req.temperature !== undefined) generationConfig.temperature = req.temperature;
  else generationConfig.temperature = info.json ? 0.2 : 0.7;
  if (info.json) generationConfig.responseMimeType = 'application/json';
  return {
    systemInstruction: { parts: [{ text: info.system }] },
    contents: req.messages.map((m) => ({ role: m.role, parts: [{ text: m.content }] })),
    generationConfig,
  };
}

function textOf(chunk) {
  const parts = chunk?.candidates?.[0]?.content?.parts;
  if (!Array.isArray(parts)) return '';
  return parts.filter((p) => typeof p?.text === 'string' && !p.thought).map((p) => p.text).join('');
}

function usageOf(chunk) {
  const u = chunk?.usageMetadata;
  if (!u) return undefined;
  return { promptTokens: u.promptTokenCount ?? 0, outputTokens: u.candidatesTokenCount ?? 0, totalTokens: u.totalTokenCount ?? 0 };
}

const BAD_FINISH = new Set(['SAFETY', 'RECITATION', 'PROHIBITED_CONTENT', 'BLOCKLIST', 'SPII', 'IMAGE_SAFETY']);

function blockedError(reason) {
  return new HttpError(422, 'blocked', `The AI service declined to answer this request (${String(reason).toLowerCase()}). Rephrase it and try again.`, { retryable: false });
}

class GeminiClient {
  constructor(config, fetchImpl = globalThis.fetch) { this.config = config; this.fetch = fetchImpl; }

  url(model, streaming) {
    const tail = streaming ? ':streamGenerateContent?alt=sse' : ':generateContent';
    return `${this.config.apiBase}/v1beta/models/${encodeURIComponent(model)}${tail}`;
  }

  /**
   * POST with timeout, abort and retries. Only upstream statuses in RETRYABLE_STATUS and network failures are retried
   * (never a 400/401/403/404), and only before any output was produced.
   */
  async _post(req, streaming, clientSignal) {
    const body = JSON.stringify(buildBody(req, this.config));
    for (let attempt = 0; ; attempt++) {
      if (clientSignal?.aborted) throw new HttpError(499, 'client_closed', 'The request was cancelled.');
      const last = attempt >= this.config.maxRetries;
      const ctrl = new AbortController();
      const onAbort = () => ctrl.abort();
      clientSignal?.addEventListener('abort', onAbort, { once: true });
      const timer = setTimeout(() => ctrl.abort(), this.config.timeoutMs);
      const cleanup = () => { clearTimeout(timer); clientSignal?.removeEventListener('abort', onAbort); };
      let res;
      try {
        res = await this.fetch(this.url(req.model, streaming), {
          method: 'POST',
          headers: { 'content-type': 'application/json', 'x-goog-api-key': this.config.apiKey },
          body,
          signal: ctrl.signal,
        });
      } catch {
        cleanup();
        if (clientSignal?.aborted) throw new HttpError(499, 'client_closed', 'The request was cancelled.');
        const err = ctrl.signal.aborted
          ? new HttpError(504, 'upstream_timeout', 'The AI service took too long to answer. Try again, or use a smaller request.', { retryable: true })
          : new HttpError(503, 'upstream_unavailable', 'Could not reach the AI service. Try again.', { retryable: true });
        if (last) throw err;
        await sleep(this.config.retryBaseMs * 2 ** attempt);
        continue;
      }
      if (res.ok) return { res, cleanup };
      const text = await res.text().catch(() => '');
      cleanup();
      const err = mapUpstream(res.status, text, res.headers.get('retry-after'));
      if (!RETRYABLE_STATUS.has(res.status) || last) throw err;
      const ra = err.extra.retryAfterSeconds;
      await sleep(ra && ra <= 10 ? ra * 1000 : this.config.retryBaseMs * 2 ** attempt);
    }
  }

  async generate(req, clientSignal) {
    const { res, cleanup } = await this._post(req, false, clientSignal);
    let data;
    try { data = await res.json(); } catch { throw new HttpError(502, 'upstream_bad_response', 'The AI service sent an unreadable answer.', { retryable: true }); } finally { cleanup(); }
    if (data?.promptFeedback?.blockReason) throw blockedError(data.promptFeedback.blockReason);
    const finishReason = data?.candidates?.[0]?.finishReason || 'STOP';
    const text = textOf(data);
    if (!text && BAD_FINISH.has(finishReason)) throw blockedError(finishReason);
    if (!text) throw new HttpError(502, 'upstream_empty', 'The AI service returned an empty answer. Try again.', { retryable: true });
    return { text, model: req.model, finishReason, usage: usageOf(data) };
  }

  /** Async generator of { type:'delta', text } ... { type:'done', finishReason, usage }. Throws HttpError before or during. */
  async *stream(req, clientSignal) {
    const { res, cleanup } = await this._post(req, true, clientSignal);
    const parser = new SseParser();
    const decoder = new TextDecoder();
    let finishReason = 'STOP';
    let usage;
    let produced = false;
    const handle = function* (events) {
      for (const ev of events) {
        let chunk;
        try { chunk = JSON.parse(ev.data); } catch { continue; }
        if (chunk?.promptFeedback?.blockReason) throw blockedError(chunk.promptFeedback.blockReason);
        const t = textOf(chunk);
        if (t) { produced = true; yield { type: 'delta', text: t }; }
        const fr = chunk?.candidates?.[0]?.finishReason;
        if (fr) finishReason = fr;
        usage = usageOf(chunk) || usage;
      }
    };
    try {
      const reader = res.body.getReader();
      for (;;) {
        let r;
        try { r = await reader.read(); } catch {
          if (clientSignal?.aborted) throw new HttpError(499, 'client_closed', 'The request was cancelled.');
          throw new HttpError(504, 'upstream_timeout', 'The connection to the AI service was interrupted.', { retryable: true });
        }
        if (r.done) break;
        yield* handle(parser.push(decoder.decode(r.value, { stream: true })));
      }
      yield* handle(parser.push(decoder.decode()));
      yield* handle(parser.end());
    } finally { cleanup(); }
    if (!produced) {
      if (BAD_FINISH.has(finishReason)) throw blockedError(finishReason);
      throw new HttpError(502, 'upstream_empty', 'The AI service returned an empty answer. Try again.', { retryable: true });
    }
    yield { type: 'done', finishReason, usage };
  }
}

module.exports = { GeminiClient, mapUpstream, buildBody };
