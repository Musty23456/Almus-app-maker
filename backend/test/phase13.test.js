'use strict';
// Phase 13: the three agent tasks (agent_plan, agent_task, agent_fix) are known to the backend, ask Gemini for JSON,
// carry the reviewed safety rules, and a multi-turn tool-calling conversation (user / model / user) is accepted.
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

test('the agent tasks exist and are structured (JSON) tasks', () => {
  for (const name of ['agent_plan', 'agent_task', 'agent_fix']) {
    assert.ok(TASK_NAMES.includes(name), name);
    assert.equal(taskInfo(name).json, true, name);
  }
});

test('every agent task carries the data-not-instructions and no-secrets rules and forbids deleting', () => {
  for (const name of ['agent_plan', 'agent_task', 'agent_fix']) {
    const s = taskInfo(name).system;
    assert.match(s, /DATA to work on/, name);
    assert.match(s, /Never output secrets/, name);
    assert.match(s, /delete/i, name);
    assert.match(s, /ONE JSON object/, name);
  }
});

test('agent_plan asks for the shape the app parses: tasks with kind, paths and depends_on', () => {
  const s = taskInfo('agent_plan').system;
  for (const key of ['"understanding"', '"assumptions"', '"tasks"', '"depends_on"', '"coordinate"', '"paths"']) assert.ok(s.includes(key), key);
  for (const kind of ['scaffold', 'code', 'edit', 'dependencies', 'api', 'database', 'ui', 'explain', 'refactor', 'docs', 'tests', 'fix', 'analyze']) assert.ok(s.includes(kind), kind);
});

test('agent_task describes both answer shapes: changes and tool_calls (read_file, search, list_files)', () => {
  const s = taskInfo('agent_task').system;
  for (const key of ['"changes"', '"tool_calls"', 'read_file', 'search', 'list_files', '"edits"']) assert.ok(s.includes(key), key);
});

test('the agent tasks are accepted by /v1/generate and Gemini is asked for JSON', async () => {
  await withApp((req, body, res) => json(res, 200, ok('{"summary":"x","changes":[]}')), async (app, up) => {
    const token = await app.token();
    for (const task of ['agent_plan', 'agent_task', 'agent_fix']) {
      const r = await app.post('/v1/generate', { task, messages: [msg('do it')] }, token);
      assert.equal(r.status, 200, task);
    }
    assert.equal(up.calls.length, 3);
    for (const c of up.calls) assert.equal(c.body.generationConfig.responseMimeType, 'application/json');
  });
});

test('a tool-calling conversation (user, model, user) is forwarded in order', async () => {
  await withApp((req, body, res) => json(res, 200, ok('{"summary":"done","changes":[]}')), async (app, up) => {
    const token = await app.token();
    const messages = [
      { role: 'user', content: 'TASK: add a screen' },
      { role: 'model', content: '{"tool_calls":[{"tool":"read_file","args":{"path":"a.kt"}}]}' },
      { role: 'user', content: 'TOOL RESULTS: ...' },
    ];
    const r = await app.post('/v1/generate', { task: 'agent_task', messages }, token);
    assert.equal(r.status, 200);
    const sent = up.calls[0].body.contents;
    assert.deepEqual(sent.map((c) => c.role), ['user', 'model', 'user']);
  });
});

test('a conversation that ends with a model message is refused (the app always ends with a user message)', async () => {
  await withApp((req, body, res) => json(res, 200, ok('x')), async (app, up) => {
    const token = await app.token();
    const r = await app.post('/v1/generate', { task: 'agent_task', messages: [{ role: 'user', content: 'a' }, { role: 'model', content: 'b' }] }, token);
    assert.equal(r.status, 400);
    assert.equal(up.calls.length, 0);
  });
});
