'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { loadConfig, ConfigError } = require('./config');
const { createServer } = require('./server');

// Tiny .env loader (no dependency). Real environment variables always win. Never commit .env.
function loadDotEnv(file) {
  if (!fs.existsSync(file)) return;
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/.exec(line);
    if (!m || line.trim().startsWith('#')) continue;
    if (process.env[m[1]] === undefined) process.env[m[1]] = m[2].replace(/^(['"])(.*)\1$/, '$2');
  }
}

loadDotEnv(path.join(__dirname, '..', '.env'));
let config;
try { config = loadConfig(); } catch (e) {
  if (e instanceof ConfigError) { process.stderr.write(`Configuration error: ${e.message}\nSee backend/docs/API_CONFIGURATION.md\n`); process.exit(1); }
  throw e;
}
const server = createServer(config);
server.listen(config.port, config.host, () => process.stdout.write(`${JSON.stringify({ msg: 'listening', port: server.address().port, models: config.models, defaultModel: config.defaultModel })}\n`));
for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => server.close(() => process.exit(0)));
