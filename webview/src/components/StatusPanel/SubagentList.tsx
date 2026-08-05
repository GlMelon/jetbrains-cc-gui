import { sendAction } from '../../bridge/typed';
import { UPSTREAM } from '../../generated/protocol';
import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import type { SubagentHistoryResponse, SubagentInfo } from '../../types';
import SubagentProcessDetails from './SubagentProcessDetails';
import { RobotIcon, CheckCircleIcon, XCircleIcon, CircleIcon, ChevronDownIcon, ClockIcon, LayersIcon } from '../Icons';

interface SubagentListProps {
  subagents: SubagentInfo[];
  histories?: Record<string, SubagentHistoryResponse>;
  currentSessionId?: string | null;
  currentProvider: string;
  isStreaming?: boolean;
}

interface SubagentRowProps {
  subagent: SubagentInfo;
  isExpanded: boolean;
  history: SubagentHistoryResponse | undefined;
  canLoad: boolean;
  onToggle: (id: string) => void;
  t: TFunction;
  animationIndex?: number;
}

// Render status icon based on type
function renderSubagentStatusIcon(status: SubagentInfo['status']) {
  switch (status) {
    case 'running':
      return <RobotIcon size={16} />;
    case 'completed':
      return <CheckCircleIcon size={16} />;
    case 'error':
      return <XCircleIcon size={16} />;
    default:
      return <CircleIcon size={16} />;
  }
}

// Get status badge text
function getStatusBadgeText(status: SubagentInfo['status'], t: TFunction) {
  switch (status) {
    case 'running':
      return t('statusPanel.subagentStatus.running', '运行中');
    case 'completed':
      return t('statusPanel.subagentStatus.completed', '已完成');
    case 'error':
      return t('statusPanel.subagentStatus.error', '错误');
    default:
      return status;
  }
}

// Format duration
function formatDuration(ms?: number): string | null {
  if (typeof ms !== 'number' || !isFinite(ms) || ms < 0) return null;
  if (ms < 1000) return `${ms}ms`;
  const seconds = Math.floor(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  const remainingSeconds = seconds % 60;
  return `${minutes}m ${remainingSeconds}s`;
}

const SubagentRow = memo(({ subagent, isExpanded, history, canLoad, onToggle, t, animationIndex }: SubagentRowProps) => {
  const statusClass = `status-${subagent.status}`;

  const handleClick = useCallback(() => {
    onToggle(subagent.id);
  }, [onToggle, subagent.id]);

  const durationText = formatDuration(subagent.totalDurationMs);

  return (
    <div
      className={`subagent-item-wrapper ${statusClass}`}
      style={{ '--stagger-delay': `${(animationIndex ?? 0) * 50}ms` } as React.CSSProperties}
    >
      <button
        type="button"
        className={`subagent-item ${statusClass}`}
        onClick={handleClick}
      >
        <span className={`subagent-status-icon ${statusClass}`}>
          {renderSubagentStatusIcon(subagent.status)}
        </span>
        <div className="subagent-body">
          <div className="subagent-header">
            <span className="subagent-type">{subagent.type || t('statusPanel.subagentTab')}</span>
            <span className={`subagent-badge ${statusClass}`}>
              {getStatusBadgeText(subagent.status, t)}
            </span>
          </div>
          <span className="subagent-description" title={subagent.prompt}>
            {subagent.description || subagent.prompt?.slice(0, 50)}
          </span>
          <div className="subagent-stats">
            {durationText && (
              <span className="subagent-stat">
                <ClockIcon size={14} />
                {durationText}
              </span>
            )}
            {typeof subagent.totalToolUseCount === 'number' && (
              <span className="subagent-stat">
                <LayersIcon size={14} />
                {subagent.totalToolUseCount} {t('statusPanel.toolCalls', '次调用')}
              </span>
            )}
            {typeof subagent.totalTokens === 'number' && (
              <span className="subagent-stat">
                {subagent.totalTokens >= 1000
                  ? `${(subagent.totalTokens / 1000).toFixed(1)}k tokens`
                  : `${subagent.totalTokens} tokens`}
              </span>
            )}
          </div>
        </div>
        <span className={`subagent-chevron ${isExpanded ? 'expanded' : ''}`}>
          <ChevronDownIcon size={14} />
        </span>
      </button>

      <div className={`subagent-details-wrapper ${isExpanded ? 'expanded' : ''}`}>
        <SubagentProcessDetails
          agentId={subagent.agentId}
          totalDurationMs={subagent.totalDurationMs}
          totalTokens={subagent.totalTokens}
          totalToolUseCount={subagent.totalToolUseCount}
          resultText={subagent.resultText}
          history={history}
          canLoad={canLoad}
        />
      </div>
    </div>
  );
});

SubagentRow.displayName = 'SubagentRow';

const SubagentList = memo(({ subagents, histories = {}, currentSessionId, currentProvider }: SubagentListProps) => {
  const { t } = useTranslation();
  const [expandedId, setExpandedId] = useState<string | null>(null);

  // Keep latest subagents/histories in refs so the polling effect can read fresh
  // values without re-running (and rebuilding the interval) on every change.
  const subagentsRef = useRef(subagents);
  const historiesRef = useRef(histories);
  useEffect(() => { subagentsRef.current = subagents; }, [subagents]);
  useEffect(() => { historiesRef.current = histories; }, [histories]);

  const requestHistory = useCallback((subagent: SubagentInfo) => {
    if (!currentSessionId) return;
    sendAction(UPSTREAM.LOAD_SUBAGENT_SESSION, JSON.stringify({
      sessionId: currentSessionId,
      provider: currentProvider,
      agentId: subagent.agentId,
      agentPath: subagent.agentPath,
      description: subagent.description,
      toolUseId: subagent.id,
    }));
  }, [currentProvider, currentSessionId]);

  // Track the expanded row's status so the polling effect re-runs (and clears
  // its interval) when it transitions out of "running", instead of leaving a
  // no-op interval firing every 2 s until the row is collapsed.
  const expandedStatus = subagents.find((item) => item.id === expandedId)?.status;

  useEffect(() => {
    if (!expandedId) return;
    const subagent = subagentsRef.current.find((item) => item.id === expandedId);
    if (!subagent || !currentSessionId) return;
    if (!historiesRef.current[expandedId]) {
      requestHistory(subagent);
    }
    if (!currentSessionId || subagent.status !== 'running') return;
    const timer = window.setInterval(() => {
      const current = subagentsRef.current.find((item) => item.id === expandedId);
      if (!current || current.status !== 'running') return;
      requestHistory(current);
    }, 2_000);
    return () => window.clearInterval(timer);
  }, [currentSessionId, expandedId, requestHistory, expandedStatus]);

  const historyById = useMemo(() => histories, [histories]);

  const handleToggleRow = useCallback((id: string) => {
    setExpandedId((prev) => (prev === id ? null : id));
  }, []);

  const canLoad = Boolean(currentSessionId);

  if (subagents.length === 0) {
    return <div className="status-panel-empty">{t('statusPanel.noSubagents')}</div>;
  }

  return (
    <div className="subagent-list">
      {subagents.map((subagent, index) => {
        const history = historyById[subagent.id] ?? (subagent.agentId ? historyById[subagent.agentId] : undefined);
        // Index fallback guards against rare cases where the bridge emits a
        // subagent without a stable id; without it React surfaces a duplicate-key
        // warning and may miscompare rows during streaming updates.
        return (
          <SubagentRow
            key={subagent.id ?? `subagent-${index}`}
            subagent={subagent}
            isExpanded={expandedId === subagent.id}
            history={history}
            canLoad={canLoad}
            onToggle={handleToggleRow}
            t={t}
            animationIndex={index}
          />
        );
      })}
    </div>
  );
});

SubagentList.displayName = 'SubagentList';

export default SubagentList;
