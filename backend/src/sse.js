'use strict';

/** Incremental Server-Sent-Events parser: feed text chunks, get complete events. Handles CRLF and split chunks. */
class SseParser {
  constructor() { this.buffer = ''; }
  push(chunk) {
    this.buffer += chunk;
    const events = [];
    for (;;) {
      const m = /\r?\n\r?\n/.exec(this.buffer);
      if (!m) break;
      const raw = this.buffer.slice(0, m.index);
      this.buffer = this.buffer.slice(m.index + m[0].length);
      const ev = parseBlock(raw);
      if (ev) events.push(ev);
    }
    return events;
  }
  /** Call at end of stream: a final event without blank line is still delivered. */
  end() {
    const rest = this.buffer.trim();
    this.buffer = '';
    if (!rest) return [];
    const ev = parseBlock(rest);
    return ev ? [ev] : [];
  }
}

function parseBlock(raw) {
  let event = 'message';
  const data = [];
  for (const line of raw.split(/\r?\n/)) {
    if (line === '' || line.startsWith(':')) continue;
    const c = line.indexOf(':');
    const field = c === -1 ? line : line.slice(0, c);
    let value = c === -1 ? '' : line.slice(c + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'event') event = value;
    else if (field === 'data') data.push(value);
  }
  if (data.length === 0) return null;
  return { event, data: data.join('\n') };
}

function formatSse(event, obj) { return `event: ${event}\ndata: ${JSON.stringify(obj)}\n\n`; }

module.exports = { SseParser, formatSse };
