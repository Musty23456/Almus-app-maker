'use strict';
// Usage: node scripts/hash-code.js <access code>   -> prints sha256:<hex> to put in ALMUS_ACCESS_CODES (name=sha256:<hex>)
const crypto = require('node:crypto');
const code = process.argv[2];
if (!code || code.length < 16) { console.error('Give an access code of at least 16 characters.'); process.exit(1); }
console.log(`sha256:${crypto.createHash('sha256').update(code).digest('hex')}`);
