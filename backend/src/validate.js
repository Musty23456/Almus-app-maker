'use strict';
const { TASK_NAMES } = require('./prompts');

class HttpError extends Error {
  constructor(status, code, message, extra = {}) { super(message); this.status = status; this.code = code; this.extra = extra; }
}

const ROLES = new Set(['user', 'model']);

/** Validates a generate request body. Returns a clean object or throws HttpError(400/413). */
function validateGenerate(body, config) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw new HttpError(400, 'bad_request', 'The request body must be a JSON object.');
  const task = body.task === undefined ? 'chat' : body.task;
  if (typeof task !== 'string' || !TASK_NAMES.includes(task)) throw new HttpError(400, 'bad_request', `Unknown task. Use one of: ${TASK_NAMES.join(', ')}.`);
  let model = body.model === undefined || body.model === null || body.model === '' ? config.defaultModel : body.model;
  if (typeof model !== 'string' || !config.models.includes(model)) throw new HttpError(400, 'bad_model', `Model not allowed. Allowed: ${config.models.join(', ')}.`);
  const msgs = body.messages;
  if (!Array.isArray(msgs) || msgs.length === 0) throw new HttpError(400, 'bad_request', '"messages" must be a non-empty array.');
  if (msgs.length > 60) throw new HttpError(400, 'bad_request', 'Too many messages (maximum 60).');
  let total = 0;
  const messages = msgs.map((m, i) => {
    if (!m || typeof m !== 'object' || !ROLES.has(m.role) || typeof m.content !== 'string') {
      throw new HttpError(400, 'bad_request', `Message ${i + 1} must have role "user" or "model" and a text "content".`);
    }
    if (m.content.length === 0) throw new HttpError(400, 'bad_request', `Message ${i + 1} is empty.`);
    total += m.content.length;
    return { role: m.role, content: m.content };
  });
  if (messages[messages.length - 1].role !== 'user') throw new HttpError(400, 'bad_request', 'The last message must be from the user.');
  if (total > config.maxPromptChars) throw new HttpError(413, 'payload_too_large', `The request is too large (${total} characters; limit ${config.maxPromptChars}). Send fewer or smaller files.`);
  let temperature;
  if (body.temperature !== undefined && body.temperature !== null) {
    if (typeof body.temperature !== 'number' || !(body.temperature >= 0 && body.temperature <= 2)) throw new HttpError(400, 'bad_request', '"temperature" must be a number from 0 to 2.');
    temperature = body.temperature;
  }
  return { task, model, messages, temperature };
}

module.exports = { validateGenerate, HttpError };
