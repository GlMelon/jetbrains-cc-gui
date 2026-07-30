/**
 * MCP Server Settings Component
 * Supports both Claude and Codex modes
 */

import { useState, useRef, useMemo, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import type { McpServer, McpPreset } from '../../types/mcp';
import { sendAction } from '../../bridge/typed';
import { UPSTREAM } from '../../generated/protocol';
import { McpServerDialog } from './McpServerDialog';
import { McpMarketDialog } from './McpMarketDialog';
import { McpPresetDialog } from './McpPresetDialog';
import { McpMarketplaceDialog } from './McpMarketplaceDialog';
import { McpImportDialog } from './McpImportDialog';
import { McpHelpDialog } from './McpHelpDialog';
import { McpConfirmDialog } from './McpConfirmDialog';
import { McpLogDialog } from './McpLogDialog';
import { ToastContainer, type ToastMessage } from '../Toast';
import { copyToClipboard } from '../../utils/copyUtils';

// Types and utility functions
import type { McpSettingsSectionProps, RefreshLog, McpTool } from './types';
import { getCacheKeys, getToolIcon, getServerStatusInfo, isServerEnabled } from './utils';

// Hooks
import { useServerData } from './hooks/useServerData';
import { useServerManagement } from './hooks/useServerManagement';
import { useToolsUpdate } from './hooks/useToolsUpdate';

// Sub-components
import { ServerCard } from './ServerCard';
import { SkeletonList } from '../shared/SkeletonList';
import { BracesIcon, ClipboardIcon, ExtensionsIcon, PlugIcon, RefreshIcon, ServerIcon, codiconToIcon } from '../Icons';

/**
 * MCP Server Settings Component
 */
export function McpSettingsSection({ currentProvider = 'claude' }: McpSettingsSectionProps) {
  const [selectedProvider, setSelectedProvider] = useState<McpProvider>(() => {
    let savedProvider: string | null = null;
    try {
      savedProvider = localStorage.getItem('mcp.selectedProvider');
    } catch {
      // Fall back to the active chat provider when storage is unavailable.
    }
    return resolveInitialMcpProvider(currentProvider, savedProvider);
  });

  const selectProvider = useCallback((provider: McpProvider) => {
    setSelectedProvider(provider);
    try {
      localStorage.setItem('mcp.selectedProvider', provider);
    } catch {
      // The selection remains valid for this settings session.
    }
  }, []);

  return (
    <div className="mcp-settings-shell">
      <div className="mcp-provider-tabs" role="tablist" aria-label="MCP provider">
        <button
          type="button"
          role="tab"
          aria-selected={selectedProvider === 'claude'}
          className={selectedProvider === 'claude' ? 'active' : ''}
          onClick={() => selectProvider('claude')}
        >
          <span className="codicon codicon-hubot" aria-hidden="true" />
          Claude
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={selectedProvider === 'codex'}
          className={selectedProvider === 'codex' ? 'active' : ''}
          onClick={() => selectProvider('codex')}
        >
          <span className="codicon codicon-terminal" aria-hidden="true" />
          Codex
        </button>
      </div>
      <McpProviderPanel key={selectedProvider} currentProvider={selectedProvider} />
    </div>
  );
}

function McpProviderPanel({ currentProvider }: { currentProvider: McpProvider }) {
  const { t } = useTranslation();
  const isCodexMode = currentProvider === 'codex';

  // Generate message type prefix based on provider
  const messagePrefix = useMemo(() => getMcpMessagePrefix(currentProvider), [currentProvider]);

  // Get provider-specific cache keys
  const cacheKeys = useMemo(() => getCacheKeys(isCodexMode ? 'codex' : 'claude'), [isCodexMode]);

  // Tool tooltip popup state
  const [hoveredTool, setHoveredTool] = useState<{ serverId: string; tool: McpTool; position: { x: number; y: number } } | null>(null);
  const tooltipRef = useRef<HTMLDivElement>(null);

  // Dialog state
  const [showServerDialog, setShowServerDialog] = useState(false);
  const [showMarketDialog, setShowMarketDialog] = useState(false);
  const [pendingPresetServer, setPendingPresetServer] = useState<McpServer | null>(null);
  const [showPresetDialog, setShowPresetDialog] = useState(false);
  const [showMarketplaceDialog, setShowMarketplaceDialog] = useState(false);
  const [showImportDialog, setShowImportDialog] = useState(false);
  const [showHelpDialog, setShowHelpDialog] = useState(false);
  const [showConfirmDialog, setShowConfirmDialog] = useState(false);
  const [showLogDialog, setShowLogDialog] = useState(false);
  const [editingServer, setEditingServer] = useState<McpServer | null>(null);
  const [deletingServer, setDeletingServer] = useState<McpServer | null>(null);

  // Toast state management
  const [toasts, setToasts] = useState<ToastMessage[]>([]);

  // Refresh logs state
  const [refreshLogs, setRefreshLogs] = useState<RefreshLog[]>([]);

  // Toast helper functions
  const addToast = useCallback((message: string, type: ToastMessage['type'] = 'info') => {
    const id = `toast-${Date.now()}-${Math.random()}`;
    setToasts((prev) => [...prev, { id, message, type }]);
  }, []);

  const dismissToast = useCallback((id: string) => {
    setToasts((prev) => prev.filter((toast) => toast.id !== id));
  }, []);

  // Log helper functions
  const addLog = useCallback((
    message: string,
    type: RefreshLog['type'] = 'info',
    details?: string,
    serverName?: string,
    requestInfo?: string,
    errorReason?: string
  ) => {
    const id = `log-${Date.now()}-${Math.random()}`;
    const log: RefreshLog = {
      id,
      timestamp: new Date(),
      type,
      message,
      details,
      serverName,
      requestInfo,
      errorReason
    };
    setRefreshLogs((prev) => [...prev, log].slice(-100));
  }, []);

  const clearLogs = useCallback(() => {
    setRefreshLogs([]);
    addLog(t('mcp.logs.cleared'), 'info');
  }, [addLog, t]);

  // Use server data hook
  const {
    servers,
    serverStatus,
    loading,
    statusLoading,
    expandedServers,
    serverTools,
    setServerTools,
    setExpandedServers,
    loadServers,
    loadServerStatus,
    loadServerTools,
    acceptToolsResponse,
    failPendingToolsRequests,
  } = useServerData({
    isCodexMode,
    messagePrefix,
    cacheKeys,
    t,
    onLog: addLog,
  });

  // Use server management hook
  const {
    serverRefreshStates,
    handleRefresh,
    handleRefreshSingleServer,
    handleToggleServer,
  } = useServerManagement({
    isCodexMode,
    messagePrefix,
    cacheKeys,
    setServerTools,
    loadServers,
    loadServerStatus,
    loadServerTools,
    onLog: addLog,
    onToast: addToast,
    t,
  });

  // Use tools list update hook
  useToolsUpdate({
    isCodexMode,
    cacheKeys,
    setServerTools,
    acceptToolsResponse,
    failPendingToolsRequests,
    onLog: addLog,
  });

  // Toggle server expand/collapse
  const toggleExpand = useCallback((serverId: string) => {
    const server = servers.find(s => s.id === serverId);
    const isExpanding = !expandedServers.has(serverId);

    if (isExpanding) {
      setExpandedServers(new Set([serverId]));
      // Save last expanded server ID to cache
      try {
        localStorage.setItem(cacheKeys.LAST_SERVER_ID, serverId);
      } catch (e) {
        // ignore
      }

      // Automatically load tool list when expanded.
      if (server && !serverTools[serverId]) {
        loadServerTools(server, false);
      }
    } else {
      const newExpanded = new Set(expandedServers);
      newExpanded.delete(serverId);
      setExpandedServers(newExpanded);
    }
  }, [servers, expandedServers, serverTools, cacheKeys, setExpandedServers, loadServerTools]);

  // Edit server
  const handleEdit = useCallback((server: McpServer) => {
    setEditingServer(server);
    setShowServerDialog(true);
  }, []);

  // Delete server
  const handleDelete = useCallback((server: McpServer) => {
    setDeletingServer(server);
    setShowConfirmDialog(true);
  }, []);

  // Confirm deletion
  const confirmDelete = useCallback(() => {
    if (deletingServer) {
      sendAction(isCodexMode ? UPSTREAM.DELETE_CODEX_MCP_SERVER : UPSTREAM.DELETE_MCP_SERVER, { id: deletingServer.id });
      addToast(`${t('mcp.deleted')} ${deletingServer.name || deletingServer.id}`, 'success');

      setTimeout(() => {
        loadServers();
      }, 100);
    }
    setShowConfirmDialog(false);
    setDeletingServer(null);
  }, [deletingServer, messagePrefix, addToast, t, loadServers]);

  // Cancel deletion
  const cancelDelete = useCallback(() => {
    setShowConfirmDialog(false);
    setDeletingServer(null);
  }, []);

  // Add server manually
  const handleAddManual = useCallback(() => {
    setEditingServer(null);
    setShowServerDialog(true);
  }, []);

  // Add server from marketplace (Smithery Registry) — OpenCode 无 MCP 后端,入口在下拉菜单隐藏
  const handleAddFromMarket = useCallback(() => {
    setShowMarketDialog(true);
  }, []);

  // 市场选中 server → 预填 McpServerDialog(isPreset 新建模式),用户填 API key/headers 后保存走 ADD
  const handleSelectFromMarket = useCallback((server: McpServer) => {
    setEditingServer(null);
    setPendingPresetServer(server);
    setShowMarketDialog(false);
    setShowServerDialog(true);
  }, []);

  // Save server
  const handleSaveServer = useCallback((server: McpServer) => {
    if (editingServer) {
      if (editingServer.id !== server.id) {
        sendAction(isCodexMode ? UPSTREAM.DELETE_CODEX_MCP_SERVER : UPSTREAM.DELETE_MCP_SERVER, { id: editingServer.id });
        sendAction(isCodexMode ? UPSTREAM.ADD_CODEX_MCP_SERVER : UPSTREAM.ADD_MCP_SERVER, server);
        addToast(`${t('mcp.updated')} ${server.name || server.id}`, 'success');
      } else {
        sendAction(isCodexMode ? UPSTREAM.UPDATE_CODEX_MCP_SERVER : UPSTREAM.UPDATE_MCP_SERVER, server);
        addToast(`${t('mcp.saved')} ${server.name || server.id}`, 'success');
      }
    } else {
      sendAction(isCodexMode ? UPSTREAM.ADD_CODEX_MCP_SERVER : UPSTREAM.ADD_MCP_SERVER, server);
      addToast(`${t('mcp.added')} ${server.name || server.id}`, 'success');
    }

    setTimeout(() => {
      loadServers();
    }, 100);

    setShowServerDialog(false);
    setEditingServer(null);
    setPendingPresetServer(null);
  }, [editingServer, messagePrefix, addToast, t, loadServers]);

  // Select preset
  const handleSelectPreset = useCallback((preset: McpPreset) => {
    const server: McpServer = {
      id: preset.id,
      name: preset.name,
      description: preset.description,
      tags: preset.tags,
      server: { ...preset.server },
      apps: {
        claude: !isCodexMode,
        codex: isCodexMode,
        gemini: false,
      },
      homepage: preset.homepage,
      docs: preset.docs,
      enabled: true,
    };
    sendAction(isCodexMode ? UPSTREAM.ADD_CODEX_MCP_SERVER : UPSTREAM.ADD_MCP_SERVER, server);
    addToast(`${t('mcp.added')} ${preset.name}`, 'success');

    setTimeout(() => {
      loadServers();
    }, 100);

    setShowPresetDialog(false);
  }, [isCodexMode, messagePrefix, addToast, t, loadServers]);

  // Handle import servers from external config (e.g. Copilot MCP config)
  const handleImportServers = useCallback((servers: McpServer[]) => {
    for (const server of servers) {
      sendAction(isCodexMode ? UPSTREAM.ADD_CODEX_MCP_SERVER : UPSTREAM.ADD_MCP_SERVER, server);
    }
    addToast(`${t('mcp.imported')} ${servers.length} ${t('mcp.servers')}`, 'success');
    setTimeout(() => {
      loadServers();
    }, 100);
    setShowImportDialog(false);
  }, [isCodexMode, addToast, t, loadServers]);

  // Copy URL
  const handleCopyUrl = useCallback(async (url: string) => {
    const success = await copyToClipboard(url);
    if (success) {
      addToast(t('mcp.linkCopied'), 'success');
    } else {
      addToast(t('mcp.copyFailed'), 'error');
    }
  }, [addToast, t]);

  // Copy server config (redact sensitive values in env/headers)
  const handleCopyConfig = useCallback(async (server: McpServer) => {
    const { env, headers, ...safeFields } = server.server;
    const serverConfig: Record<string, unknown> = { ...safeFields };
    if (env) {
      serverConfig.env = Object.fromEntries(
        Object.keys(env).map(k => [k, '***'])
      );
    }
    if (headers) {
      serverConfig.headers = Object.fromEntries(
        Object.keys(headers).map(k => [k, '***'])
      );
    }
    const config = {
      mcpServers: {
        [server.id]: serverConfig,
      },
    };
    const jsonContent = JSON.stringify(config, null, 2);
    const success = await copyToClipboard(jsonContent);
    if (success) {
      addToast(t('mcp.configCopied'), 'success');
    } else {
      addToast(t('mcp.copyFailed'), 'error');
    }
  }, [addToast, t]);

  // Tool hover handler
  const handleToolHover = useCallback((tool: McpTool | null, position?: { x: number; y: number }, serverId?: string) => {
    if (tool && position && serverId) {
      setHoveredTool({ serverId, tool, position });
    } else {
      setHoveredTool(null);
    }
  }, []);

  // 统计条:connected / error / disabled / total
  const stats = useMemo(() => {
    let connected = 0, error = 0, disabled = 0;
    servers.forEach(s => {
      if (!isServerEnabled(s, isCodexMode)) { disabled++; return; }
      const st = getServerStatusInfo(s, serverStatus)?.status;
      if (st === 'connected') connected++;
      else if (st === 'failed' || st === 'needs-auth') error++;
    });
    return { connected, error, disabled, total: servers.length };
  }, [servers, serverStatus, isCodexMode]);

  return (
    <div className="mcp-settings-section">
      {/* Header(设计稿 panel-header) */}
      <div className="panel-header">
        <div className="panel-title">
          <span className="ico-badge"><PlugIcon size={16} /></span>
          <span className="title-text">
            {t('mcp.title')}
            <span className="subtitle">{t('mcp.subtitle')}</span>
          </span>
        </div>
        <div className="header-tools">
          {/* 帮助:什么是 MCP? */}
          <button
            className="icon-btn"
            onClick={() => setShowHelpDialog(true)}
            title={t('mcp.whatIsMcp')}
            aria-label={t('mcp.whatIsMcp')}
          >
            ?
          </button>
          {/* 日志(剪贴板图标 + 未读徽章) */}
          <button
            className="icon-btn"
            onClick={() => setShowLogDialog(true)}
            title={t('mcp.logs.title')}
            aria-label={t('mcp.logs.title')}
          >
            <ClipboardIcon size={16} />
            {refreshLogs.length > 0 && (
              <span className="badge">{refreshLogs.length}</span>
            )}
          </button>
          {/* 刷新状态 */}
          <button
            className="icon-btn"
            onClick={handleRefresh}
            disabled={loading || statusLoading}
            title={t('mcp.refreshStatus')}
            aria-label={t('mcp.refreshStatus')}
          >
            <RefreshIcon size={16} className={loading || statusLoading ? 'spinning' : ''} />
          </button>
          {/* 手动配置(幽灵按钮) */}
          <button
            className="btn-ghost"
            onClick={handleAddManual}
            title={t('mcp.manualConfig')}
          >
            <BracesIcon size={16} />
            {t('mcp.manualConfig')}
          </button>
          {/* 从市场获取(主色按钮,OpenCode 无 MCP 后端时隐藏) */}
          {currentProvider !== 'opencode' && (
            <button
              className="market-btn"
              onClick={handleAddFromMarket}
              title={t('mcp.addFromMarket')}
            >
              <ExtensionsIcon size={16} />
              {t('mcp.addFromMarket')}
            </button>
          )}
        </div>
      </div>

      {/* 统计条 */}
      {servers.length > 0 && (
        <div className="stats-bar">
          <span className="stat-pill"><span className="dot ok" />{t('mcp.statusConnected')} <b>{stats.connected}</b></span>
          {stats.error > 0 && (
            <span className="stat-pill"><span className="dot err" />{t('mcp.statsError')} <b>{stats.error}</b></span>
          )}
          {stats.disabled > 0 && (
            <span className="stat-pill"><span className="dot off" />{t('mcp.statsDisabled')} <b>{stats.disabled}</b></span>
          )}
          <span className="stat-pill">{t('mcp.statsTotal', { count: stats.total })}</span>
        </div>
      )}

      {/* Vertical layout: server list | refresh logs */}
      <div className="mcp-panels-container">
        {/* Top panel: server list */}
        <div className="mcp-server-panel">
          {!loading || servers.length > 0 ? (
            <div className="server-list">
              {servers.map(server => (
                <ServerCard
                  key={server.id}
                  server={server}
                  isExpanded={expandedServers.has(server.id)}
                  isCodexMode={isCodexMode}
                  serverStatus={serverStatus}
                  refreshState={serverRefreshStates[server.id]}
                  toolsInfo={serverTools[server.id]}
                  t={t}
                  onToggleExpand={() => toggleExpand(server.id)}
                  onToggleServer={(enabled) => handleToggleServer(server, enabled)}
                  onEdit={() => handleEdit(server)}
                  onDelete={() => handleDelete(server)}
                  onCopy={() => handleCopyConfig(server)}
                  onRefresh={() => handleRefreshSingleServer(server)}
                  onLoadTools={(forceRefresh) => loadServerTools(server, forceRefresh)}
                  onCopyUrl={handleCopyUrl}
                  onToolHover={(tool, position) => handleToolHover(tool, position, server.id)}
                />
              ))}

              {/* Empty state */}
              {servers.length === 0 && !loading && (
                <div className="empty-state">
                  <ServerIcon size={16} />
                  <p>{t('mcp.noServers')}</p>
                  <p className="hint">{t('mcp.addServerHint')}</p>
                </div>
              )}
            </div>
          ) : null}

          {/* Loading state (H5 骨架屏)：首次加载 server 列表时的占位卡片。
              loading 由 useServerData 的后端 MCP_SERVER_LIST 事件驱动(非前端自行推导)，
              满足 H5「真实请求状态驱动」约束；脉冲动画的 reduced-motion 降级由 base.less 全局收口。 */}
          {loading && servers.length === 0 && (
            <SkeletonList label={t('mcp.loading')} />
          )}
        </div>
      </div>

      {/* Dialogs */}
      {showServerDialog && (
        <McpServerDialog
          server={editingServer ?? pendingPresetServer}
          isPreset={!!pendingPresetServer}
          existingIds={servers.map(s => s.id)}
          currentProvider={currentProvider}
          onClose={() => {
            setShowServerDialog(false);
            setEditingServer(null);
            setPendingPresetServer(null);
          }}
          onSave={handleSaveServer}
        />
      )}

      {showMarketDialog && (
        <McpMarketDialog
          isCodexMode={isCodexMode}
          onClose={() => setShowMarketDialog(false)}
          onSelect={handleSelectFromMarket}
        />
      )}

      {showPresetDialog && (
        <McpPresetDialog
          onClose={() => setShowPresetDialog(false)}
          onSelect={handleSelectPreset}
        />
      )}

      {showMarketplaceDialog && (
        <McpMarketplaceDialog
          currentProvider={currentProvider}
          existingIds={servers.map(s => s.id)}
          onClose={() => setShowMarketplaceDialog(false)}
          onInstalled={loadServers}
        />
      )}

      {showImportDialog && (
        <McpImportDialog
          currentProvider={currentProvider}
          existingIds={servers.map(s => s.id)}
          onClose={() => setShowImportDialog(false)}
          onImport={handleImportServers}
        />
      )}

      {showHelpDialog && (
        <McpHelpDialog onClose={() => setShowHelpDialog(false)} />
      )}

      {showConfirmDialog && deletingServer && (
        <McpConfirmDialog
          title={t('mcp.deleteTitle')}
          message={t('mcp.deleteMessage', { name: deletingServer.name || deletingServer.id })}
          confirmText={t('mcp.deleteConfirm')}
          cancelText={t('mcp.cancel')}
          onConfirm={confirmDelete}
          onCancel={cancelDelete}
        />
      )}

      {showLogDialog && (
        <McpLogDialog
          logs={refreshLogs.map(log => ({
            id: log.id,
            timestamp: log.timestamp,
            serverName: log.serverName || '',
            level: log.type === 'warning' ? 'warn' : log.type,
            message: log.message
          }))}
          onClose={() => setShowLogDialog(false)}
          onClear={clearLogs}
        />
      )}

      {/* Toast notifications */}
      <ToastContainer messages={toasts} onDismiss={dismissToast} />

      {/* Tool tooltip popup */}
      {hoveredTool && (() => {
        const tooltipStyle: React.CSSProperties = {
          left: `${Math.min(hoveredTool.position.x, window.innerWidth - 420)}px`,
          top: `${hoveredTool.position.y}px`,
        };
        return (
        <div
          ref={tooltipRef}
          className="mcp-tool-tooltip"
          style={tooltipStyle}
        >
          <div className="tooltip-header">
            <span className="tooltip-icon">
              {codiconToIcon(getToolIcon(hoveredTool.tool.name), 16, { className: 'tool-icon' })}
            </span>
            <span className="tooltip-name">{hoveredTool.tool.name}</span>
          </div>
          {hoveredTool.tool.description && (
            <div className="tooltip-description">{hoveredTool.tool.description}</div>
          )}
          {hoveredTool.tool.inputSchema && (
            <div className="tooltip-params">
              {renderInputSchema(hoveredTool.tool.inputSchema, t)}
            </div>
          )}
        </div>
        );
      })()}
    </div>
  );
}

/**
 * Render inputSchema as a parameter list
 */
function renderInputSchema(
  schema: Record<string, unknown> | undefined,
  t: (key: string) => string
): React.ReactElement {
  if (!schema) {
    return <div className="tooltip-no-params">{t('mcp.noParams')}</div>;
  }

  const properties = schema.properties as Record<string, { type?: string; description?: string }> | undefined;
  const required = (schema.required as string[]) || [];

  if (!properties || Object.keys(properties).length === 0) {
    return <div className="tooltip-no-params">{t('mcp.noParams')}</div>;
  }

  return (
    <>
      {Object.entries(properties).map(([paramName, paramDef]) => {
        const isRequired = required.includes(paramName);
        const paramType = paramDef.type || 'unknown';
        const paramDesc = paramDef.description;

        return (
          <div key={paramName} className="tooltip-param">
            <div className="tooltip-param-name">{paramName}</div>
            {paramDesc && <div className="tooltip-param-desc">{paramDesc}</div>}
            <div className="tooltip-param-meta">
              <span className="tooltip-param-type">{paramType}</span>
              <span className={isRequired ? 'tooltip-param-required' : 'tooltip-param-optional'}>
                {isRequired ? t('mcp.required') : t('mcp.optional')}
              </span>
            </div>
          </div>
        );
      })}
    </>
  );
}
