import { describe, it, expect, afterEach } from 'vitest';
import { canUseLocalStorage } from './storageAvailability.js';

describe('canUseLocalStorage', () => {
  const originalSetItem = window.localStorage.setItem.bind(window.localStorage);
  const originalRemoveItem = window.localStorage.removeItem.bind(window.localStorage);

  afterEach(() => {
    window.localStorage.setItem = originalSetItem;
    window.localStorage.removeItem = originalRemoveItem;
  });

  it('returns true when localStorage is writable (jsdom default)', () => {
    expect(canUseLocalStorage()).toBe(true);
  });

  it('returns false when setItem throws (privacy mode / quota denied)', () => {
    window.localStorage.setItem = () => {
      throw new DOMException('quota exceeded', 'QuotaExceededError');
    };
    expect(canUseLocalStorage()).toBe(false);
  });

  it('cleans up its probe key after a successful check', () => {
    canUseLocalStorage();
    expect(window.localStorage.getItem('__localStorage_test__')).toBeNull();
  });
});
