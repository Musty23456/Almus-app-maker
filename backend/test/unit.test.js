'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { loadConfig, ConfigError } = require('../src/config');
const { sign, verify, matchAccessCode, issueToken } = require('../src/auth');
const { redactText } = require('../src/redact');
const { SseParser } = require('../src/sse');
const { SlidingWindowLimiter } = require('../src/rateLimit');
const { parseAccessCodes } = require('../src/config');
const { BASE_ENV } = require('./helpers');

test('config: refuses to start without secrets or with weak ones', () => {
  assert.throws(() => loadConfig({ ...BASE_ENV, GEMINI_API_KEY: '' }), ConfigError);
  assert.throws(() => loadConfig({ ...BASE_ENV, ALMUS_TOKEN_SECRET: 'short' }), /at least 32/);
  assert.throws(() => loadConfig({ ...BASE_ENV, ALMUS_ACCESS_CODES: '' }), /empty/);
  assert.throws(() => loadConfig({ ...BASE_ENV, ALMUS_ACCESS_CODES: 'alice=tooshort' }), /too short/);
  assert.throws(() => loadConfig({ ...BASE_ENV, GEMINI_DEFAULT_MODEL: 'nope' }), /one of GEMINI_MODELS/);
  assert.throws(() => loadConfig({ ...BASE_ENV, GEMINI_MODELS: 'bad model!' }), /invalid model/);
  assert.throws(() => loadConfig({ ...BASE_ENV, RATE_LIMIT_PER_MINUTE: '0' }), /RATE_LIMIT_PER_MINUTE/);
});

test('config: models are configurable and the error text never contains the key', () => {
  const c = loadConfig({ ...BASE_ENV, GEMINI_MODELS: 'a-1,b-2', GEMINI_DEFAULT_MODEL: 'b-2' });
  assert.deepEqual(c.models, ['a-1', 'b-2']); assert.equal(c.defaultModel, 'b-2');
  try { loadConfig({ ...BASE_ENV, ALMUS_TOKEN_SECRET: 'tiny' }); } catch (e) { assert.ok(!e.message.includes(BASE_ENV.GEMINI_API_KEY)); }
});

test('access codes: plain and sha256 forms, constant-time match', () => {
  const crypto = require('node:crypto');
  const hex = crypto.createHash('sha256').update('a-very-long-access-code').digest('hex');
  const users = parseAccessCodes(`one=a-very-long-access-code,two=sha256:${hex}`);
  assert.equal(matchAccessCode('a-very-long-access-code', users), 'two'); // last match wins; both names map to same code
  assert.equal(matchAccessCode('wrong', users), null);
  assert.equal(matchAccessCode('', users), null);
  assert.equal(matchAccessCode('x'.repeat(500), users), null);
});

test('tokens: valid, tampered, expired, wrong secret', () => {
  const cfg = { tokenSecret: 's'.repeat(40), tokenTtlSeconds: 60 };
  const { token } = issueToken('alice', cfg, 1_000_000);
  assert.equal(verify(token, cfg.tokenSecret, 1_000_000 + 30_000).sub, 'alice');
  assert.equal(verify(token, cfg.tokenSecret, 1_000_000 + 61_000), null);
  assert.equal(verify(token, 'o'.repeat(40), 1_000_000), null);
  const [body, mac] = token.split('.');
  const forged = Buffer.from(JSON.stringify({ v: 1, sub: 'mallory', iat: 1000, exp: 9999999999 })).toString('base64url');
  assert.equal(verify(`${forged}.${mac}`, cfg.tokenSecret, 1_000_000), null);
  assert.equal(verify(`${body}.`, cfg.tokenSecret), null);
  assert.equal(verify('a.b.c', cfg.tokenSecret), null);
  assert.equal(verify(undefined, cfg.tokenSecret), null);
  // a token without the version field is refused even if correctly signed
  assert.equal(verify(sign({ sub: 'x', exp: 9999999999 }, cfg.tokenSecret), cfg.tokenSecret), null);
});

test('redaction: keys, tokens, private keys, password assignments', () => {
  const input = [
    'val key = "AIzaSyA1234567890abcdefghijklmnopqrstuvw"',
    'token ghp_abcdefghijklmnopqrstuvwxyz0123456789',
    '-----BEGIN PRIVATE KEY-----\nMIIEvQ\n-----END PRIVATE KEY-----',
    'storePassword = "hunter2hunter2"',
    'api_key: sk-abcdefghijklmnopqrstuvwxyz0123456789',
    'normal code fun main() { println("hi") }',
  ].join('\n');
  const { text, count } = redactText(input);
  assert.ok(count >= 5);
  for (const leak of ['AIzaSyA123', 'ghp_abcdef', 'MIIEvQ', 'hunter2', 'sk-abcdef']) assert.ok(!text.includes(leak), leak);
  assert.ok(text.includes('fun main()'));
});

test('sse parser: split chunks, CRLF, comments, final event without blank line', () => {
  const p = new SseParser();
  assert.deepEqual(p.push('data: {"a"'), []);
  assert.deepEqual(p.push(':1}\r\n\r\n: ping\n\nevent: x\ndata: two\n\n'), [{ event: 'message', data: '{"a":1}' }, { event: 'x', data: 'two' }]);
  p.push('data: tail');
  assert.deepEqual(p.end(), [{ event: 'message', data: 'tail' }]);
});

test('sliding window limiter', () => {
  let t = 0; const l = new SlidingWindowLimiter(2, 1000, () => t);
  assert.ok(l.take('a').allowed); assert.ok(l.take('a').allowed);
  const r = l.take('a'); assert.equal(r.allowed, false); assert.equal(r.retryAfterSeconds, 1);
  assert.ok(l.take('b').allowed);
  t = 1001; assert.ok(l.take('a').allowed);
});
