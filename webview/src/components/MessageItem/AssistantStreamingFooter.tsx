import type { ReactElement } from 'react';
import type { TFunction } from 'i18next';

import { formatLiveElapsedMs, useLiveElapsedMs } from './useLiveElapsedMs';

interface AssistantStreamingFooterProps {
  elapsedMs?: number;
  startedAt?: number | null;
  t: TFunction;
}

export function AssistantStreamingFooter({ elapsedMs, startedAt, t }: AssistantStreamingFooterProps): ReactElement {
  const liveElapsedMs = useLiveElapsedMs({ elapsedMs, startedAt, active: true });
  const elapsedLabel = formatLiveElapsedMs(liveElapsedMs);

  return (
    <div className="assistant-streaming-footer" role="status" aria-live="polite">
      <span className="assistant-streaming-footer-dots" aria-hidden="true">
        <span />
        <span />
        <span />
      </span>
      <span className="assistant-streaming-footer-label">{t('chat.streamingResponse')}</span>
      {elapsedLabel ? (
        <span className="assistant-streaming-footer-elapsed">{elapsedLabel}</span>
      ) : null}
    </div>
  );
}
