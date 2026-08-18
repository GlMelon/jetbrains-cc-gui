import { type ReactElement, useState, useEffect, useRef } from 'react';
import type { AssistantResponseStatusPayload } from '../../types';
import { SpinLoader, GradientText } from '../react-bits';

interface AssistantResponseStatusProps {
  payload?: AssistantResponseStatusPayload;
}

/**
 * 工具栏按钮风格的连接状态指示器
 * - 连接中: 圆形转圈 spinner + 文字
 * - 已完成: ✓ + 文字
 * - 失败:    ✕ + 文字
 * 无背景无边框,纯扁平按钮风格
 *
 * 启动序列各阶段(QUEUED→MCP_SYNCING→CONNECTING→UNDERSTANDING)统一动效:
 * SpinLoader 转圈 + title 用 GradientText 流动渐变暗示仍在等待(react-bits 既有动效),
 * 避免"启动运行时"与"等待模型响应"两段动效不一致。
 *
 * UNDERSTANDING(等待模型响应)阶段额外:init 后 7~10s 静默期,description 追加
 * "· Xs"计时缓解焦虑。waitedSeconds=0(fake timers 不推进时)不显示计时,不影响测试断言。
 */
const WAITING_PHASES = new Set(['queued', 'mcp_syncing', 'connecting', 'understanding']);

export function AssistantResponseStatus({ payload }: AssistantResponseStatusProps): ReactElement | null {
  const [waitedSeconds, setWaitedSeconds] = useState(0);
  const waitStartRef = useRef<number | null>(null);

  const isUnderstanding = payload?.phase === 'understanding';
  const isWaitingPhase = WAITING_PHASES.has(payload?.phase ?? '');

  useEffect(() => {
    if (!isUnderstanding) {
      waitStartRef.current = null;
      setWaitedSeconds(0);
      return;
    }
    waitStartRef.current = Date.now();
    setWaitedSeconds(0);
    const id = window.setInterval(() => {
      if (waitStartRef.current != null) {
        setWaitedSeconds(Math.floor((Date.now() - waitStartRef.current) / 1000));
      }
    }, 1000);
    return () => window.clearInterval(id);
  }, [isUnderstanding]);

  if (!payload) return null;

  const isError = payload.phase === 'error';
  const isDone = payload.phase === 'done';
  const isActive = payload.active && !isDone && !isError;

  const phaseClass = payload.phase ? ` phase-${payload.phase}` : '';
  const stateClass = isError ? ' state-error' : isDone ? ' state-done' : ' state-connecting';

  // UNDERSTANDING 静默期显示计时(>0s 才显示,避免首帧闪烁)
  const showWaitTimer = isUnderstanding && waitedSeconds > 0;
  const titleNode = isWaitingPhase ? (
    <GradientText animated animationDuration={3} colors={['#7c5cff', '#2dd4bf']}>
      {payload.title}
    </GradientText>
  ) : (
    payload.title
  );

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
      <span className="ars-text">{titleNode}</span>
      {payload.description ? (
        <span className="ars-desc">
          {payload.description}
          {showWaitTimer ? ` · ${waitedSeconds}s` : null}
        </span>
      ) : showWaitTimer ? (
        <span className="ars-desc">{`${waitedSeconds}s`}</span>
      ) : null}
    </div>
  );
}
