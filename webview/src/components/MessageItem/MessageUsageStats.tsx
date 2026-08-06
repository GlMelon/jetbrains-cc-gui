import { memo } from 'react';
import type { TFunction } from 'i18next';

import { formatDurationMs, formatTokenCount, formatUsdCost } from '../../utils/messageUsage';
import { CountUp } from '../react-bits';

interface MessageUsageStatsProps {
  inputTokens: number | null;
  outputTokens: number | null;
  cacheCreationTokens?: number | null;
  cacheReadTokens?: number | null;
  costUsd?: number | null;
  detailedOutputEnabled?: boolean;
  durationMs: number | null;
  t: TFunction;
  durationLabelKey?: string;
  /** Whether to animate token counts (default: true) */
  animateCounts?: boolean;
}

/**
 * Usage stats bar displayed after each completed assistant message.
 * The default view stays compact; cache details and backend-computed cost are opt-in.
 */
export const MessageUsageStats = memo(function MessageUsageStats({
  inputTokens,
  outputTokens,
  cacheCreationTokens = null,
  cacheReadTokens = null,
  costUsd = null,
  detailedOutputEnabled = false,
  durationMs,
  t,
  durationLabelKey = 'chat.usageStats.duration',
  animateCounts = true,
}: MessageUsageStatsProps) {
  const hasTokens =
    (inputTokens !== null && inputTokens > 0) || (outputTokens !== null && outputTokens > 0);
  const hasDuration = durationMs !== null && durationMs > 0;
  const totalTokens = (inputTokens ?? 0) + (outputTokens ?? 0);
  const showCacheCreation =
    detailedOutputEnabled && cacheCreationTokens !== null && cacheCreationTokens > 0;
  const showCacheRead = detailedOutputEnabled && cacheReadTokens !== null && cacheReadTokens > 0;
  const showCost = detailedOutputEnabled && costUsd !== null && costUsd > 0;

  if (!hasTokens && !hasDuration && !showCost) return null;

  return (
    <div className="usage-stats">
      {hasTokens && inputTokens !== null && inputTokens > 0 && (
        <div className="usage-item type-in">
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" />
            <polyline points="17 8 12 3 7 8" />
            <line x1="12" y1="3" x2="12" y2="15" />
          </svg>
          <span>{t('chat.usageStats.input')}</span>
          <span className="usage-value">
            {animateCounts ? (
              <CountUp end={inputTokens} duration={800} />
            ) : (
              formatTokenCount(inputTokens)
            )}
          </span>
        </div>
      )}

      {hasTokens && outputTokens !== null && outputTokens > 0 && (
        <div className="usage-item type-out">
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" />
            <polyline points="7 10 12 15 17 10" />
            <line x1="12" y1="15" x2="12" y2="3" />
          </svg>
          <span>{t('chat.usageStats.output')}</span>
          <span className="usage-value">
            {animateCounts ? (
              <CountUp end={outputTokens} duration={800} />
            ) : (
              formatTokenCount(outputTokens)
            )}
          </span>
        </div>
      )}

      {hasTokens && totalTokens > 0 && (
        <div className="usage-item type-total">
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <path d="M4 6h16" />
            <path d="M4 12h16" />
            <path d="M4 18h16" />
          </svg>
          <span>{t('chat.usageStats.total')}</span>
          <span className="usage-value">
            {animateCounts ? (
              <CountUp end={totalTokens} duration={1000} />
            ) : (
              formatTokenCount(totalTokens)
            )}
          </span>
        </div>
      )}

      {showCacheCreation && (
        <div className="usage-item type-cache-write">
          <span>{t('usage.cacheWrite')}</span>
          <span className="usage-value">{formatTokenCount(cacheCreationTokens)}</span>
        </div>
      )}

      {showCacheRead && (
        <div className="usage-item type-cache-read">
          <span>{t('usage.cacheRead')}</span>
          <span className="usage-value">{formatTokenCount(cacheReadTokens)}</span>
        </div>
      )}

      {showCost && (
        <div className="usage-item type-cost">
          <span>{t('usage.totalCost')}</span>
          <span className="usage-value">{formatUsdCost(costUsd)}</span>
        </div>
      )}

      {hasDuration && (
        <div className="usage-item type-duration">
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <circle cx="12" cy="12" r="10" />
            <polyline points="12 6 12 12 16 14" />
          </svg>
          <span>{t(durationLabelKey)}</span>
          <span className="usage-value">{formatDurationMs(durationMs!)}</span>
        </div>
      )}
    </div>
  );
});
