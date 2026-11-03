'use strict';

/** Sliding-window counter per key. Old entries are dropped on use and by sweep(), so memory stays bounded. */
class SlidingWindowLimiter {
  constructor(limit, windowMs, now = () => Date.now()) {
    this.limit = limit; this.windowMs = windowMs; this.now = now; this.hits = new Map();
  }
  _live(key) { const t = this.now(); const l = (this.hits.get(key) || []).filter((x) => t - x < this.windowMs); this.hits.set(key, l); return l; }
  /** Returns { allowed, retryAfterSeconds }. Records the hit only when allowed. */
  take(key) {
    const t = this.now();
    const list = this._live(key);
    if (list.length >= this.limit) return { allowed: false, retryAfterSeconds: Math.max(1, Math.ceil((list[0] + this.windowMs - t) / 1000)) };
    list.push(t);
    return { allowed: true, retryAfterSeconds: 0 };
  }
  /** Counts a failure without checking (login attempts). */
  record(key) { this._live(key).push(this.now()); }
  /** Seconds until allowed again, or 0 when not blocked. */
  blocked(key) {
    const t = this.now();
    const l = this._live(key);
    return l.length >= this.limit ? Math.max(1, Math.ceil((l[0] + this.windowMs - t) / 1000)) : 0;
  }
  reset(key) { this.hits.delete(key); }
  sweep() { for (const k of [...this.hits.keys()]) if (this._live(k).length === 0) this.hits.delete(k); }
}

module.exports = { SlidingWindowLimiter };
