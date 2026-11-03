'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { API_KEY, ACCESS, ok, startUpstream, startApp, msg, sseChunk } = require('./helpers');

const json = (res, status, obj, headers = {}) => { res.writeHead(status, { 'content-type': 'application/json', ...headers }); res.end(JSON.stringify(obj)); };
const sse = (res) => res.writeHead(200, { 'content-type': 'text/event-stream' });

async function withApp(handler, env, fn, deps) {
  const up = await startUpstream(handler);
  const app = await startApp(up, env, deps);
  try { await fn(app, up); } finally { await app.close(); await up.close(); }
}

// ---------------------------------------------------------------- Gemini connection / generation
test('connection: login, list models, single generation reaches Gemini with the key in a header only', async () => {
  await withApp((req, body, res) => json(res, 200, ok('Hello from Gemini')), {}, async (app, up) => {
    const login = await app.login();
    assert.equal(login.status, 200);
    assert.equal(login.body.expiresInSeconds, 3600);
    const models = await fetch(`${app.base}/v1/models`, { headers: { authorization: `Bearer ${login.body.token}` } }).then((r) => r.json());
    assert.deepEqual(models, { default: 'fast-model', models: [{ id: 'fast-model' }, { id: 'pro-model' }] });

    const r = await app.post('/v1/generate', { task: 'chat', messages: [msg('Say hi')], model: 'pro-model' }, login.body.token);
    assert.equal(r.status, 200);
    assert.equal(r.json.text, 'Hello from Gemini');
    assert.equal(r.json.model, 'pro-model');
    assert.deepEqual(r.json.usage, { promptTokens: 5, outputTokens: 7, totalTokens: 12 });

    const call = up.calls[0];
    assert.equal(call.headers['x-goog-api-key'], API_KEY);
    assert.ok(!call.url.includes(API_KEY) && !call.url.includes('key='), 'key must not be in the URL');
    assert.equal(call.url, '/v1beta/models/pro-model:generateContent');
    assert.equal(call.body.contents[0].parts[0].text, 'Say hi');
    assert.match(call.body.systemInstruction.parts[0].text, /ALMUS APP CREATOR/);
    assert.equal(call.body.generationConfig.responseMimeType, undefined, 'plain chat is not forced to JSON');
  });
});

test('structured tasks ask Gemini for JSON; default model is used when none is given', async () => {
  await withApp((req, body, res) => json(res, 200, ok('{"project_name":"NotesApp","files":[]}')), {}, async (app, up) => {
    const token = await app.token();
    const r = await app.post('/v1/generate', { task: 'generate_project', messages: [msg('offline notes app')] }, token);
    assert.equal(r.status, 200);
    assert.equal(r.json.model, 'fast-model');
    assert.equal(up.calls[0].body.generationConfig.responseMimeType, 'application/json');
    assert.match(up.calls[0].body.systemInstruction.parts[0].text, /"files"/);
    assert.match(up.calls[0].body.systemInstruction.parts[0].text, /8\.7\.3/);
  });
});

// ---------------------------------------------------------------- streaming
test('streaming: deltas arrive while Gemini is still producing (not buffered)', async () => {
  let release;
  const gate = new Promise((r) => { release = r; });
  await withApp((req, body, res) => {
    assert.match(req.url, /streamGenerateContent\?alt=sse$/);
    sse(res);
    res.write(sseChunk('Hel'));
    gate.then(() => { res.write(sseChunk('lo ')); res.write(sseChunk('world', { finishReason: 'STOP' })); res.end(); });
  }, {}, async (app) => {
    const token = await app.token();
    const r = await fetch(`${app.base}/v1/generate/stream`, { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` }, body: JSON.stringify({ task: 'chat', messages: [msg('stream please')] }) });
    assert.equal(r.status, 200);
    assert.match(r.headers.get('content-type'), /text\/event-stream/);
    const reader = r.body.getReader(); const dec = new TextDecoder(); let seen = '';
    // First delta must be readable BEFORE the upstream is released.
    while (!seen.includes('"Hel"')) { const { done, value } = await reader.read(); if (done) break; seen += dec.decode(value, { stream: true }); }
    assert.ok(seen.includes('event: delta') && seen.includes('"Hel"'), 'first delta arrived early');
    assert.ok(!seen.includes('world'));
    release();
    for (;;) { const { done, value } = await reader.read(); if (done) break; seen += dec.decode(value, { stream: true }); }
    assert.ok(seen.includes('"lo "') && seen.includes('"world"'));
    assert.ok(seen.includes('event: done') && seen.includes('"finishReason":"STOP"'));
  });
});

test('streaming: events are meta, delta*, done; usage forwarded', async () => {
  await withApp((req, body, res) => { sse(res); res.write(sseChunk('A')); res.write(sseChunk('B')); res.end(); }, {}, async (app) => {
    const r = await app.stream('/v1/generate/stream', { task: 'explain', messages: [msg('x')] }, await app.token());
    assert.deepEqual(r.events.map((e) => e.event), ['meta', 'delta', 'delta', 'done']);
    assert.equal(r.events.filter((e) => e.event === 'delta').map((e) => e.data.text).join(''), 'AB');
    assert.equal(r.events.at(-1).data.usage.totalTokens, 12);
  });
});

test('streaming: error before the first byte is a normal HTTP error status', async () => {
  await withApp((req, body, res) => json(res, 403, { error: { message: 'API key not valid' } }), {}, async (app) => {
    const r = await app.stream('/v1/generate/stream', { task: 'chat', messages: [msg('x')] }, await app.token());
    assert.equal(r.status, 502);
    assert.equal(r.json.error.code, 'upstream_auth');
  });
});

test('streaming: Gemini connection dying mid-stream gives an error event, truncation is visible', async () => {
  await withApp((req, body, res) => { sse(res); res.write(sseChunk('partial')); setTimeout(() => res.destroy(), 20); }, {}, async (app) => {
    const r = await app.stream('/v1/generate/stream', { task: 'chat', messages: [msg('x')] }, await app.token());
    assert.equal(r.status, 200);
    assert.ok(r.events.some((e) => e.event === 'delta'));
    const err = r.events.find((e) => e.event === 'error');
    assert.ok(err, 'error event present');
    assert.equal(err.data.retryable, true);
    assert.ok(!r.events.some((e) => e.event === 'done'));
  });
});

test('streaming: MAX_TOKENS finish reason is forwarded so the app can warn about cut-off JSON', async () => {
  await withApp((req, body, res) => { sse(res); res.write(sseChunk('{"files":[', { finishReason: 'MAX_TOKENS' })); res.end(); }, {}, async (app) => {
    const r = await app.stream('/v1/generate/stream', { task: 'generate_project', messages: [msg('big')] }, await app.token());
    assert.equal(r.events.at(-1).data.finishReason, 'MAX_TOKENS');
  });
});

test('streaming: the app cancelling aborts the Gemini request', async () => {
  await withApp((req, body, res) => { sse(res); res.write(sseChunk('x')); /* never ends */ }, {}, async (app, up) => {
    const token = await app.token();
    const ctrl = new AbortController();
    const r = await fetch(`${app.base}/v1/generate/stream`, { method: 'POST', signal: ctrl.signal, headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` }, body: JSON.stringify({ task: 'chat', messages: [msg('x')] }) });
    await r.body.getReader().read();
    ctrl.abort();
    for (let i = 0; i < 50 && !up.calls[0].closedEarly; i++) await new Promise((r2) => setTimeout(r2, 20));
    assert.equal(up.calls[0].closedEarly, true, 'upstream connection closed');
  });
});

// ---------------------------------------------------------------- API failure handling
test('failures: 429 from Gemini is retried, then reported as retryable with Retry-After', async () => {
  await withApp((req, body, res) => json(res, 429, { error: { message: 'quota' } }, { 'retry-after': '7' }), { GEMINI_MAX_RETRIES: '1' }, async (app, up) => {
    const r = await app.post('/v1/generate', { messages: [msg()] }, await app.token());
    assert.equal(r.status, 429);
    assert.equal(r.json.error.code, 'upstream_rate_limited');
    assert.equal(r.json.error.retryable, true);
    assert.equal(r.headers.get('retry-after'), '7');
    assert.equal(up.calls.length, 2);
  });
});

test('failures: a transient 503 is retried and then succeeds', async () => {
  let n = 0;
  await withApp((req, body, res) => (++n < 3 ? json(res, 503, { error: { message: 'overloaded' } }) : json(res, 200, ok('recovered'))), {}, async (app, up) => {
    const r = await app.post('/v1/generate', { messages: [msg()] }, await app.token());
    assert.equal(r.status, 200); assert.equal(r.json.text, 'recovered'); assert.equal(up.calls.length, 3);
  });
});

test('failures: a persistent 500 becomes a retryable 503 and does not leak the upstream body', async () => {
  await withApp((req, body, res) => json(res, 500, { error: { message: 'internal detail X-SECRET-DETAIL' } }), { GEMINI_MAX_RETRIES: '1' }, async (app) => {
    const r = await app.post('/v1/generate', { messages: [msg()] }, await app.token());
    assert.equal(r.status, 503); assert.equal(r.json.error.retryable, true);
    assert.ok(!r.text.includes('X-SECRET-DETAIL'));
  });
});

test('failures: bad Gemini key / permission / model are NOT retried and never reveal details', async () => {
  for (const [status, code] of [[403, 'upstream_auth'], [401, 'upstream_auth'], [404, 'upstream_model']]) {
    await withApp((req, body, res) => json(res, status, { error: { message: `denied for ${API_KEY}` } }), {}, async (app, up) => {
      const r = await app.post('/v1/generate', { messages: [msg()] }, await app.token());
      assert.equal(r.json.error.code, code); assert.equal(r.status, 502); assert.equal(up.calls.length, 1, `status ${status} must not be retried`);
      assert.ok(!r.text.includes(API_KEY));
    });
  }
});

test('failures: invalid-argument 400 from Gemini is a client error, not retried', async () => {
  await withApp((req, body, res) => json(res, 400, { error: { message: 'Request contains an invalid argument.' } }), {}, async (app, up) => {
    const r = await app.post('/v1/generate', { messages: [msg()] }, await app.token());
    assert.equal(r.status, 400); assert.equal(r.json.error.code, 'upstream_rejected'); assert.equal(up.calls.length, 1);
  });
});

test('failures: Gemini timeout becomes 504; unreachable Gemini becomes 503', async () => {
  await withApp((req, body, res) => { /* never answers */ }, { GEMINI_TIMEOUT_MS: '1000', GEMINI_MAX_RETRIES: '0' }, async (app) => {
    const r = await app.post('/v1/generate', { messages: [msg()] }, await app.token());
    assert.equal(r.status, 504); assert.equal(r.json.error.code, 'upstream_timeout');
  });
  const up = await startUpstream(() => {}); const base = up.base; await up.close();
  const app = await startApp({ base }, { GEMINI_MAX_RETRIES: '0' });
  try {
    const r = await app.post('/v1/generate', { messages: [msg()] }, await app.token());
    assert.equal(r.status, 503); assert.equal(r.json.error.code, 'upstream_unavailable');
  } finally { await app.close(); }
});

test('failures: safety block and empty answers are reported clearly', async () => {
  await withApp((req, body, res) => json(res, 200, { promptFeedback: { blockReason: 'SAFETY' } }), {}, async (app) => {
    const r = await app.post('/v1/generate', { messages: [msg()] }, await app.token());
    assert.equal(r.status, 422); assert.equal(r.json.error.code, 'blocked');
  });
  await withApp((req, body, res) => json(res, 200, { candidates: [{ finishReason: 'SAFETY' }] }), {}, async (app) => {
    assert.equal((await app.post('/v1/generate', { messages: [msg()] }, await app.token())).json.error.code, 'blocked');
  });
  await withApp((req, body, res) => json(res, 200, { candidates: [{ content: { parts: [] }, finishReason: 'STOP' }] }), { GEMINI_MAX_RETRIES: '0' }, async (app) => {
    assert.equal((await app.post('/v1/generate', { messages: [msg()] }, await app.token())).json.error.code, 'upstream_empty');
  });
});

// ---------------------------------------------------------------- security
test('security: missing, malformed, tampered, expired tokens are rejected; no CORS; hardening headers', async () => {
  let t = 1_000_000;
  await withApp((req, body, res) => json(res, 200, ok('x')), {}, async (app, up) => {
    for (const hdr of [undefined, 'Bearer', 'Bearer abc', 'Basic Zm9vOmJhcg==', 'Bearer a.b']) {
      const r = await fetch(`${app.base}/v1/generate`, { method: 'POST', headers: { 'content-type': 'application/json', ...(hdr ? { authorization: hdr } : {}) }, body: JSON.stringify({ messages: [msg()] }) });
      assert.equal(r.status, 401, String(hdr));
      assert.equal(r.headers.get('www-authenticate'), 'Bearer');
      assert.equal(r.headers.get('access-control-allow-origin'), null);
    }
    const good = await app.token();
    const [body, mac] = good.split('.');
    const forged = Buffer.from(JSON.stringify({ v: 1, sub: 'alice', iat: 1, exp: 99999999999 })).toString('base64url');
    assert.equal((await app.post('/v1/generate', { messages: [msg()] }, `${forged}.${mac}`)).status, 401);
    t += 3601_000; // expire
    assert.equal((await app.post('/v1/generate', { messages: [msg()] }, good)).status, 401);
    assert.equal(up.calls.length, 0, 'nothing reached Gemini without valid auth');
    const h = await fetch(`${app.base}/health`);
    assert.equal(h.headers.get('x-content-type-options'), 'nosniff');
    assert.equal(h.headers.get('cache-control'), 'no-store');
  }, { now: () => t });
});

test('security: wrong access codes are refused and lock out after repeated failures', async () => {
  await withApp((req, body, res) => json(res, 200, ok('x')), { LOGIN_MAX_FAILURES: '3' }, async (app) => {
    for (let i = 0; i < 3; i++) { const r = await app.login('definitely-wrong-code-123'); assert.equal(r.status, 401); assert.equal(r.body.error.code, 'invalid_access_code'); }
    const locked = await app.login(ACCESS); // even the right code is refused while locked
    assert.equal(locked.status, 429); assert.equal(locked.body.error.code, 'too_many_attempts');
  });
});

test('security: secrets inside prompts are removed before they reach Gemini', async () => {
  await withApp((req, body, res) => json(res, 200, ok('fine')), {}, async (app, up) => {
    const leak = 'AIzaSyA1234567890abcdefghijklmnopqrstuvw';
    const r = await app.post('/v1/generate', { task: 'explain', messages: [msg(`val k = "${leak}"\nstorePassword = "hunter2hunter2"`)] }, await app.token());
    assert.equal(r.status, 200); assert.ok(r.json.redactions >= 2);
    const sent = JSON.stringify(up.calls[0].body);
    assert.ok(!sent.includes(leak) && !sent.includes('hunter2'));
  });
});

test('security: logs never contain prompts, answers, tokens, access codes or the API key', async () => {
  await withApp((req, body, res) => json(res, 200, ok('ANSWER-TEXT-123')), {}, async (app) => {
    const token = await app.token();
    await app.post('/v1/generate', { messages: [msg('PROMPT-TEXT-456')] }, token);
    await app.login('wrong-code-wrong-code');
    const all = JSON.stringify(app.logs);
    for (const s of ['PROMPT-TEXT-456', 'ANSWER-TEXT-123', token, ACCESS, 'wrong-code-wrong-code', API_KEY, 'x'.repeat(40)]) assert.ok(!all.includes(s), `log leaked ${s.slice(0, 12)}`);
    const entry = app.logs.find((l) => l.path === '/v1/generate');
    assert.equal(entry.user, 'alice'); assert.equal(entry.status, 200); assert.equal(entry.promptChars, 15);
  });
});

test('security: no response (success or error) ever contains the Gemini key or token secret', async () => {
  await withApp((req, body, res) => json(res, 500, { error: { message: `boom ${API_KEY}` } }), { GEMINI_MAX_RETRIES: '0' }, async (app) => {
    const token = await app.token();
    const bodies = [];
    bodies.push((await app.post('/v1/generate', { messages: [msg()] }, token)).text);
    bodies.push((await app.post('/v1/generate', 'not json', token)).text);
    bodies.push((await app.post('/v1/nope', {}, token)).text);
    bodies.push((await app.login('bad-bad-bad-bad-bad')).body.error.message);
    for (const b of bodies) { assert.ok(!b.includes(API_KEY)); assert.ok(!b.includes('x'.repeat(40))); }
  });
});

test('security: users are rate limited separately; limits return Retry-After', async () => {
  await withApp((req, body, res) => json(res, 200, ok('x')), { RATE_LIMIT_PER_MINUTE: '2' }, async (app, up) => {
    const alice = await app.token(); const bob = await app.token('bob-access-code-00002');
    assert.equal((await app.post('/v1/generate', { messages: [msg()] }, alice)).status, 200);
    assert.equal((await app.post('/v1/generate', { messages: [msg()] }, alice)).status, 200);
    const blocked = await app.post('/v1/generate', { messages: [msg()] }, alice);
    assert.equal(blocked.status, 429); assert.equal(blocked.json.error.code, 'rate_limited'); assert.ok(Number(blocked.headers.get('retry-after')) >= 1);
    assert.equal((await app.post('/v1/generate', { messages: [msg()] }, bob)).status, 200, 'bob unaffected');
    assert.equal(up.calls.length, 3);
  });
});

test('security: daily limit', async () => {
  await withApp((req, body, res) => json(res, 200, ok('x')), { RATE_LIMIT_PER_DAY: '1' }, async (app) => {
    const t = await app.token();
    assert.equal((await app.post('/v1/generate', { messages: [msg()] }, t)).status, 200);
    assert.equal((await app.post('/v1/generate', { messages: [msg()] }, t)).json.error.code, 'daily_limit');
  });
});

// ---------------------------------------------------------------- validation
test('validation: unknown task/model, bad shapes, size limits', async () => {
  await withApp((req, body, res) => json(res, 200, ok('x')), { MAX_PROMPT_CHARS: '1000', MAX_BODY_BYTES: '5000' }, async (app, up) => {
    const t = await app.token();
    const bad = async (body, status, code, headers) => { const r = await app.post('/v1/generate', body, t, headers); assert.equal(r.status, status, JSON.stringify(body).slice(0, 60)); assert.equal(r.json.error.code, code); };
    await bad({ task: 'rm -rf', messages: [msg()] }, 400, 'bad_request');
    await bad({ model: 'gemini-secret-internal', messages: [msg()] }, 400, 'bad_model');
    await bad({ messages: [] }, 400, 'bad_request');
    await bad({ messages: [{ role: 'system', content: 'x' }] }, 400, 'bad_request');
    await bad({ messages: [msg('a', 'model')] }, 400, 'bad_request');
    await bad({ messages: [msg()], temperature: 9 }, 400, 'bad_request');
    await bad({ messages: [msg('x'.repeat(1001))] }, 413, 'payload_too_large');
    await bad({ messages: [msg('x'.repeat(6000))] }, 413, 'payload_too_large');
    await bad([1, 2], 400, 'bad_request');
    await bad('{broken', 400, 'bad_request');
    const wrongType = await app.post('/v1/generate', '{"messages":[]}', t, { 'content-type': 'text/plain' });
    assert.equal(wrongType.status, 415);
    assert.equal(up.calls.length, 0);
    assert.equal((await fetch(`${app.base}/v1/generate`, { headers: { authorization: `Bearer ${t}` } })).status, 405);
    assert.equal((await app.post('/v1/unknown', {}, t)).status, 404);
  });
});
