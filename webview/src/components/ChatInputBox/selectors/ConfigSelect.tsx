import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { CheckIcon, ChevronRightIcon, CommandLineIcon, InfoIcon, LightbulbIcon, PlusIcon, RobotIcon, ServerProcessIcon, SettingsIcon, SyncIcon } from '../../Icons';;
import { useTranslation } from 'react-i18next';
import { Switch } from '../../shared/Switch';
import { agentProvider, CREATE_NEW_AGENT_ID, EMPTY_STATE_ID, type AgentItem } from '../providers/agentProvider';
import type { SelectedAgent } from '../types';
import { RuntimeProviderSelect } from './RuntimeProviderSelect';
import { NodeProcessSelect } from './NodeProcessSelect';
import {
  fetchNodeProcesses,
  subscribeNodeProcesses,
  type NodeProcessSnapshot,
} from '../../../utils/nodeProcessCapabilities';
import { useDropdownPosition } from '../../../hooks/useDropdownPosition';
import { UnifiedLoader } from '../../UnifiedLoader';

interface ConfigSelectProps {
  showThinkingEnabled?: boolean;
  onShowThinkingEnabledChange?: (enabled: boolean) => void;
  streamingEnabled?: boolean;
  onStreamingEnabledChange?: (enabled: boolean) => void;
  selectedAgent?: SelectedAgent | null;
  onAgentSelect?: (agent: SelectedAgent) => void;
  onOpenAgentSettings?: () => void;
  currentProvider?: string;
}

const WRAPPER_STYLE: React.CSSProperties = {
  position: 'relative',
  display: 'inline-block',
};

const SUBMENU_BASE_STYLE: React.CSSProperties = {
  minWidth: 0,
  maxWidth: '360px',
  maxHeight: '300px',
  overflowY: 'auto',
};

const LOADING_OPTION_STYLE: React.CSSProperties = { cursor: 'default' };

const AGENT_BODY_STYLE: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: '2px',
  minWidth: 0,
  flex: 1,
};

const AGENT_NAME_STYLE: React.CSSProperties = {
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
};

const AGENT_DESC_STYLE: React.CSSProperties = {
  fontStyle: 'normal',
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
};

const AGENT_DESC_PLAIN_STYLE: React.CSSProperties = {
  fontStyle: 'normal',
};

const DROPDOWN_STYLE: React.CSSProperties = {
  position: 'absolute',
  bottom: '100%',
  marginBottom: '4px',
  zIndex: 10000,
  minWidth: '200px',
  maxWidth: 'calc(100vw - 16px)',
  overflow: 'visible',
};

const SELECTOR_OPTION_RELATIVE_STYLE: React.CSSProperties = { position: 'relative', overflow: 'visible' };

const ITEM_INFO_STYLE: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: '2px',
};

const ARROW_CONTAINER_STYLE: React.CSSProperties = {
  marginLeft: 'auto',
  display: 'flex',
  alignItems: 'center',
  alignSelf: 'stretch',
  paddingLeft: '12px',
  cursor: 'pointer',
};

const ARROW_ICON_STYLE: React.CSSProperties = { fontSize: '12px' };

const SWITCH_OPTION_STYLE: React.CSSProperties = {
  justifyContent: 'space-between',
  cursor: 'pointer',
};

const SWITCH_LABEL_STYLE: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
};

const FAINT_DIVIDER_STYLE: React.CSSProperties = {
  height: 1,
  background: 'var(--dropdown-border)',
  margin: '4px 0',
  opacity: 0.5,
};

const TOAST_STYLE: React.CSSProperties = { zIndex: 20000 };

function getAgentOptionStyle(isInfo: boolean): React.CSSProperties {
  return {
    alignItems: 'flex-start',
    cursor: isInfo ? 'default' : 'pointer',
  };
}

/**
 * ConfigSelect - Configuration menu (Agent, Streaming, Thinking)
 * Provider selection has been moved to a standalone ProviderSelect icon button.
 */
export const ConfigSelect = ({
  showThinkingEnabled,
  onShowThinkingEnabledChange,
  streamingEnabled,
  onStreamingEnabledChange,
  selectedAgent,
  onAgentSelect,
  onOpenAgentSettings,
  currentProvider = 'claude',
}: ConfigSelectProps) => {
  const { t } = useTranslation();
  // 流式/思考区开关本质是 provider/调用模式无关的纯显示开关:流式 off → 后端缓冲到
  // turn 边界一次性推送;思考区 off → 不推送 thinking 类型数据(模型照常思考)。
  // OpenCode CLI 现带 --thinking flag(见 OpenCodeCliSession.buildRunCommand),opencode run
  // --format json 会输出 type:"reasoning" 文本事件,parser EVENT_REASONING 分支据此推送思考区,
  // 故思考开关对所有 provider/调用模式均可用(对称 SDK 路径),无需灰显。
  const [isOpen, setIsOpen] = useState(false);
  const [activeSubmenu, setActiveSubmenu] = useState<'none' | 'agent' | 'runtimeProvider' | 'nodeProcesses'>('none');
  const [agentItems, setAgentItems] = useState<AgentItem[]>([]);
  const [agentsLoading, setAgentsLoading] = useState(false);
  const [toastMessage, setToastMessage] = useState('');
  const [showToast, setShowToast] = useState(false);
  const [nodeProcessTotals, setNodeProcessTotals] = useState<{ all: number; orphan: number }>({ all: 0, orphan: 0 });

  const buttonRef = useRef<HTMLButtonElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const agentSubmenuRef = useRef<HTMLDivElement>(null);
  const agentTriggerRef = useRef<HTMLDivElement>(null);
  const runtimeProviderTriggerRef = useRef<HTMLDivElement>(null);
  const agentAbortControllerRef = useRef<AbortController | null>(null);
  const toastTimerRef = useRef<number | undefined>(undefined);

  const { positionedStyle: mainPositionedStyle, recalculate: mainRecalculate } = useDropdownPosition({
    buttonRef,
    dropdownRef,
    isOpen,
  });
  const { positionedStyle: agentSubmenuPositionedStyle, maxHeight: agentSubmenuMaxHeight, maxWidth: agentSubmenuMaxWidth, recalculate: agentSubmenuRecalculate } = useDropdownPosition({
    buttonRef: agentTriggerRef,
    dropdownRef: agentSubmenuRef,
    submenu: true,
    minWidth: 260,
    maxWidth: 360,
    submenuMaxHeight: 300,
    submenuBottomClearance: 96,
  });

  const handleToggle = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    const nextOpen = !isOpen;
    setIsOpen(nextOpen);
    if (nextOpen) {
      setActiveSubmenu('none');
      mainRecalculate();
    }
  }, [isOpen, mainRecalculate]);

  const loadAgents = useCallback(async () => {
    if (agentAbortControllerRef.current) {
      agentAbortControllerRef.current.abort();
    }

    const controller = new AbortController();
    agentAbortControllerRef.current = controller;

    setAgentsLoading(true);
    try {
      const list = await agentProvider('', controller.signal);
      if (controller.signal.aborted) return;
      setAgentItems(list);
    } catch (error) {
      if ((error as Error).name === 'AbortError') return;
      setAgentItems([{
        id: EMPTY_STATE_ID,
        name: t('settings.agent.loadFailed'),
        prompt: '',
      }, {
        id: CREATE_NEW_AGENT_ID,
        name: t('settings.agent.createAgent'),
        prompt: '',
      }]);
    } finally {
      if (!controller.signal.aborted) {
        setAgentsLoading(false);
      }
    }
  }, []);

  const showProviderToast = useCallback((providerName: string) => {
    if (toastTimerRef.current !== undefined) {
      window.clearTimeout(toastTimerRef.current);
    }
    setToastMessage(t('config.runtimeProvider.switched', { provider: providerName }));
    setShowToast(true);
    toastTimerRef.current = window.setTimeout(() => {
      setShowToast(false);
    }, 1500);
  }, [t]);

  const showGenericToast = useCallback((message: string) => {
    if (!message) return;
    if (toastTimerRef.current !== undefined) {
      window.clearTimeout(toastTimerRef.current);
    }
    setToastMessage(message);
    setShowToast(true);
    toastTimerRef.current = window.setTimeout(() => {
      setShowToast(false);
    }, 1800);
  }, []);

  // Subscribe to node process snapshots so the badge counter stays in sync
  // with whatever the panel (or other consumers) see.
  useEffect(() => {
    const unsubscribe = subscribeNodeProcesses((snapshot: NodeProcessSnapshot) => {
      setNodeProcessTotals({ all: snapshot.totals.all, orphan: snapshot.totals.orphan });
    });
    return unsubscribe;
  }, []);

  // Refresh node process counts whenever the main menu opens, so the badge
  // is accurate before the user even hovers over the submenu.
  useEffect(() => {
    if (isOpen) {
      fetchNodeProcesses();
    }
  }, [isOpen]);

  useEffect(() => {
    if (!isOpen) return;

    const handleClickOutside = (e: MouseEvent) => {
      // 当确认对话框打开时跳过外部点击处理：点击确认/取消按钮会先触发 mousedown，
      // 若此时关闭 ConfigSelect 会让确认框随之卸载，导致 onConfirm 永不执行（issue #1522）
      if (document.querySelector('.confirm-dialog-overlay')) {
        return;
      }
      if (
        dropdownRef.current &&
        !dropdownRef.current.contains(e.target as Node) &&
        buttonRef.current &&
        !buttonRef.current.contains(e.target as Node)
      ) {
        setIsOpen(false);
        setActiveSubmenu('none');
      }
    };

    const timer = setTimeout(() => {
      document.addEventListener('mousedown', handleClickOutside);
    }, 0);

    return () => {
      clearTimeout(timer);
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, [isOpen]);

  useEffect(() => {
    if (activeSubmenu !== 'agent') return;
    loadAgents();
  }, [activeSubmenu, loadAgents]);

  useLayoutEffect(() => {
    if (isOpen) {
      mainRecalculate();
    }
  }, [isOpen, mainRecalculate]);

  useLayoutEffect(() => {
    if (activeSubmenu !== 'agent') return;
    agentSubmenuRecalculate();
  }, [activeSubmenu, agentItems.length, agentsLoading, agentSubmenuRecalculate]);

  useEffect(() => {
    return () => {
      if (agentAbortControllerRef.current) {
        agentAbortControllerRef.current.abort();
      }
      if (toastTimerRef.current !== undefined) {
        window.clearTimeout(toastTimerRef.current);
      }
    };
  }, []);

  const renderAgentSubmenu = () => {
    const submenuMaxHeightPx = agentSubmenuMaxHeight ? `${Math.min(300, agentSubmenuMaxHeight)}px` : '300px';
    return (
    <div
      ref={agentSubmenuRef}
      className="selector-dropdown"
      style={{
        ...SUBMENU_BASE_STYLE,
        maxWidth: agentSubmenuMaxWidth ?? 360,
        ...agentSubmenuPositionedStyle,
        maxHeight: submenuMaxHeightPx,
      }}
      onMouseEnter={(e) => {
        e.stopPropagation();
        setActiveSubmenu('agent');
      }}
    >
      {agentsLoading ? (
        <div className="selector-option" style={LOADING_OPTION_STYLE}>
          <UnifiedLoader type="pulse" size={14} />
          <span>{t('chat.loadingDropdown')}</span>
        </div>
      ) : (
        agentItems.map((agent) => {
          const isInfo = agent.id === EMPTY_STATE_ID;
          const isCreate = agent.id === CREATE_NEW_AGENT_ID;
          const isSelected = !!selectedAgent && selectedAgent.id === agent.id;

          return (
            <div
              key={agent.id}
              className={`selector-option ${isSelected ? 'selected' : ''} ${isInfo ? 'disabled' : ''}`}
              style={getAgentOptionStyle(isInfo)}
              onClick={(e) => {
                e.stopPropagation();
                if (isInfo) return;

                if (isCreate) {
                  setIsOpen(false);
                  setActiveSubmenu('none');
                  onOpenAgentSettings?.();
                  return;
                }

                onAgentSelect?.({ id: agent.id, name: agent.name, prompt: agent.prompt });
                setIsOpen(false);
                setActiveSubmenu('none');
              }}
            >
              {isCreate ? <PlusIcon size={16} /> : isInfo ? <InfoIcon size={16} /> : <RobotIcon size={16} />}
              <div style={AGENT_BODY_STYLE}>
                <span style={AGENT_NAME_STYLE}>{agent.name}</span>
                {agent.prompt ? (
                  <span className="model-description" style={AGENT_DESC_STYLE}>
                    {agent.prompt.length > 60 ? agent.prompt.substring(0, 60) + '...' : agent.prompt}
                  </span>
                ) : isCreate ? (
                  <span className="model-description" style={AGENT_DESC_PLAIN_STYLE}>{t('settings.agent.createAgentHint')}</span>
                ) : null}
              </div>
              {isSelected && <CheckIcon size={16} className="check-mark" />}
            </div>
          );
        })
      )}
    </div>
    );
  };

  return (
    <div style={WRAPPER_STYLE}>
      <button
        ref={buttonRef}
        className="selector-button"
        onClick={handleToggle}
        title={t('settings.configure', 'Configure')}
      >
        <SettingsIcon size={16} />
      </button>

      {isOpen && (
        <div
          ref={dropdownRef}
          className="selector-dropdown"
          style={{ ...DROPDOWN_STYLE, ...mainPositionedStyle }}
        >
          {/* Agent Item */}
          <div
            ref={agentTriggerRef}
            className="selector-option"
            data-testid="config-option-agent"
            onMouseEnter={() => {
              setActiveSubmenu('agent');
              agentSubmenuRecalculate();
            }}
            onMouseLeave={() => setActiveSubmenu('none')}
            style={SELECTOR_OPTION_RELATIVE_STYLE}
          >
            <RobotIcon size={16} />
            <div style={ITEM_INFO_STYLE}>
              <span>{t('settings.agent.title')}</span>
              {selectedAgent?.name ? (
                <span className="model-description" style={AGENT_DESC_PLAIN_STYLE}>
                  {selectedAgent.name}
                </span>
              ) : null}
            </div>
            <div style={ARROW_CONTAINER_STYLE}>
              <ChevronRightIcon size={16} style={ARROW_ICON_STYLE} />
            </div>

            {activeSubmenu === 'agent' && renderAgentSubmenu()}
          </div>

          <div className="selector-divider" />

          {/* Runtime Provider Item */}
          <div
            ref={runtimeProviderTriggerRef}
            className="selector-option"
            data-testid="config-option-runtime-provider"
            onMouseEnter={() => setActiveSubmenu('runtimeProvider')}
            onMouseLeave={() => setActiveSubmenu('none')}
            style={SELECTOR_OPTION_RELATIVE_STYLE}
          >
            <CommandLineIcon size={14} />
            <div style={ITEM_INFO_STYLE}>
              <span>{t('config.runtimeProvider.title')}</span>
            </div>
            <div style={ARROW_CONTAINER_STYLE}>
              <ChevronRightIcon size={16} style={ARROW_ICON_STYLE} />
            </div>

            {activeSubmenu === 'runtimeProvider' && (
              <RuntimeProviderSelect
                currentProvider={currentProvider}
                embedded
                triggerRef={runtimeProviderTriggerRef}
                onProviderSwitched={showProviderToast}
                onClose={() => {
                  setIsOpen(false);
                  setActiveSubmenu('none');
                }}
              />
            )}
          </div>

          <div className="selector-divider" />

          {/* Node Process Management Item */}
          <div
            className="selector-option"
            data-testid="config-option-node-processes"
            onMouseEnter={() => setActiveSubmenu('nodeProcesses')}
            onMouseLeave={() => setActiveSubmenu('none')}
            style={SELECTOR_OPTION_RELATIVE_STYLE}
          >
            <ServerProcessIcon size={16} />
            <div style={ITEM_INFO_STYLE}>
              <span>{t('config.nodeProcesses.title', { defaultValue: 'Node 进程管理' })}</span>
              {nodeProcessTotals.all > 0 ? (
                <span className="model-description" style={AGENT_DESC_PLAIN_STYLE}>
                  {nodeProcessTotals.orphan > 0
                    ? t('config.nodeProcesses.badgeWithOrphan', {
                        total: nodeProcessTotals.all,
                        orphan: nodeProcessTotals.orphan,
                        defaultValue: '{{total}} 个 · {{orphan}} 孤立 ⚠',
                      })
                    : t('config.nodeProcesses.badge', {
                        total: nodeProcessTotals.all,
                        defaultValue: '{{total}} 个进程',
                      })}
                </span>
              ) : null}
            </div>
            <div style={ARROW_CONTAINER_STYLE}>
              <ChevronRightIcon size={16} style={ARROW_ICON_STYLE} />
            </div>

            {activeSubmenu === 'nodeProcesses' && (
              <NodeProcessSelect
                embedded
                onToast={showGenericToast}
                onClose={() => {
                  setIsOpen(false);
                  setActiveSubmenu('none');
                }}
              />
            )}
          </div>

          {/* Divider */}
          <div className="selector-divider" />

          {/* Streaming Switch Item - 流式输出开关(跨所有 provider/调用模式) */}
          <div
            className="selector-option"
            onClick={(e) => {
              e.stopPropagation();
              onStreamingEnabledChange?.(!streamingEnabled);
            }}
            onMouseEnter={() => setActiveSubmenu('none')}
            style={SWITCH_OPTION_STYLE}
          >
            <div style={SWITCH_LABEL_STYLE}>
              <SyncIcon size={16} />
              <span>{t('settings.basic.streaming.label')}</span>
            </div>
            <Switch
              size="small"
              checked={streamingEnabled ?? true}
              onClick={(checked, e) => {
                e.stopPropagation();
                onStreamingEnabledChange?.(checked);
              }}
            />
          </div>

          {/* Divider */}
          <div style={FAINT_DIVIDER_STYLE} />

          {/* Show-thinking Switch Item - 显示思考区开关。
              所有 provider/调用模式均可生效:Claude/Codex/OpenCode CLI 均推送 reasoning,
              OpenCode CLI 由 --thinking flag 驱动(见 OpenCodeCliSession.buildRunCommand)。 */}
          <div
            className="selector-option"
            onClick={(e) => {
              e.stopPropagation();
              onShowThinkingEnabledChange?.(!showThinkingEnabled);
            }}
            onMouseEnter={() => setActiveSubmenu('none')}
            style={SWITCH_OPTION_STYLE}
          >
            <div style={SWITCH_LABEL_STYLE}>
              <LightbulbIcon size={16} />
              <span>{t('common.thinking')}</span>
            </div>
            <Switch
              size="small"
              checked={showThinkingEnabled ?? true}
              onClick={(checked, e) => {
                e.stopPropagation();
                onShowThinkingEnabledChange?.(checked);
              }}
            />
          </div>
        </div>
      )}

      {showToast && createPortal(
        <div className="selector-toast" style={TOAST_STYLE}>
          {toastMessage}
        </div>,
        document.body
      )}
    </div>
  );
};
