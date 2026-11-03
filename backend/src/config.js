'use strict';
const crypto = require('node:crypto');

/** Reads and validates configuration. Throws ConfigError with a clear message; never prints secret values. */
class ConfigError extends Error {}

function int(env, name, def, min, max) {
  const raw = env[name];
  if (raw === undefined || raw === '') return def;
  const n = Number(raw);
  if (!Number.isInteger(n) || n < min || n > max) throw new ConfigError(`${name} must be an integer between ${min} and ${max}.`);
  return n;
}

function parseAccessCodes(raw) {
  const users = new Map();
  for (const part of String(raw || '').split(',').map((s) => s.trim()).filter(Boolean)) {
    const eq = part.indexOf('=');
    if (eq <= 0) throw new ConfigError('ALMUS_ACCESS_CODES entries must look like name=code.');
    const name = part.slice(0, eq).trim();
    const code = part.slice(eq + 1).trim();
    if (!/^[A-Za-z0-9_.-]{1,40}$/.test(name)) throw new ConfigError('Access code names may use letters, digits, _ . - (max 40).');
    let digest;
    if (code.startsWith('sha256:')) {
      const hex = code.slice(7).toLowerCase();
      if (!/^[0-9a-f]{64}$/.test(hex)) throw new ConfigError(`Access code "${name}": sha256 value must be 64 hex characters.`);
      digest = Buffer.from(hex, 'hex');
    } else {
      if (code.length < 16) throw new ConfigError(`Access code "${name}" is too short (minimum 16 characters).`);
      digest = crypto.createHash('sha256').update(code).digest();
    }
    users.set(name, digest);
  }
  return users;
}

function loadConfig(env = process.env) {
  const apiKey = (env.GEMINI_API_KEY || '').trim();
  if (!apiKey) throw new ConfigError('GEMINI_API_KEY is not set.');
  const tokenSecret = env.ALMUS_TOKEN_SECRET || '';
  if (tokenSecret.length < 32) throw new ConfigError('ALMUS_TOKEN_SECRET must be at least 32 characters.');
  const users = parseAccessCodes(env.ALMUS_ACCESS_CODES);
  if (users.size === 0) throw new ConfigError('ALMUS_ACCESS_CODES is empty: nobody could sign in.');
  const models = (env.GEMINI_MODELS || 'gemini-2.5-flash,gemini-2.5-pro').split(',').map((s) => s.trim()).filter(Boolean);
  if (models.length === 0 || models.some((m) => !/^[A-Za-z0-9._-]{1,80}$/.test(m))) throw new ConfigError('GEMINI_MODELS contains an invalid model id.');
  const defaultModel = (env.GEMINI_DEFAULT_MODEL || models[0]).trim();
  if (!models.includes(defaultModel)) throw new ConfigError('GEMINI_DEFAULT_MODEL must be one of GEMINI_MODELS.');
  const base = (env.GEMINI_API_BASE || 'https://generativelanguage.googleapis.com').replace(/\/+$/, '');
  if (!/^https?:\/\//.test(base)) throw new ConfigError('GEMINI_API_BASE must start with http:// or https://');
  return Object.freeze({
    port: int(env, 'PORT', 8080, 0, 65535),
    host: env.HOST || '0.0.0.0',
    apiKey,
    apiBase: base,
    tokenSecret,
    users,
    models,
    defaultModel,
    maxOutputTokens: int(env, 'GEMINI_MAX_OUTPUT_TOKENS', 32768, 256, 65536),
    timeoutMs: int(env, 'GEMINI_TIMEOUT_MS', 120000, 1000, 600000),
    maxRetries: int(env, 'GEMINI_MAX_RETRIES', 2, 0, 5),
    retryBaseMs: int(env, 'GEMINI_RETRY_BASE_MS', 500, 1, 30000),
    tokenTtlSeconds: int(env, 'TOKEN_TTL_SECONDS', 3600, 60, 86400),
    rateLimitPerMinute: int(env, 'RATE_LIMIT_PER_MINUTE', 20, 1, 10000),
    rateLimitPerDay: int(env, 'RATE_LIMIT_PER_DAY', 600, 1, 1000000),
    loginMaxFailures: int(env, 'LOGIN_MAX_FAILURES', 10, 1, 1000),
    maxBodyBytes: int(env, 'MAX_BODY_BYTES', 1500000, 1024, 20000000),
    maxPromptChars: int(env, 'MAX_PROMPT_CHARS', 400000, 1000, 5000000),
    trustProxy: env.TRUST_PROXY === '1',
  });
}

module.exports = { loadConfig, ConfigError, parseAccessCodes };
