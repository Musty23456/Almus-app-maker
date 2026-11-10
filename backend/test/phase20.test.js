'use strict';
// Phase 20: the preview_ui task (UI generation for the sandboxed live preview).
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

test('preview_ui exists, is structured, and keeps the shared safety rules', () => {
  assert.ok(TASK_NAMES.includes('preview_ui'));
  const t = taskInfo('preview_ui');
  assert.equal(t.json, true);
  assert.match(t.system, /DATA to work on/);
  assert.match(t.system, /Never output secrets/);
  assert.match(t.system, /ONE JSON object/);
});

test('preview_ui asks for the answer shape the app parses and names all three targets', () => {
  const s = taskInfo('preview_ui').system;
  for (const key of ['"summary"', '"files"', '"path"', '"content"', '"notes"', 'web', 'android_xml', 'android_compose']) assert.ok(s.includes(key), key);
});

test('preview_ui tells the model the preview has no network and no external resources', () => {
  const s = taskInfo('preview_ui').system;
  assert.match(s, /no network access/i);
  assert.match(s, /No external resources at all/);
  assert.match(s, /prefers-color-scheme/);
  assert.match(s, /viewport/);
  assert.match(s, /app\/src\/main\/res\/layout\//);
  assert.match(s, /@Preview/);
});

test('preview_ui is accepted by /v1/generate and Gemini is asked for JSON', async () => {
  await withApp((req, body, res) => json(res, 200, ok('{"summary":"x","files":[{"path":"index.html","content":"<p>x</p>"}]}')), async (app, up) => {
    const token = await app.token();
    const r = await app.post('/v1/generate', { task: 'preview_ui', messages: [msg('TARGET: web\n\nREQUEST:\nA login screen')] }, token);
    assert.equal(r.status, 200);
    assert.equal(up.calls[0].body.generationConfig.responseMimeType, 'application/json');
  });
});
