'use strict';
const http = require('node:http');
const crypto = require('node:crypto');
const { matchAccessCode, issueToken, verify, bearer } = require('./auth');
const { SlidingWindowLimiter } = require('./rateLimit');
const { validateGenerate, HttpError } = require('./validate');
const { GeminiClient } = require('./gemini');
const { formatSse } = require('./sse');
const { redactText } = require('./redact');

const SECURITY_HEADERS = {
  'x-content-type-options': 'nosniff',
  'cache-control': 'no-store',
  'referrer-policy': 'no-referrer',
  'content-security-policy': "default-src 'none'",
};

function createServer(config, deps = {}) {
  const log = deps.logger || ((o) => process.stdout.write(`${JSON.stringify(o)}\n`));
  const now = deps.now || (() => Date.now());
  const gemini = deps.gemini || new GeminiClient(config, deps.fetch);
  const perMinute = new SlidingWindowLimiter(config.rateLimitPerMinute, 60_000, now);
  const perDay = new SlidingWindowLimiter(config.rateLimitPerDay, 86_400_000, now);
  const loginFailures = new SlidingWindowLimiter(config.loginMaxFailures, 15 * 60_000, now);
  const sweeper = setInterval(() => { perMinute.sweep(); perDay.sweep(); loginFailures.sweep(); }, 60_000);
  sweeper.unref();

  function clientIp(req) {
    if (config.trustProxy) {
      const xff = String(req.headers['x-forwarded-for'] || '').split(',').map((s) => s.trim()).filter(Boolean);
      if (xff.length) return xff[xff.length - 1];
    }
    return req.socket.remoteAddress || 'unknown';
  }

  function sendJson(res, status, obj, extraHeaders = {}) {
    if (res.headersSent) return;
    const body = JSON.stringify(obj);
    res.writeHead(status, { ...SECURITY_HEADERS, 'content-type': 'application/json; charset=utf-8', 'content-length': Buffer.byteLength(body), ...extraHeaders });
    res.end(body);
  }

  function sendError(res, err) {
    const e = err instanceof HttpError ? err : new HttpError(500, 'internal', 'Something went wrong on the server.');
    const headers = {};
    if (e.status === 401) headers['www-authenticate'] = 'Bearer';
    if (e.extra.retryAfterSeconds) headers['retry-after'] = String(e.extra.retryAfterSeconds);
    sendJson(res, e.status === 499 ? 400 : e.status, { error: { code: e.code, message: e.message, retryable: Boolean(e.extra.retryable), ...(e.extra.retryAfterSeconds ? { retryAfterSeconds: e.extra.retryAfterSeconds } : {}) } }, headers);
  }

  function readJson(req) {
    return new Promise((resolve, reject) => {
      const type = String(req.headers['content-type'] || '');
      if (!/^application\/json\b/i.test(type)) return reject(new HttpError(415, 'unsupported_media_type', 'Send the body as application/json.'));
      const declared = Number(req.headers['content-length']);
      if (Number.isFinite(declared) && declared > config.maxBodyBytes) return reject(new HttpError(413, 'payload_too_large', 'The request body is too large.'));
      const chunks = [];
      let size = 0;
      let failed = false;
      req.on('data', (c) => {
        if (failed) return;
        size += c.length;
        if (size > config.maxBodyBytes) { failed = true; reject(new HttpError(413, 'payload_too_large', 'The request body is too large.')); req.resume(); return; }
        chunks.push(c);
      });
      req.on('end', () => {
        if (failed) return;
        try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); } catch { reject(new HttpError(400, 'bad_request', 'The body is not valid JSON.')); }
      });
      req.on('error', () => { if (!failed) reject(new HttpError(400, 'bad_request', 'The request could not be read.')); });
    });
  }

  function authenticate(req) {
    const token = bearer(req);
    const payload = token && verify(token, config.tokenSecret, now());
    if (!payload) throw new HttpError(401, 'unauthorized', 'Sign in again: your session is missing, invalid or expired.');
    return payload.sub;
  }

  function limit(user) {
    const m = perMinute.take(user);
    if (!m.allowed) throw new HttpError(429, 'rate_limited', 'Too many requests. Wait a moment and try again.', { retryable: true, retryAfterSeconds: m.retryAfterSeconds });
    const d = perDay.take(user);
    if (!d.allowed) throw new HttpError(429, 'daily_limit', 'The daily request limit is reached. Try again later.', { retryable: false, retryAfterSeconds: d.retryAfterSeconds });
  }

  /** Redacts secrets in the outgoing messages; returns the number of redactions. */
  function scrub(request) {
    let redactions = 0;
    request.messages = request.messages.map((m) => { const r = redactText(m.content); redactions += r.count; return { ...m, content: r.text }; });
    return redactions;
  }

  async function login(req, res, ctx) {
    const ip = clientIp(req);
    const wait = loginFailures.blocked(ip);
    if (wait) throw new HttpError(429, 'too_many_attempts', 'Too many failed sign-in attempts. Try again later.', { retryable: true, retryAfterSeconds: wait });
    const body = await readJson(req);
    const user = matchAccessCode(body && body.accessCode, config.users);
    if (!user) { loginFailures.record(ip); throw new HttpError(401, 'invalid_access_code', 'The access code is not correct.'); }
    loginFailures.reset(ip);
    ctx.user = user;
    sendJson(res, 200, { ...issueToken(user, config, now()), user });
  }

  async function generate(req, res, ctx, streaming) {
    const user = authenticate(req); ctx.user = user;
    limit(user);
    const body = await readJson(req);
    const request = validateGenerate(body, config);
    ctx.task = request.task; ctx.model = request.model;
    ctx.promptChars = request.messages.reduce((n, m) => n + m.content.length, 0);
    ctx.redactions = scrub(request);
    const abort = new AbortController();
    res.on('close', () => { if (!res.writableEnded) abort.abort(); });

    if (!streaming) {
      const out = await gemini.generate(request, abort.signal);
      sendJson(res, 200, { text: out.text, model: out.model, finishReason: out.finishReason, usage: out.usage, redactions: ctx.redactions });
      return;
    }
    const it = gemini.stream(request, abort.signal)[Symbol.asyncIterator]();
    const first = await it.next(); // errors before the first byte become normal HTTP errors
    res.writeHead(200, { ...SECURITY_HEADERS, 'content-type': 'text/event-stream; charset=utf-8', connection: 'keep-alive', 'x-accel-buffering': 'no' });
    res.write(formatSse('meta', { model: request.model, redactions: ctx.redactions }));
    const heartbeat = setInterval(() => res.write(': ping\n\n'), 15_000);
    try {
      let step = first;
      while (!step.done) {
        const ev = step.value;
        if (ev.type === 'delta') res.write(formatSse('delta', { text: ev.text }));
        else res.write(formatSse('done', { finishReason: ev.finishReason, usage: ev.usage }));
        step = await it.next();
      }
    } catch (err) {
      const e = err instanceof HttpError ? err : new HttpError(500, 'internal', 'Something went wrong on the server.');
      ctx.error = e.code;
      if (!res.writableEnded) res.write(formatSse('error', { code: e.code, message: e.message, retryable: Boolean(e.extra.retryable), ...(e.extra.retryAfterSeconds ? { retryAfterSeconds: e.extra.retryAfterSeconds } : {}) }));
    } finally {
      clearInterval(heartbeat);
      if (!res.writableEnded) res.end();
    }
  }

  const server = http.createServer(async (req, res) => {
    const started = now();
    const ctx = { id: crypto.randomBytes(6).toString('hex') };
    const path = (req.url || '/').split('?')[0];
    try {
      res.setHeader('x-request-id', ctx.id);
      if (path === '/health') {
        if (req.method !== 'GET') throw new HttpError(405, 'method_not_allowed', 'Use GET.');
        sendJson(res, 200, { status: 'ok' });
      } else if (path === '/v1/auth/login') {
        if (req.method !== 'POST') throw new HttpError(405, 'method_not_allowed', 'Use POST.');
        await login(req, res, ctx);
      } else if (path === '/v1/models') {
        if (req.method !== 'GET') throw new HttpError(405, 'method_not_allowed', 'Use GET.');
        ctx.user = authenticate(req);
        sendJson(res, 200, { default: config.defaultModel, models: config.models.map((id) => ({ id })) });
      } else if (path === '/v1/generate' || path === '/v1/generate/stream') {
        if (req.method !== 'POST') throw new HttpError(405, 'method_not_allowed', 'Use POST.');
        await generate(req, res, ctx, path.endsWith('/stream'));
      } else {
        throw new HttpError(404, 'not_found', 'Unknown address.');
      }
    } catch (err) {
      ctx.error = err instanceof HttpError ? err.code : 'internal';
      if (!(err instanceof HttpError)) log({ ts: new Date().toISOString(), level: 'error', id: ctx.id, msg: 'unhandled', name: err && err.name });
      sendError(res, err);
    } finally {
      // Never log prompts, answers, tokens, access codes or the API key.
      log({ ts: new Date().toISOString(), level: 'info', id: ctx.id, method: req.method, path, status: res.statusCode, ms: now() - started, user: ctx.user, task: ctx.task, model: ctx.model, promptChars: ctx.promptChars, redactions: ctx.redactions, error: ctx.error });
    }
  });
  server.requestTimeout = 5 * 60_000;
  server.headersTimeout = 20_000;
  server.on('close', () => clearInterval(sweeper));
  server.limiters = { perMinute, perDay, loginFailures };
  return server;
}

module.exports = { createServer };
