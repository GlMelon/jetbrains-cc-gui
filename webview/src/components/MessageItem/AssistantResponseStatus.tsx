import type { ReactElement } from 'react';
import type { AssistantResponseStatusPayload } from '../../types';

interface AssistantResponseStatusProps {
  payload?: AssistantResponseStatusPayload;
}

export function AssistantResponseStatus({ payload }: AssistantResponseStatusProps): ReactElement | null {
  if (!payload) return null;

  const phaseClass = payload.phase ? ` phase-${payload.phase}` : '';
  const activityClass = payload.active ? 'active' : 'inactive';

  return (
    <div
      className={`assistant-response-status${phaseClass} ${activityClass}`}
      role="status"
      aria-live="polite"
    >
      <div className="assistant-response-status-ring" aria-hidden="true" />
      <div className="assistant-response-status-body">
        <div className="assistant-response-status-head">
          <span className="assistant-response-status-title">{payload.title}</span>
          {payload.active ? (
            <span className="assistant-response-status-dots" aria-hidden="true">
              <span />
              <span />
              <span />
            </span>
          ) : null}
        </div>
        {payload.description ? (
          <div className="assistant-response-status-description">{payload.description}</div>
        ) : null}
        <div className="assistant-response-status-footer">
          <span className="assistant-response-status-provider">{payload.providerLabel}</span>
        </div>
      </div>
      {payload.active ? <div className="assistant-response-status-progress" aria-hidden="true" /> : null}
    </div>
  );
}
