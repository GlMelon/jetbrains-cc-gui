import type { ClaudeMessage } from '../types';

/**
 * Per-message token usage extracted from raw message data.
 */
export interface MessageUsage {
  inputTokens: number;
  outputTokens: number;
  cacheCreationTokens: number;
  cacheReadTokens: number;
  costUsd?: number;
}

/**
 * Format a duration in milliseconds to a human-readable string.
 * - Under 1 minute: "M:SS"
 * - Over 1 hour: "H:MM:SS"
 */
export function formatDurationMs(durationMs: number): string {
  const seconds = Math.max(0, Math.floor(durationMs / 1000));
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const remainder = seconds % 60;
  if (hours > 0) {
    return `${hours}:${String(minutes).padStart(2, '0')}:${String(remainder).padStart(2, '0')}`;
  }
  return `${minutes}:${String(remainder).padStart(2, '0')}`;
}

/**
 * Format token count with locale-specific thousand separators.
 * Example: 1234 → "1,234"
 */
export function formatTokenCount(count: number): string {
  return count.toLocaleString();
}

export function formatUsdCost(cost: number): string {
  if (cost > 0 && cost < 0.0001) return '<$0.0001';
  if (cost < 0.01) return `$${cost.toFixed(4)}`;
  if (cost < 1) return `$${cost.toFixed(3)}`;
  return `$${cost.toFixed(2)}`;
}

/**
 * Extract per-message token usage from the raw message data.
 *
 * The backend stamps a top-level `turnUsage` field when a turn completes
 * (ClaudeMessageHandler.handleResult / CodexMessageHandler.handleResultMessage).
 * This field aggregates every API call in the turn, normalized to the Claude
 * usage schema (input_tokens excludes cache; cache fields are separate).
 *
 * Path: raw.turnUsage.input_tokens / output_tokens
 *
 * NOTE: Do NOT read raw.message.usage or raw.usage here: those carry
 * per-API-call and session-cumulative values that feed the context-usage
 * status bar, and would understate (Claude) or overstate (Codex) what
 * this turn consumed.
 *
 * Returns null if no meaningful usage data is found (aborted turns, history replay).
 */
export function extractMessageUsage(message: ClaudeMessage): MessageUsage | null {
  const raw = message.raw;
  if (!raw || typeof raw !== 'object') return null;

  const rawObj = raw as Record<string, unknown>;

  // Read turnUsage field (whole-turn aggregate stamped by backend)
  const turnUsage = rawObj.turnUsage;
  if (!turnUsage || typeof turnUsage !== 'object') return null;

  const usage = turnUsage as Record<string, unknown>;

  // Extract cache components for full input calculation
  const nonCacheInput = typeof usage.input_tokens === 'number' ? usage.input_tokens : 0;
  const cacheCreation =
    typeof usage.cache_creation_input_tokens === 'number' ? usage.cache_creation_input_tokens : 0;
  const cacheRead =
    typeof usage.cache_read_input_tokens === 'number' ? usage.cache_read_input_tokens : 0;
  const outputTokens = typeof usage.output_tokens === 'number' ? usage.output_tokens : 0;

  // Total input = non-cache input + cache write + cache read
  const inputTokens = nonCacheInput + cacheCreation + cacheRead;

  // Only return if at least one has a positive value
  if (inputTokens <= 0 && outputTokens <= 0) return null;

  const rawCost = rawObj.turnCostUsd;
  const costUsd =
    typeof rawCost === 'number' && Number.isFinite(rawCost) && rawCost > 0 ? rawCost : undefined;

  return {
    inputTokens,
    outputTokens,
    cacheCreationTokens: cacheCreation,
    cacheReadTokens: cacheRead,
    ...(costUsd !== undefined ? { costUsd } : {}),
  };
}
