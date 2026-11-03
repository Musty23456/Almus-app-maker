'use strict';
const crypto = require('node:crypto');

const b64u = (buf) => Buffer.from(buf).toString('base64url');

function sign(payload, secret) {
  const body = b64u(JSON.stringify(payload));
  const mac = crypto.createHmac('sha256', secret).update(body).digest('base64url');
  return `${body}.${mac}`;
}

/** Returns the payload, or null for a wrong, tampered or expired token. Constant-time signature check. */
function verify(token, secret, nowMs = Date.now()) {
  if (typeof token !== 'string' || token.length > 2000) return null;
  const dot = token.indexOf('.');
  if (dot <= 0 || token.indexOf('.', dot + 1) !== -1) return null;
  const body = token.slice(0, dot);
  const mac = Buffer.from(token.slice(dot + 1), 'base64url');
  const expected = crypto.createHmac('sha256', secret).update(body).digest();
  if (mac.length !== expected.length || !crypto.timingSafeEqual(mac, expected)) return null;
  let payload;
  try { payload = JSON.parse(Buffer.from(body, 'base64url').toString('utf8')); } catch { return null; }
  if (!payload || typeof payload.sub !== 'string' || typeof payload.exp !== 'number' || payload.v !== 1) return null;
  if (payload.exp * 1000 <= nowMs) return null;
  return payload;
}

/** Finds the user whose access code matches. Compares SHA-256 digests in constant time, over every user. */
function matchAccessCode(code, users) {
  if (typeof code !== 'string' || code.length === 0 || code.length > 200) return null;
  const digest = crypto.createHash('sha256').update(code).digest();
  let found = null;
  for (const [name, expected] of users) {
    if (crypto.timingSafeEqual(digest, expected)) found = name; // no early exit
  }
  return found;
}

function issueToken(user, config, nowMs = Date.now()) {
  const iat = Math.floor(nowMs / 1000);
  const exp = iat + config.tokenTtlSeconds;
  return { token: sign({ v: 1, sub: user, iat, exp }, config.tokenSecret), expiresInSeconds: config.tokenTtlSeconds };
}

function bearer(req) {
  const h = req.headers['authorization'];
  if (typeof h !== 'string') return null;
  const m = /^Bearer ([A-Za-z0-9_\-.]+)$/.exec(h);
  return m ? m[1] : null;
}

module.exports = { sign, verify, matchAccessCode, issueToken, bearer };
