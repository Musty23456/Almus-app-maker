'use strict';

/** Defence in depth: the app already filters secrets before sending; the backend removes what still slips through. */
const PATTERNS = [
  [/-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----/g, () => '[REDACTED PRIVATE KEY]'],
  [/AIza[0-9A-Za-z_\-]{35}/g, () => '[REDACTED API KEY]'],
  [/\bgh[pousr]_[A-Za-z0-9]{30,}\b/g, () => '[REDACTED GITHUB TOKEN]'],
  [/\bgithub_pat_[A-Za-z0-9_]{40,}\b/g, () => '[REDACTED GITHUB TOKEN]'],
  [/\bsk-[A-Za-z0-9_\-]{30,}\b/g, () => '[REDACTED API KEY]'],
  [/\bAKIA[0-9A-Z]{16}\b/g, () => '[REDACTED AWS KEY]'],
  [/\bxox[baprs]-[A-Za-z0-9-]{10,}\b/g, () => '[REDACTED SLACK TOKEN]'],
  [
    /(\b(?:storePassword|keyPassword|password|passwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token)\b\s*[=:]\s*)(["']?)[^\s"',;]{4,}(["']?)/gi,
    (_m, head, q1, q2) => `${head}${q1}[REDACTED]${q2}`,
  ],
];

function redactText(text) {
  let count = 0;
  let out = text;
  for (const [re, fn] of PATTERNS) {
    out = out.replace(re, (...args) => { count++; return fn(...args); });
  }
  return { text: out, count };
}

module.exports = { redactText };
