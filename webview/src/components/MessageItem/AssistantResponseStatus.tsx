import type { ReactElement } from 'react';
import type { AssistantResponseStatusPayload } from '../../types';
import { formatLiveElapsedMs, useLiveElapsedMs } from './useLiveElapsedMs';

interface AssistantResponseStatusProps {
  payload?: AssistantResponseStatusPayload;
}

export function AssistantResponseStatus({ payload }: AssistantResponseStatusProps): ReactElement | null {
  const liveElapsedMs = useLiveElapsedMs({ elapsedMs: payload?.elapsedMs, active: payload?.active ?? false });

  if (!payload) return null;

  const elapsedLabel = formatLiveElapsedMs(liveElapsedMs);
  const phaseClass = payload.phase ? ` phase-${payload.phase}` : '';

  return (
    <div
      className={`assistant-response-status${phaseClass} ${payload.active ? 'active' : 'inactive'}`}
      role="status"
      aria-live="polite"
    >
      <div className="assistant-response-status-ring" aria-hidden="true" />
      <div className="assistant-response-status-body">
        <div className="assistant-response-status-head">
          <span className="assistant-response-status-title">{payload.title}</span>
          {elapsedLabel ? (
            <span className="assistant-response-status-elapsed">{elapsedLabel}</span>
          ) : null}
        </div>
        {payload.description ? (
          <div className="assistant-response-status-description">{payload.description}</div>
        ) : null}
        <div className="assistant-response-status-footer">
          <span className="assistant-response-status-provider">{payload.providerLabel}</span>
          <span className="assistant-response-status-dots" aria-hidden="true">
            <span />
            <span />
            <span />
          </span>
        </div>
      </div>
    </div>
  );
}
