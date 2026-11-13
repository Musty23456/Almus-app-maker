'use strict';
// Phase 22: the advanced error diagnosis tasks (debug and debug_followup).
const test = require('node:test');
const assert = require('node:assert/strict');
const { TASK_NAMES, taskInfo } = require('../src/prompts');
const { ok, startUpstream, startApp, msg } = require('./helpers');

const json = (res, status, obj) => { res.writeHead(status, { 'content-type': 'application/json' }); res.end(JSON.stringify(obj)); };

async function withApp(handler, fn) {
  const up = await startUpstream(handler);
  const app = await startApp(up, {});
  try { await fn(app, up); } finally { await app.close(); await up.close(); }
}

for (const name of ['debug', 'debug_followup']) {
  test(`${name} exists, is structured, and keeps the shared safety rules`, () => {
    assert.ok(TASK_NAMES.includes(name));
    const t = taskInfo(name);
    assert.equal(t.json, true);
    assert.match(t.system, /DATA to work on/);
    assert.match(t.system, /Never output secrets/);
    assert.match(t.system, /ONE JSON object/);
  });

  test(`${name} forbids inventing evidence and requires an honest confidence`, () => {
    const s = taskInfo(name).system;
    assert.match(s, /Do not invent file names, line numbers, versions/);
    assert.match(s, /ROOT CAUSE/);
    assert.match(s, /insufficient/);
    assert.match(s, /"high" only when the given text itself shows the cause/);
    assert.match(s, /hint to check, not as truth/);
    assert.match(s, /retrying may be enough/);
  });

  test(`${name} is accepted by /v1/generate and Gemini is asked for JSON`, async () => {
    await withApp((req, body, res) => json(res, 200, ok('{"summary":"x","answer":"y"}')), async (app, up) => {
      const token = await app.token();
      const r = await app.post('/v1/generate', { task: name, messages: [msg('Build failed: Unresolved reference: foo')] }, token);
      assert.equal(r.status, 200);
      assert.equal(up.calls[0].body.generationConfig.responseMimeType, 'application/json');
      assert.match(JSON.stringify(up.calls[0].body), /Diagnosis rules/);
    });
  });
}

test('an unknown task is still refused and lists the new task', async () => {
  await withApp((req, body, res) => json(res, 200, ok('{}')), async (app) => {
    const token = await app.token();
    const r = await app.post('/v1/generate', { task: 'debug_everything', messages: [msg('x')] }, token);
    assert.equal(r.status, 400);
    assert.match(JSON.stringify(r.json), /debug_followup/);
  });
});
