import { memo } from 'react';
import type { TFunction } from 'i18next';

import { formatDurationMs, formatTokenCount } from '../../utils/messageUsage';
import { CountUp } from '../react-bits';

interface MessageUsageStatsProps {
  inputTokens: number | null;
  outputTokens: number | null;
  cacheReadTokens?: number | null;
  detailedOutputEnabled?: boolean;
  durationMs: number | null;
  t: TFunction;
  durationLabelKey?: string;
  /** Whether to animate token counts (default: true) */
  animateCounts?: boolean;
}

/**
 * Usage stats bar displayed after each completed assistant message.
 * Controlled by detailedOutputEnabled setting - when enabled, shows the full usage area;
 * when disabled, hides the entire usage area.
 */
export const MessageUsageStats = memo(function MessageUsageStats({
  inputTokens,
  outputTokens,
  cacheReadTokens = null,
  detailedOutputEnabled = false,
  durationMs,
  t,
  durationLabelKey = 'chat.usageStats.duration',
  animateCounts = true,
}: MessageUsageStatsProps) {
  // detailedOutputEnabled controls the entire usage area visibility
  if (!detailedOutputEnabled) return null;

  const hasTokens =
    (inputTokens !== null && inputTokens > 0) || (outputTokens !== null && outputTokens > 0);
  const hasDuration = durationMs !== null && durationMs > 0;
  const totalTokens = (inputTokens ?? 0) + (outputTokens ?? 0);
  const showCacheRead = cacheReadTokens !== null && cacheReadTokens > 0;

  if (!hasTokens && !hasDuration) return null;

  return (
    <div className="usage-stats">
      {/* Input tokens */}
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

      {/* Output tokens */}
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

      {/* Cache read tokens */}
      {showCacheRead && (
        <div className="usage-item type-cache-read">
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <path d="M21 21v-4a2 2 0 00-2-2H5a2 2 0 00-2 2v4" />
            <polyline points="7 11 12 6 17 11" />
            <line x1="12" y1="6" x2="12" y2="18" />
          </svg>
          <span>{t('chat.usageStats.cacheRead')}</span>
          <span className="usage-value">{formatTokenCount(cacheReadTokens)}</span>
        </div>
      )}

      {/* Total tokens */}
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

      {/* Duration */}
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
