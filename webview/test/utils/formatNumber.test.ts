import { describe, it, expect } from 'vitest';
import { formatCapacity } from '../../src/utils/formatNumber';

describe('formatCapacity', () => {
  it('formats millions as M (integer division)', () => {
    expect(formatCapacity(1_000_000)).toBe('1M');
    expect(formatCapacity(2_000_000)).toBe('2M');
  });

  it('formats thousands as K (rounded)', () => {
    expect(formatCapacity(200_000)).toBe('200K');
    expect(formatCapacity(1_500)).toBe('2K'); // Math.round(1.5) = 2
    expect(formatCapacity(1_000)).toBe('1K');
  });

  it('returns raw value string when < 1K', () => {
    expect(formatCapacity(500)).toBe('500');
    expect(formatCapacity(1)).toBe('1');
  });

  it('falls back when value is undefined', () => {
    expect(formatCapacity(undefined, 200_000)).toBe('200K');
    expect(formatCapacity(undefined, 1_000_000)).toBe('1M');
  });

  it('returns empty string for 0 / NaN / missing value', () => {
    expect(formatCapacity(0)).toBe('');
    expect(formatCapacity(NaN)).toBe('');
    expect(formatCapacity(undefined)).toBe('');
  });
});
