import { type ReactElement, useState, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import type { AssistantResponseStatusPayload } from '../../types';
import { SpinLoader, WaveLoader, ProgressRing, GradientText } from '../react-bits';

interface AssistantResponseStatusProps {
  payload?: AssistantResponseStatusPayload;
}

/**
 * 连接状态指示器(扁平单行风格)
 * - 标题 · 描述 · 计时同行显示(用户要求:Starting runtime / Launching the AI CLI process 同行)
 * - 保持扁平按钮风格,无渐变背景
 *
 * 文案 i18n:按 phase/descriptionKey 查 webview locale(与 webview 语言设置一致)。
 * 后端下发的 title/description 来自 IDE Bundle(跟随 IDE 界面语言,英文 IDE 下恒为
 * 英文),仅作缺 key/未初始化环境的 fallback。
 *
 * 各阶段图标:
 * - waiting类(queued/connecting/understanding): SpinLoader ring
 * - active类(thinking/tooling/responding): WaveLoader 音浪
 * - api_retry: ProgressRing 不确定旋转 + 重试信息(琥珀色)
 * - done: ✓
 * - error: ✕
 */
const WAITING_PHASES = new Set(['queued', 'mcp_syncing', 'connecting', 'understanding', 'api_retry']);

export function AssistantResponseStatus({ payload }: AssistantResponseStatusProps): ReactElement | null {
  const { t } = useTranslation();
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

  // i18n 解析:缺 key(含 react-i18next 未初始化返回 key 本身)时回退后端下发文本
  const resolveText = (
    key: string,
    fallback: string | undefined,
    options?: Record<string, unknown>,
  ): string => {
    if (!fallback) return '';
    const translated = options
      ? t(key, { defaultValue: fallback, ...options })
      : t(key, { defaultValue: fallback });
    return translated === key ? fallback : translated;
  };

  // 标题:按 phase 查(api_retry 复用 understanding 标题,与后端语义一致)
  const titleText = resolveText(
    isApiRetry ? 'chat.responsePhase.understanding.title' : `chat.responsePhase.${payload.phase}.title`,
    payload.title,
  );

  // 描述:apiRetry 带重试计数参数;其余按 descriptionKey(缺省=phase)查
  let descriptionText: string;
  if (payload.descriptionKey === 'apiRetry') {
    const attempt = payload.attempt && payload.attempt > 0 ? String(payload.attempt) : '?';
    const max = payload.maxRetries && payload.maxRetries > 0 ? String(payload.maxRetries) : '?';
    descriptionText = resolveText('chat.responsePhase.apiRetry.description', payload.description, { attempt, max });
  } else {
    descriptionText = resolveText(
      `chat.responsePhase.${payload.descriptionKey ?? payload.phase}.description`,
      payload.description,
    );
  }

  // 标题节点：等待阶段用 GradientText 流光
  const titleNode = isWaitingPhase ? (
    <GradientText animated animationDuration={3} colors={['#7c5cff', '#2dd4bf']}>
      {titleText}
    </GradientText>
  ) : (
    titleText
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
    if (isDone) {
      return resolveText('chat.responsePhase.elapsed', `用时 ${Math.round((payload.elapsedMs || 0) / 1000)}s`, {
        seconds: Math.round((payload.elapsedMs || 0) / 1000),
      });
    }
    if (isError) return resolveText('chat.responsePhase.retryHint', '请重试');
    if (showWaitTimer) return `${waitedSeconds}s`;
    return null;
  };

  const timerText = getTimerText();

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
          {descriptionText && (
            <>
              <span className="ars-sep">·</span>
              <span className="ars-desc">{descriptionText}</span>
            </>
          )}
          {timerText && (
            <>
              <span className="ars-sep">·</span>
              <span className="ars-timer">{timerText}</span>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
