import type { ReactElement } from 'react';
import type { AssistantResponseStatusPayload } from '../../types';

interface AssistantResponseStatusProps {
  payload?: AssistantResponseStatusPayload;
}

export function AssistantResponseStatus({ payload }: AssistantResponseStatusProps): ReactElement | null {
  if (!payload) return null;

  return (
    <div
      className={`assistant-response-status phase-${payload.phase} ${payload.active ? 'active' : 'inactive'}`}
      role="status"
      aria-live="polite"
    >
      <div className="assistant-response-status-orb" aria-hidden="true" />
      <div className="assistant-response-status-body">
        <div className="assistant-response-status-meta">{payload.providerLabel}</div>
        <div className="assistant-response-status-title">{payload.title}</div>
        {payload.description ? (
          <div className="assistant-response-status-description">{payload.description}</div>
        ) : null}
      </div>
    </div>
  );
}
