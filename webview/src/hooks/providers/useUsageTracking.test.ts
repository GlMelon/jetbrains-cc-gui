import { act, renderHook } from '@testing-library/react';
import { useUsageTracking } from './useUsageTracking';

describe('useUsageTracking', () => {
  it('exposes usage counters and setters', () => {
    const { result } = renderHook(() => useUsageTracking());

    expect(result.current.usagePercentage).toBe(0);
    expect(result.current.usageUsedTokens).toBeUndefined();
    expect(result.current.usageMaxTokens).toBeUndefined();
    expect(result.current.tokenDetail).toBeUndefined();

    act(() => {
      result.current.setUsagePercentage(42);
      result.current.setUsageUsedTokens(100);
      result.current.setUsageMaxTokens(200);
    });

    expect(result.current.usagePercentage).toBe(42);
    expect(result.current.usageUsedTokens).toBe(100);
    expect(result.current.usageMaxTokens).toBe(200);
  });
});
