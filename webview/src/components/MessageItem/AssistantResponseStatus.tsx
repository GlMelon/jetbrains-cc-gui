import type { ReactElement } from 'react';
import type { AssistantResponseStatusPayload } from '../../types';
import { SpinLoader } from '../react-bits';

interface AssistantResponseStatusProps {
  payload?: AssistantResponseStatusPayload;
}

/**
 * 工具栏按钮风格的连接状态指示器
 * - 连接中: 圆形转圈 spinner + 文字
 * - 已完成: ✓ + 文字
 * - 失败:    ✕ + 文字
 * 无背景无边框，纯扁平按钮风格
 */
export function AssistantResponseStatus({ payload }: AssistantResponseStatusProps): ReactElement | null {
  if (!payload) return null;

  const isError = payload.phase === 'error';
  const isDone = payload.phase === 'done';
  const isActive = payload.active && !isDone && !isError;

  const phaseClass = payload.phase ? ` phase-${payload.phase}` : '';
  const stateClass = isError ? ' state-error' : isDone ? ' state-done' : ' state-connecting';

  return (
    <div
      className={`assistant-response-status${phaseClass}${stateClass}`}
      role="status"
      aria-live="polite"
    >
      {isActive ? (
        <SpinLoader size={14} variant="ring" duration={0.7} />
      ) : (
        <span className="ars-icon" aria-hidden="true">
          {isError ? '✕' : '✓'}
        </span>
      )}
      <span className="ars-text">{payload.title}</span>
      {payload.description ? (
        <span className="ars-desc">{payload.description}</span>
      ) : null}
    </div>
  );
}
