import { type ReactElement, useState, useEffect, useRef } from 'react';
import type { AssistantResponseStatusPayload } from '../../types';
import { SpinLoader, WaveLoader, ProgressRing, GradientText } from '../react-bits';

interface AssistantResponseStatusProps {
  payload?: AssistantResponseStatusPayload;
}

/**
 * 连接状态指示器（扁平风格 + 换行布局）
 * - 标题+计时器同行显示
 * - 额外信息（如重试提示）独立成行
 * - 保持原有扁平按钮风格，无渐变背景
 *
 * 各阶段图标:
 * - waiting类(queued/connecting/understanding): SpinLoader ring
 * - active类(thinking/tooling/responding): WaveLoader 音浪
 * - api_retry: ProgressRing 不确定旋转 + 重试信息行
 * - done: ✓
 * - error: ✕
 */
const WAITING_PHASES = new Set(['queued', 'mcp_syncing', 'connecting', 'understanding', 'api_retry']);

export function AssistantResponseStatus({ payload }: AssistantResponseStatusProps): ReactElement | null {
  const [waitedSeconds, setWaitedSeconds] = useState(0);
  const waitStartRef = useRef<number | null>(null);

  const isApiRetry = payload?.phase === 'api_retry';
  const shouldTimer = payload?.phase === 'understanding' || isApiRetry;
  const isWaitingPhase = WAITING_PHASES.has(payload?.phase ?? '');

  useEffect(() => {
    if (!shouldTimer) {
      waitStartRef.current = null;
      setWaitedSeconds(0);
      return;
    }
    if (waitStartRef.current == null) {
      waitStartRef.current = Date.now();
    }
    setWaitedSeconds(0);
    const id = window.setInterval(() => {
      if (waitStartRef.current != null) {
        setWaitedSeconds(Math.floor((Date.now() - waitStartRef.current) / 1000));
      }
    }, 1000);
    return () => window.clearInterval(id);
  }, [shouldTimer]);

  if (!payload) return null;

  const isError = payload.phase === 'error';
  const isDone = payload.phase === 'done';

  const phaseClass = payload.phase ? ` phase-${payload.phase}` : '';
  const stateClass = isError ? ' state-error' : isDone ? ' state-done' : ' state-connecting';

  // 等待静默期显示计时(>0s 才显示)
  const showWaitTimer = shouldTimer && waitedSeconds > 0;

  // 标题节点：等待阶段用 GradientText 流光
  const titleNode = isWaitingPhase ? (
    <GradientText animated animationDuration={3} colors={['#7c5cff', '#2dd4bf']}>
      {payload.title}
    </GradientText>
  ) : (
    payload.title
  );

  // 图标节点
  const renderIcon = () => {
    if (isDone) {
      return <span className="ars-icon" aria-hidden="true">✓</span>;
    }
    if (isError) {
      return <span className="ars-icon" aria-hidden="true">✕</span>;
    }
    if (isApiRetry) {
      return <ProgressRing size={14} strokeWidth={2} color="var(--color-warning, #f59e0b)" decorative />;
    }
    // connecting/understanding 阶段用 SpinLoader
    if (isWaitingPhase) {
      return <SpinLoader size={14} variant="ring" duration={0.7} color={isApiRetry ? 'var(--color-warning, #f59e0b)' : undefined} />;
    }
    // thinking/tooling/responding 阶段用 WaveLoader
    return <WaveLoader count={5} barWidth={2} height={14} duration={1} />;
  };

  // 计时/用时文本
  const getTimerText = () => {
    if (isDone) return `用时 ${Math.round((payload.elapsedMs || 0) / 1000)}s`;
    if (isError) return '请重试';
    if (showWaitTimer) return `${waitedSeconds}s`;
    return null;
  };

  const timerText = getTimerText();

  // api_retry 额外信息行
  const extraRow = isApiRetry && payload.description ? (
    <div className="ars-extra">
      {payload.description}
    </div>
  ) : null;

  return (
    <div
      className={`assistant-response-status${phaseClass}${stateClass}`}
      role="status"
      aria-live="polite"
    >
      {renderIcon()}
      <div className="ars-body">
        <div className="ars-title-row">
          <span className="ars-text">{titleNode}</span>
          {timerText && (
            <>
              <span className="ars-sep">·</span>
              <span className="ars-timer">{timerText}</span>
            </>
          )}
        </div>
        {extraRow}
      </div>
    </div>
  );
}
