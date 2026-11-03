'use strict';
const http = require('node:http');
const { loadConfig } = require('../src/config');
const { createServer } = require('../src/server');

const API_KEY = 'AIzaSyTESTKEY_0123456789abcdefghijklmnopq'; // looks like a real Google key on purpose
const ACCESS = 'alice-access-code-0001';
const BASE_ENV = {
  GEMINI_API_KEY: API_KEY,
  ALMUS_TOKEN_SECRET: 'x'.repeat(40),
  ALMUS_ACCESS_CODES: `alice=${ACCESS},bob=bob-access-code-00002`,
  GEMINI_MODELS: 'fast-model,pro-model',
  GEMINI_DEFAULT_MODEL: 'fast-model',
  GEMINI_RETRY_BASE_MS: '1',
  GEMINI_MAX_RETRIES: '2',
  PORT: '0',
};

const ok = (text, extra = {}) => ({ candidates: [{ content: { role: 'model', parts: [{ text }] }, finishReason: 'STOP', ...extra }], usageMetadata: { promptTokenCount: 5, candidatesTokenCount: 7, totalTokenCount: 12 } });

/** A fake Google endpoint. `handler(req, body, res, calls)` decides what to do; every call is recorded. */
async function startUpstream(handler) {
  const calls = [];
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8');
      let body = null; try { body = JSON.parse(raw); } catch { /* ignore */ }
      const call = { url: req.url, headers: req.headers, body, closedEarly: false };
      calls.push(call);
      res.on('close', () => { if (!res.writableEnded) call.closedEarly = true; });
      handler(req, body, res, calls, call);
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  return { server, calls, base: `http://127.0.0.1:${server.address().port}`, close: () => new Promise((r) => { server.closeAllConnections?.(); server.close(r); }) };
}

async function startApp(upstream, envOverrides = {}, deps = {}) {
  const logs = [];
  const config = loadConfig({ ...BASE_ENV, GEMINI_API_BASE: upstream.base, ...envOverrides });
  const server = createServer(config, { logger: (o) => logs.push(o), ...deps });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  const api = {
    base, logs, config, server,
    async login(code = ACCESS) {
      const r = await fetch(`${base}/v1/auth/login`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ accessCode: code }) });
      return { status: r.status, body: await r.json() };
    },
    async token(code = ACCESS) { const r = await api.login(code); if (r.status !== 200) throw new Error('login failed'); return r.body.token; },
    async post(path, body, token, headers = {}) {
      const r = await fetch(`${base}${path}`, { method: 'POST', headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}), ...headers }, body: typeof body === 'string' ? body : JSON.stringify(body) });
      const text = await r.text();
      let json = null; try { json = JSON.parse(text); } catch { /* not json */ }
      return { status: r.status, headers: r.headers, text, json };
    },
    /** Reads a stream response into events. */
    async stream(path, body, token) {
      const r = await fetch(`${base}${path}`, { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` }, body: JSON.stringify(body) });
      if (r.status !== 200) return { status: r.status, json: await r.json() };
      const { SseParser } = require('../src/sse');
      const p = new SseParser(); const dec = new TextDecoder(); const events = [];
      const reader = r.body.getReader();
      for (;;) { const { done, value } = await reader.read(); if (done) break; for (const e of p.push(dec.decode(value, { stream: true }))) events.push({ event: e.event, data: JSON.parse(e.data) }); }
      return { status: 200, events };
    },
    close: () => new Promise((r) => { server.closeAllConnections?.(); server.close(r); }),
  };
  return api;
}

const msg = (content = 'hello', role = 'user') => ({ role, content });
const sseChunk = (text, extra) => `data: ${JSON.stringify(ok(text, extra))}\n\n`;

module.exports = { API_KEY, ACCESS, BASE_ENV, ok, startUpstream, startApp, msg, sseChunk };
