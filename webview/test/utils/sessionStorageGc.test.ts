import { beforeEach, describe, expect, it } from 'vitest';
import { gcSessionScopedKeys, touchSessionScopedKey } from '../../src/utils/sessionStorageGc';

const DAY_MS = 24 * 60 * 60 * 1000;
const T0 = 1_700_000_000_000;

beforeEach(() => {
  localStorage.clear();
});

describe('sessionStorageGc', () => {
  it('drops keys untouched beyond the TTL (data + index)', () => {
    localStorage.setItem('processed-files-s1', '["a.ts"]');
    touchSessionScopedKey('processed-files-s1', T0);

    gcSessionScopedKeys(T0 + DAY_MS + 1);

    expect(localStorage.getItem('processed-files-s1')).toBeNull();
  });

  it('keeps keys still within the TTL', () => {
    localStorage.setItem('keep-all-base-s1', '3');
    touchSessionScopedKey('keep-all-base-s1', T0);

    gcSessionScopedKeys(T0 + DAY_MS - 1);

    expect(localStorage.getItem('keep-all-base-s1')).toBe('3');
  });

  it('evicts the oldest keys beyond the 400-key LRU cap', () => {
    for (let i = 0; i < 401; i += 1) {
      const key = `session-file-ledger-meta-s${i}`;
      localStorage.setItem(key, '{}');
      touchSessionScopedKey(key, T0 + i);
    }

    expect(localStorage.getItem('session-file-ledger-meta-s0')).toBeNull();
    expect(localStorage.getItem('session-file-ledger-meta-s400')).toBe('{}');
  });

  it('adopts legacy untracked keys so they expire via TTL', () => {
    localStorage.setItem('processed-files-legacy', '["b.ts"]');

    // First GC adopts the legacy key (not removed yet)
    gcSessionScopedKeys(T0);
    expect(localStorage.getItem('processed-files-legacy')).toBe('["b.ts"]');

    // Once adopted, it ages out past the TTL
    gcSessionScopedKeys(T0 + DAY_MS + 1);
    expect(localStorage.getItem('processed-files-legacy')).toBeNull();
  });

  it('does not touch unrelated localStorage keys', () => {
    localStorage.setItem('unrelated-key', 'value');

    gcSessionScopedKeys(T0 + DAY_MS * 365);

    expect(localStorage.getItem('unrelated-key')).toBe('value');
  });
});
