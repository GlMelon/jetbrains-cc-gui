/**
 * OpenCode MCP Server 面板(与 Claude/Codex 的 {@link McpProviderPanel} 对齐的全功能版本)。
 *
 * <p>与 McpProviderPanel 的差异:OpenCode 的 MCP server 在插件 channel 层无
 * getMcpServerStatus/列工具命令,故不复用 useServerData 的 isCodexMode 二元结构与 mcp_/codex_
 * 前缀通道。本面板用独立的 OPENCODE_MCP_* action:
 * <ul>
 *   <li>server 列表:后端读 {@code ~/.config/opencode/opencode.json} 的 {@code mcp} 字段(global 层),
 *       适配成 McpServer 嵌套形状后经 OPENCODE_MCP_SERVER_LIST 下行。</li>
 *   <li>连接状态:后端取 MCP Gateway 聚合 statusJson,过滤 sourceProvider=="opencode" 并把 gateway
 *       state(READY/DEGRADED/STARTING/BACKOFF/STOPPED)映射到前端词表后经 OPENCODE_MCP_SERVER_STATUS 下行。</li>
 *   <li>增删改/toggle/手动添加/市场下载:后端外科手术式写 opencode.json 的 mcp 段(保留 provider 等
 *       其它段),经 SEC-01 闸门 + SEC-06 包名二次确认后落盘并刷新 gateway。仅无工具列表
 *       (OpenCode 无列工具 API,ServerCard 传 showTools=false)。</li>
 * </ul>
 * <p>header 含"刷新状态"按钮;"重载 Gateway"已提升至 {@link McpSettingsSection} 全局操作栏(provider 无关)。
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import type { McpServer, McpServerStatusInfo } from '../../types/mcp';
import { sendAction, subscribeEvent } from '../../bridge/typed';
import { UPSTREAM, DOWNSTREAM } from '../../generated/protocol';
import { ServerCard } from './ServerCard';
import { McpServerDialog } from './McpServerDialog';
import { McpMarketDialog } from './McpMarketDialog';
import { McpConfirmDialog } from './McpConfirmDialog';
import { McpPackageConfirmDialog, type PackageConfirmItem } from './McpPackageConfirmDialog';
import { ToastContainer, type ToastMessage } from '../Toast';
import { copyToClipboard } from '../../utils/copyUtils';
import type { McpTool } from './types';
import { parsePackageRunner } from './packageRunner';
import { SkeletonList } from '../shared/SkeletonList';
import { BracesIcon, ExtensionsIcon, PlugIcon, RefreshIcon, ServerIcon } from '../Icons';
import { UnifiedLoader } from '../UnifiedLoader';

export function OpenCodeMcpPanel() {
  const { t } = useTranslation();
  const [servers, setServers] = useState<McpServer[]>([]);
  const [serverStatus, setServerStatus] = useState<Map<string, McpServerStatusInfo>>(new Map());
  const [loading, setLoading] = useState(true);
  const [statusLoading, setStatusLoading] = useState(false);
  const [expandedServers, setExpandedServers] = useState<Set<string>>(new Set());

  // Dialog state(对称 McpProviderPanel)
  const [showServerDialog, setShowServerDialog] = useState(false);
  const [showMarketDialog, setShowMarketDialog] = useState(false);
  const [showConfirmDialog, setShowConfirmDialog] = useState(false);
  const [editingServer, setEditingServer] = useState<McpServer | null>(null);
  const [deletingServer, setDeletingServer] = useState<McpServer | null>(null);

  // 市场选中 server → 预填 McpServerDialog(isPreset 新建模式),用户填 API key/headers 后保存走 ADD
  const [pendingPresetServer, setPendingPresetServer] = useState<McpServer | null>(null);

  // B3/SEC-06:包管理型 / 容器型 runner 安装前的包名二次确认
  const [pendingPackageApproval, setPendingPackageApproval] = useState<{
    items: PackageConfirmItem[];
    onApprove: () => void;
  } | null>(null);

  // Toast state
  const [toasts, setToasts] = useState<ToastMessage[]>([]);
  const addToast = useCallback((message: string, type: ToastMessage['type'] = 'info') => {
    const id = `toast-${Date.now()}-${Math.random()}`;
    setToasts((prev) => [...prev, { id, message, type }]);
  }, []);
  const dismissToast = useCallback((id: string) => {
    setToasts((prev) => prev.filter((toast) => toast.id !== id));
  }, []);

  const loadServers = useCallback(() => {
    setLoading(true);
    sendAction(UPSTREAM.GET_OPENCODE_MCP_SERVERS, {});
  }, []);

  // status 查询的前端超时句柄:超时复位 statusLoading 允许重试(对齐 useCliModels 15s)
  const statusTimeoutRef = useRef<number | null>(null);
  const loadServerStatus = useCallback(() => {
    setStatusLoading(true);
    if (statusTimeoutRef.current) window.clearTimeout(statusTimeoutRef.current);
    statusTimeoutRef.current = window.setTimeout(() => {
      setStatusLoading(false);
      statusTimeoutRef.current = null;
    }, 15_000);
    sendAction(UPSTREAM.GET_OPENCODE_MCP_SERVER_STATUS, {});
  }, []);

  // gateway 状态事件的重拉节流时间戳(见下方订阅处的 5s 时间窗)
  const gwStatusRefreshAtRef = useRef(0);

  // 进面板:拉列表 + 状态,并订阅下行事件(payload 为 escapeJs 后的 JSON 字符串,需 JSON.parse)
  useEffect(() => {
    loadServers();
    loadServerStatus();

    const handleListUpdate = (jsonStr: string) => {
      try {
        const list: McpServer[] = JSON.parse(jsonStr);
        setServers(Array.isArray(list) ? list : []);
      } catch (error) {
        console.error('[OpenCodeMcpPanel] Failed to parse servers:', error);
        setServers([]);
      } finally {
        setLoading(false);
      }
    };

    const handleStatusUpdate = (jsonStr: string) => {
      try {
        const list: McpServerStatusInfo[] = JSON.parse(jsonStr);
        const map = new Map<string, McpServerStatusInfo>();
        if (Array.isArray(list)) {
          for (const item of list) {
            if (item && typeof item.name === 'string') {
              map.set(item.name, item);
            }
          }
        }
        setServerStatus(map);
      } catch (error) {
        console.error('[OpenCodeMcpPanel] Failed to parse server status:', error);
      } finally {
        // 收到响应:清除前端超时兜底
        if (statusTimeoutRef.current) {
          window.clearTimeout(statusTimeoutRef.current);
          statusTimeoutRef.current = null;
        }
        setStatusLoading(false);
      }
    };

    const unsubList = subscribeEvent(DOWNSTREAM.OPENCODE_MCP_SERVER_LIST, (json) => handleListUpdate(json as string));
    const unsubStatus = subscribeEvent(DOWNSTREAM.OPENCODE_MCP_SERVER_STATUS, (json) => handleStatusUpdate(json as string));
    // gateway 重启完成信号:重拉 server 状态(重启后 health 全变,不重拉则面板停留旧红态)
    // 防御:该事件若在异常路径高频重复(曾与 status 查询形成乒乓风暴),5s 时间窗内只重拉一次。
    const unsubGatewayStatus = subscribeEvent(DOWNSTREAM.MCP_GATEWAY_STATUS, () => {
      const now = Date.now();
      if (now - gwStatusRefreshAtRef.current < 5000) return;
      gwStatusRefreshAtRef.current = now;
      loadServerStatus();
    });
    return () => {
      unsubList();
      unsubStatus();
      unsubGatewayStatus();
      if (statusTimeoutRef.current) {
        window.clearTimeout(statusTimeoutRef.current);
        statusTimeoutRef.current = null;
      }
    };
  }, [loadServers, loadServerStatus]);

  const handleRefresh = useCallback(() => {
    loadServers();
    loadServerStatus();
  }, [loadServers, loadServerStatus]);

  const toggleExpand = useCallback((serverId: string) => {
    setExpandedServers(prev => {
      const next = new Set(prev);
      if (next.has(serverId)) {
        next.delete(serverId);
      } else {
        next.clear();
        next.add(serverId);
      }
      return next;
    });
  }, []);

  // ---- 增删改/toggle/添加(对称 McpProviderPanel,action 换 OPENCODE 通道) ----

  const handleEdit = useCallback((server: McpServer) => {
    setEditingServer(server);
    setShowServerDialog(true);
  }, []);

  const handleDelete = useCallback((server: McpServer) => {
    setDeletingServer(server);
    setShowConfirmDialog(true);
  }, []);

  const confirmDelete = useCallback(() => {
    if (deletingServer) {
      sendAction(UPSTREAM.DELETE_OPENCODE_MCP_SERVER, { id: deletingServer.id });
      addToast(`${t('mcp.deleted')} ${deletingServer.name || deletingServer.id}`, 'success');
      setTimeout(() => {
        loadServers();
      }, 100);
    }
    setShowConfirmDialog(false);
    setDeletingServer(null);
  }, [deletingServer, addToast, t, loadServers]);

  const cancelDelete = useCallback(() => {
    setShowConfirmDialog(false);
    setDeletingServer(null);
  }, []);

  // B3/SEC-06:单个 server 安装前检测包名,命中包管理 / 容器型 runner 则弹二次确认
  const requirePackageApproval = useCallback((server: McpServer, onApprove: () => void) => {
    const info = parsePackageRunner(server.server);
    if (!info) {
      onApprove();
      return;
    }
    setPendingPackageApproval({
      items: [{ serverName: server.name || server.id, info }],
      onApprove,
    });
  }, []);

  const confirmPackageApproval = useCallback(() => {
    const pending = pendingPackageApproval;
    setPendingPackageApproval(null);
    if (pending) {
      pending.onApprove();
    }
  }, [pendingPackageApproval]);

  const cancelPackageApproval = useCallback(() => {
    setPendingPackageApproval(null);
  }, []);

  const handleAddManual = useCallback(() => {
    setEditingServer(null);
    setShowServerDialog(true);
  }, []);

  const handleAddFromMarket = useCallback(() => {
    setShowMarketDialog(true);
  }, []);

  const handleSelectFromMarket = useCallback((server: McpServer) => {
    setEditingServer(null);
    setPendingPresetServer(server);
    setShowMarketDialog(false);
    setShowServerDialog(true);
  }, []);

  // Save server(经 requirePackageApproval:包管理 / 容器 runner 命中则先弹二次确认)。
  // 后端写 opencode.json 的 mcp 段(apps 等前端专属字段被忽略)。
  const handleSaveServer = useCallback((server: McpServer) => {
    requirePackageApproval(server, () => {
      if (editingServer) {
        if (editingServer.id !== server.id) {
          sendAction(UPSTREAM.DELETE_OPENCODE_MCP_SERVER, { id: editingServer.id });
          sendAction(UPSTREAM.ADD_OPENCODE_MCP_SERVER, server);
          addToast(`${t('mcp.updated')} ${server.name || server.id}`, 'success');
        } else {
          sendAction(UPSTREAM.UPDATE_OPENCODE_MCP_SERVER, server);
          addToast(`${t('mcp.saved')} ${server.name || server.id}`, 'success');
        }
      } else {
        sendAction(UPSTREAM.ADD_OPENCODE_MCP_SERVER, server);
        addToast(`${t('mcp.added')} ${server.name || server.id}`, 'success');
      }

      setTimeout(() => {
        loadServers();
      }, 100);

      setShowServerDialog(false);
      setEditingServer(null);
      setPendingPresetServer(null);
    });
  }, [editingServer, addToast, t, loadServers, requirePackageApproval]);

  // toggle=upsert 整个 server(后端落 enabled 字段;apps 为前端专属字段,后端忽略)
  const handleToggleServer = useCallback((server: McpServer, enabled: boolean) => {
    sendAction(UPSTREAM.TOGGLE_OPENCODE_MCP_SERVER, { ...server, enabled });
    addToast(
      enabled
        ? `${t('mcp.enabled')} ${server.name || server.id}`
        : `${t('mcp.disabled')} ${server.name || server.id}`,
      'success'
    );
    loadServers();
    loadServerStatus();
  }, [addToast, t, loadServers, loadServerStatus]);

  // Copy URL
  const handleCopyUrl = useCallback(async (url: string) => {
    const success = await copyToClipboard(url);
    if (success) {
      addToast(t('mcp.linkCopied'), 'success');
    } else {
      addToast(t('mcp.copyFailed'), 'error');
    }
  }, [addToast, t]);

  // Copy server config(redact sensitive values in env/headers,对称 McpProviderPanel)
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

  return (
    <div className="mcp-settings-section">
      {/* Header */}
      <div className="panel-header">
        <div className="panel-title">
          <span className="ico-badge"><PlugIcon size={16} /></span>
          <span className="title-text">
            {t('mcp.title')}
            <span className="subtitle">{t('mcp.subtitle')}</span>
          </span>
        </div>
        <div className="header-tools">
          <button
            className="icon-btn"
            onClick={handleRefresh}
            disabled={loading || statusLoading}
            title={t('mcp.refreshStatus')}
            aria-label={t('mcp.refreshStatus')}
          >
            {loading || statusLoading ? <UnifiedLoader type="spin" size={16} /> : <RefreshIcon size={16} />}
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
          {/* 从市场获取(主色按钮) */}
          <button
            className="market-btn"
            onClick={handleAddFromMarket}
            title={t('mcp.addFromMarket')}
          >
            <ExtensionsIcon size={16} />
            {t('mcp.addFromMarket')}
          </button>
        </div>
      </div>

      <div className="mcp-panels-container">
        <div className="mcp-server-panel">
          {!loading || servers.length > 0 ? (
            <div className="server-list">
              {servers.map((server, index) => (
                <ServerCard
                  key={server.id}
                  server={server}
                  isExpanded={expandedServers.has(server.id)}
                  isCodexMode={false}
                  serverStatus={serverStatus}
                  t={t}
                  showTools={false}
                  onToggleExpand={() => toggleExpand(server.id)}
                  onToggleServer={(enabled) => handleToggleServer(server, enabled)}
                  onEdit={() => handleEdit(server)}
                  onDelete={() => handleDelete(server)}
                  onCopy={() => handleCopyConfig(server)}
                  onRefresh={() => {}}
                  onLoadTools={() => {}}
                  onCopyUrl={handleCopyUrl}
                  onToolHover={((_tool: McpTool | null) => {})}
                  animationIndex={index}
                />
              ))}

              {servers.length === 0 && !loading && (
                <div className="empty-state">
                  <ServerIcon size={16} />
                  <p>{t('mcp.noServers')}</p>
                  <p className="hint">{t('mcp.addServerHint')}</p>
                </div>
              )}
            </div>
          ) : null}

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
          currentProvider="opencode"
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
          isCodexMode={false}
          onClose={() => setShowMarketDialog(false)}
          onSelect={handleSelectFromMarket}
        />
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

      {pendingPackageApproval && (
        <McpPackageConfirmDialog
          items={pendingPackageApproval.items}
          onConfirm={confirmPackageApproval}
          onCancel={cancelPackageApproval}
        />
      )}

      {/* Toast notifications */}
      <ToastContainer messages={toasts} onDismiss={dismissToast} />
    </div>
  );
}
