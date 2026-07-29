import { describe, it, expect, afterEach, vi } from 'vitest';
import { canUseLocalStorage } from './storageAvailability.js';

describe('canUseLocalStorage', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('returns true when localStorage is writable (jsdom default)', () => {
    expect(canUseLocalStorage()).toBe(true);
  });

  it('returns false when setItem throws (privacy mode / quota denied)', () => {
    vi.spyOn(window.localStorage, 'setItem').mockImplementation(() => {
      throw new DOMException('quota exceeded', 'QuotaExceededError');
    });

    expect(canUseLocalStorage()).toBe(false);
  });

  it('cleans up its probe key after a successful check', () => {
    canUseLocalStorage();
    expect(window.localStorage.getItem('__localStorage_test__')).toBeNull();
  });
});
