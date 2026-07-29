import { describe, expect, it } from 'vitest';

import type { ClaudeMessage } from '../types';
import {
  extractMessageUsage,
  formatDurationMs,
  formatTokenCount,
  formatUsdCost,
} from './messageUsage';

function assistantMessage(raw: Record<string, unknown>): ClaudeMessage {
  return { type: 'assistant', raw };
}

describe('extractMessageUsage', () => {
  it('extracts backend-normalized turn usage and backend-computed cost', () => {
    const usage = extractMessageUsage(
      assistantMessage({
        turnUsage: {
          input_tokens: 690,
          output_tokens: 353,
          cache_creation_input_tokens: 10,
          cache_read_input_tokens: 36_310,
        },
        turnCostUsd: 0.00893125,
      }),
    );

    expect(usage).toEqual({
      inputTokens: 37_010,
      outputTokens: 353,
      cacheCreationTokens: 10,
      cacheReadTokens: 36_310,
      costUsd: 0.00893125,
    });
  });

  it('does not expose invalid or non-positive backend cost values', () => {
    for (const turnCostUsd of [0, -1, Number.NaN, Number.POSITIVE_INFINITY, '0.01']) {
      const usage = extractMessageUsage(
        assistantMessage({
          turnUsage: { input_tokens: 10, output_tokens: 5 },
          turnCostUsd,
        }),
      );

      expect(usage).not.toBeNull();
      expect(usage?.costUsd).toBeUndefined();
    }
  });

  it('returns null without meaningful turn usage', () => {
    expect(extractMessageUsage(assistantMessage({}))).toBeNull();
    expect(
      extractMessageUsage(
        assistantMessage({
          turnUsage: {
            input_tokens: 0,
            output_tokens: 0,
            cache_creation_input_tokens: 0,
            cache_read_input_tokens: 0,
          },
          turnCostUsd: 0.01,
        }),
      ),
    ).toBeNull();
  });
});

describe('message usage formatting', () => {
  it('formats durations and token counts', () => {
    expect(formatDurationMs(65_000)).toBe('1:05');
    expect(formatDurationMs(3_661_000)).toBe('1:01:01');
    expect(formatTokenCount(1234)).toBe((1234).toLocaleString());
  });

  it('formats USD cost at useful precision', () => {
    expect(formatUsdCost(0.00001)).toBe('<$0.0001');
    expect(formatUsdCost(0.00456)).toBe('$0.0046');
    expect(formatUsdCost(0.4567)).toBe('$0.457');
    expect(formatUsdCost(1.234)).toBe('$1.23');
  });
});
