/**
 * GC for session-scoped localStorage keys named per sessionId:
 * `processed-files-<sessionId>` / `keep-all-base-<sessionId>` /
 * `session-file-ledger-meta-<sessionId>` — one key per session, previously
 * never reclaimed.
 *
 * Mirrors the fileTouchRegistry paradigm (cap + TTL): a single JSON index
 * records each key's last-write time; writes call touchSessionScopedKey()
 * which lazily reclaims expired (> TTL) and oldest-beyond-cap (LRU) keys.
 * Legacy keys written before this index existed are adopted on first GC so
 * they expire naturally instead of living forever.
 */

const INDEX_KEY = 'ccgui-session-key-gc-index-v1';
const MAX_TRACKED_KEYS = 400;
/** Keys untouched longer than this are stale and lazily dropped on GC. */
const KEY_TTL_MS = 24 * 60 * 60 * 1000;

const TRACKED_PREFIXES = ['processed-files-', 'keep-all-base-', 'session-file-ledger-meta-'];

/** storage key → last write time (ms) */
type GcIndex = Record<string, number>;

function loadIndex(): GcIndex {
  try {
    const raw = localStorage.getItem(INDEX_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as GcIndex;
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
    return parsed;
  } catch {
    return {};
  }
}

function saveIndex(index: GcIndex): void {
  try {
    localStorage.setItem(INDEX_KEY, JSON.stringify(index));
  } catch {
    // quota / private mode
  }
}

function removeDataKey(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    // ignore
  }
}

/**
 * Reclaim expired / overflow session-scoped keys. Adopts untracked legacy keys
 * (written before the index existed) with `now` so they age out via TTL.
 */
export function gcSessionScopedKeys(now = Date.now()): void {
  const index = loadIndex();
  let changed = false;

  // Adopt legacy tracked keys missing from the index so they eventually expire.
  // Enumerate via length/key(i): Object.keys(localStorage) does not expose
  // stored items in every DOM implementation (e.g. happy-dom).
  try {
    for (let i = 0; i < localStorage.length; i += 1) {
      const key = localStorage.key(i);
      if (!key || key in index) continue;
      if (TRACKED_PREFIXES.some((prefix) => key.startsWith(prefix))) {
        index[key] = now;
        changed = true;
      }
    }
  } catch {
    // localStorage unavailable — nothing to adopt
  }

  // TTL: drop entries untouched for too long
  for (const [key, touchedAt] of Object.entries(index)) {
    if (typeof touchedAt !== 'number' || now - touchedAt > KEY_TTL_MS) {
      delete index[key];
      removeDataKey(key);
      changed = true;
    }
  }

  // LRU: cap total tracked keys, dropping the oldest
  const entries = Object.entries(index);
  if (entries.length > MAX_TRACKED_KEYS) {
    entries.sort((a, b) => a[1] - b[1]);
    for (const [key] of entries.slice(0, entries.length - MAX_TRACKED_KEYS)) {
      delete index[key];
      removeDataKey(key);
    }
    changed = true;
  }

  if (changed) saveIndex(index);
}

/**
 * Record a write of a session-scoped key, then lazily GC. Callers should
 * invoke this right after persisting a `processed-files-*` /
 * `keep-all-base-*` / `session-file-ledger-meta-*` key.
 */
export function touchSessionScopedKey(key: string, now = Date.now()): void {
  try {
    const index = loadIndex();
    index[key] = now;
    saveIndex(index);
  } catch {
    // localStorage unavailable — skip tracking
  }
  gcSessionScopedKeys(now);
}
