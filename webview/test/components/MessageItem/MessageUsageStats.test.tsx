import { cleanup, render, screen } from '@testing-library/react';
import type { TFunction } from 'i18next';
import { afterEach, describe, expect, it } from 'vitest';

import { MessageUsageStats } from '../../../src/components/MessageItem/MessageUsageStats';

const t = ((key: string) => key) as TFunction;

const baseProps = {
  inputTokens: 37_000,
  outputTokens: 353,
  cacheCreationTokens: 10,
  cacheReadTokens: 36_310,
  costUsd: 0.00893125,
  durationMs: 65_000,
  t,
};

describe('MessageUsageStats detailed output', () => {
  afterEach(cleanup);

  it('keeps cache and cost details hidden by default', () => {
    render(<MessageUsageStats {...baseProps} detailedOutputEnabled={false} />);

    expect(screen.getByText('chat.usageStats.input')).toBeTruthy();
    expect(screen.getByText('chat.usageStats.output')).toBeTruthy();
    expect(screen.getByText('chat.usageStats.total')).toBeTruthy();
    expect(screen.getByText('chat.usageStats.duration')).toBeTruthy();
    expect(screen.queryByText('usage.cacheWrite')).toBeNull();
    expect(screen.queryByText('usage.cacheRead')).toBeNull();
    expect(screen.queryByText('usage.totalCost')).toBeNull();
  });

  it('shows backend-provided cache and cost details when enabled', () => {
    render(<MessageUsageStats {...baseProps} detailedOutputEnabled />);

    expect(screen.getByText('usage.cacheWrite')).toBeTruthy();
    expect(screen.getByText('usage.cacheRead')).toBeTruthy();
    expect(screen.getByText('usage.totalCost')).toBeTruthy();
    expect(screen.getByText('$0.0089')).toBeTruthy();
  });

  it('updates immediately when the canonical setting changes', () => {
    const { rerender } = render(<MessageUsageStats {...baseProps} detailedOutputEnabled={false} />);

    expect(screen.queryByText('usage.totalCost')).toBeNull();

    rerender(<MessageUsageStats {...baseProps} detailedOutputEnabled />);

    expect(screen.getByText('usage.totalCost')).toBeTruthy();
    expect(screen.getByText('usage.cacheRead')).toBeTruthy();
  });
});
