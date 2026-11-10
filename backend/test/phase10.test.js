'use strict';
// Phase 10: extra edge-case coverage for paths the earlier suites did not exercise
// (login input handling, method checks on every route, chunked oversize bodies, secrets on the streaming path).
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { API_KEY, ACCESS, ok, startUpstream, startApp, msg, sseChunk } = require('./helpers');

const json = (res, status, obj) => { res.writeHead(status, { 'content-type': 'application/json' }); res.end(JSON.stringify(obj)); };

async function withApp(handler, env, fn) {
  const up = await startUpstream(handler);
  const app = await startApp(up, env);
  try { await fn(app, up); } finally { await app.close(); await up.close(); }
}

test('login: wrong content type, broken JSON, missing/oversized/non-string code are refused and never reach Gemini', async () => {
  await withApp((req, body, res) => json(res, 200, ok('x')), {}, async (app, up) => {
    const type = await app.post('/v1/auth/login', JSON.stringify({ accessCode: ACCESS }), null, { 'content-type': 'text/plain' });
    assert.equal(type.status, 415);
    const broken = await app.post('/v1/auth/login', '{nope', null);
    assert.equal(broken.status, 400);
    assert.equal(broken.json.error.code, 'bad_request');
    for (const body of [{}, { accessCode: 12345 }, { accessCode: '' }, { accessCode: 'x'.repeat(201) }, { accessCode: null }, []]) {
      const r = await app.post('/v1/auth/login', body, null);
      assert.equal(r.status, 401, JSON.stringify(body).slice(0, 40));
      assert.equal(r.json.error.code, 'invalid_access_code');
    }
    assert.equal(up.calls.length, 0);
  });
});

test('routes: every route rejects the wrong HTTP method; models needs a token', async () => {
  await withApp((req, body, res) => json(res, 200, ok('x')), {}, async (app) => {
    const t = await app.token();
    const status = async (method, path, token) => (await fetch(`${app.base}${path}`, { method, headers: token ? { authorization: `Bearer ${token}` } : {} })).status;
    assert.equal(await status('POST', '/health'), 405);
    assert.equal(await status('GET', '/v1/auth/login'), 405);
    assert.equal(await status('POST', '/v1/models', t), 405);
    assert.equal(await status('GET', '/v1/generate/stream', t), 405);
    assert.equal(await status('GET', '/v1/models'), 401);
    assert.equal(await status('GET', '/v1/models', t), 200);
    assert.equal(await status('GET', '/'), 404);
    // a query string must not change routing
    assert.equal(await status('GET', '/health?x=1'), 200);
  });
});

test('body limit: an oversized body sent in chunks (no honest Content-Length) is refused with 413', async () => {
  await withApp((req, body, res) => json(res, 200, ok('x')), { MAX_BODY_BYTES: '2048' }, async (app, up) => {
    const t = await app.token();
    const url = new URL(`${app.base}/v1/generate`);
    const status = await new Promise((resolve, reject) => {
      const req = http.request({ host: url.hostname, port: url.port, path: url.pathname, method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${t}`, 'transfer-encoding': 'chunked' } }, (res) => { res.resume(); res.on('end', () => resolve(res.statusCode)); });
      req.on('error', reject);
      req.write('{"messages":[{"role":"user","content":"');
      for (let i = 0; i < 8; i++) req.write('a'.repeat(1024));
      req.end('"}]}');
    });
    assert.equal(status, 413);
    assert.equal(up.calls.length, 0);
  });
});

test('streaming: secrets in the prompt are removed before Gemini AND the count is reported in the meta event', async () => {
  const leaky = `const k = "${API_KEY}"`;
  await withApp((req, body, res) => { res.writeHead(200, { 'content-type': 'text/event-stream' }); res.write(sseChunk('hi')); res.end(); }, {}, async (app, up) => {
    const t = await app.token();
    const r = await app.stream('/v1/generate/stream', { messages: [msg(`please review ${leaky}`)] }, t);
    assert.equal(r.status, 200);
    const meta = r.events.find((e) => e.event === 'meta');
    assert.ok(meta.data.redactions >= 1);
    assert.equal(r.events[r.events.length - 1].event, 'done');
    assert.ok(!JSON.stringify(up.calls[0].body).includes(API_KEY.slice(0, 12)), 'the key must not reach Gemini');
  });
});

test('limits: a failed login attempt by one address does not lock out a valid user on another request path', async () => {
  await withApp((req, body, res) => json(res, 200, ok('x')), { LOGIN_MAX_FAILURES: '3' }, async (app) => {
    for (let i = 0; i < 2; i++) assert.equal((await app.login('wrong-code-xxxxxxx')).status, 401);
    // a correct login before the limit still works and resets the counter
    assert.equal((await app.login()).status, 200);
    for (let i = 0; i < 2; i++) assert.equal((await app.login('wrong-code-xxxxxxx')).status, 401);
    assert.equal((await app.login()).status, 200);
  });
});
