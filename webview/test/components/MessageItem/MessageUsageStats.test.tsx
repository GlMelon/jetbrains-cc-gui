import { cleanup, render, screen } from '@testing-library/react';
import type { TFunction } from 'i18next';
import { afterEach, describe, expect, it } from 'vitest';

import { MessageUsageStats } from '../../../src/components/MessageItem/MessageUsageStats';

const t = ((key: string) => key) as TFunction;

const baseProps = {
  inputTokens: 37_000,
  outputTokens: 353,
  cacheReadTokens: 36_310,
  durationMs: 65_000,
  t,
};

describe('MessageUsageStats detailed output', () => {
  afterEach(cleanup);

  it('hides entire usage area when detailedOutputEnabled is false', () => {
    render(<MessageUsageStats {...baseProps} detailedOutputEnabled={false} />);

    expect(screen.queryByText('chat.usageStats.input')).toBeNull();
    expect(screen.queryByText('chat.usageStats.output')).toBeNull();
    expect(screen.queryByText('chat.usageStats.cacheRead')).toBeNull();
    expect(screen.queryByText('chat.usageStats.total')).toBeNull();
    expect(screen.queryByText('chat.usageStats.duration')).toBeNull();
  });

  it('shows usage area with cacheRead when detailedOutputEnabled is true', () => {
    render(<MessageUsageStats {...baseProps} detailedOutputEnabled />);

    expect(screen.getByText('chat.usageStats.input')).toBeTruthy();
    expect(screen.getByText('chat.usageStats.output')).toBeTruthy();
    expect(screen.getByText('chat.usageStats.cacheRead')).toBeTruthy();
    expect(screen.getByText('chat.usageStats.total')).toBeTruthy();
    expect(screen.getByText('chat.usageStats.duration')).toBeTruthy();
  });

  it('hides cacheRead when cacheReadTokens is null or zero', () => {
    render(<MessageUsageStats {...baseProps} cacheReadTokens={0} detailedOutputEnabled />);

    expect(screen.getByText('chat.usageStats.input')).toBeTruthy();
    expect(screen.getByText('chat.usageStats.output')).toBeTruthy();
    expect(screen.queryByText('chat.usageStats.cacheRead')).toBeNull();
  });

  it('updates immediately when the canonical setting changes', () => {
    const { rerender } = render(<MessageUsageStats {...baseProps} detailedOutputEnabled={false} />);

    expect(screen.queryByText('chat.usageStats.input')).toBeNull();

    rerender(<MessageUsageStats {...baseProps} detailedOutputEnabled />);

    expect(screen.getByText('chat.usageStats.input')).toBeTruthy();
    expect(screen.getByText('chat.usageStats.cacheRead')).toBeTruthy();
  });
});
