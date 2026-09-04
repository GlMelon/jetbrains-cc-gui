/**
 * Server Management Operations Hook
 * Handles server refresh, toggle, and other operations
 */

import { useState, useCallback, useRef, useEffect } from 'react';
import type { McpServer, ServerToolsState, ServerRefreshState, RefreshLog, CacheKeys } from '../types';
import { sendAction } from '../../../bridge/typed';
import { UPSTREAM } from '../../../generated/protocol';
import { clearToolsCache, clearAllToolsCache } from '../utils';
import type { ToastMessage } from '../../Toast';

/**
 * Server Management Operations Hook
 */
export function useServerManagement({
  cacheKeys,
  setServerTools,
  loadServers,
  loadServerStatus,
  loadServerTools,
  onLog,
  onToast,
  t,
}: {
  cacheKeys: CacheKeys;
  setServerTools: React.Dispatch<React.SetStateAction<ServerToolsState>>;
  loadServers: () => void;
  loadServerStatus: () => void;
  loadServerTools: (server: McpServer, forceRefresh?: boolean) => void;
  onLog: (message: string, type: RefreshLog['type'], details?: string, serverName?: string, requestInfo?: string, errorReason?: string) => void;
  onToast: (message: string, type: ToastMessage['type']) => void;
  t: (key: string, options?: Record<string, unknown>) => string;
}): {
  serverRefreshStates: ServerRefreshState;
  handleRefresh: () => void;
  handleRefreshSingleServer: (server: McpServer, forceRefreshTools?: boolean) => void;
  handleToggleServer: (server: McpServer, enabled: boolean) => void;
} {
  // Individual server refresh state
  const [serverRefreshStates, setServerRefreshStates] = useState<ServerRefreshState>({});
  // 单 server 刷新的延时句柄:随组件卸载清理,避免面板关闭后仍补发全量 loadServerStatus
  const refreshTimersRef = useRef<number[]>([]);

  // 卸载时清理所有悬挂的刷新定时器
  useEffect(() => {
    return () => {
      refreshTimersRef.current.forEach((id) => window.clearTimeout(id));
      refreshTimersRef.current = [];
    };
  }, []);

  // Set individual server refresh state
  const setServerRefreshing = useCallback((serverId: string, isRefreshing: boolean, step: string = '') => {
    setServerRefreshStates(prev => ({
      ...prev,
      [serverId]: { isRefreshing, step }
    }));
  }, []);

  // Refresh all servers
  const handleRefresh = useCallback(() => {
    onLog(t('mcp.logs.refreshingAll'), 'info');
    // Clear all tools cache
    clearAllToolsCache(cacheKeys);
    // Clear current tools state
    setServerTools({});
    loadServers();
    loadServerStatus();
  }, [cacheKeys, setServerTools, loadServers, loadServerStatus, t, onLog]);

  // Refresh a single server
  const handleRefreshSingleServer = useCallback((server: McpServer, forceRefreshTools: boolean = false) => {
    const serverName = server.name || server.id;
    setServerRefreshing(server.id, true, t('mcp.logs.startRefresh'));

    if (forceRefreshTools) {
      // Force refresh tools list
      clearToolsCache(server.id, cacheKeys);
      setServerTools(prev => {
        const next = { ...prev };
        delete next[server.id];
        return next;
      });
      onLog(t('mcp.logs.forceRefreshingToolsServer', { name: serverName }), 'info', undefined, serverName);
      loadServerTools(server, true);
    } else {
      onLog(t('mcp.logs.startRefreshServer', { name: serverName }), 'info', undefined, serverName);
    }

    // Simulate refresh process (SDK doesn't support single server refresh)
    // 定时器存 ref,随卸载清理;否则面板关闭后 1.5s 仍补发全量 loadServerStatus
    const step1 = window.setTimeout(() => {
      setServerRefreshing(server.id, true, t('mcp.logs.checkingConnection'));
      onLog(t('mcp.logs.checkingConnectionServer', { name: serverName }), 'info', undefined, serverName);
    }, 300);
    refreshTimersRef.current.push(step1);

    const step2 = window.setTimeout(() => {
      // Refresh all server statuses to get updates
      loadServerStatus();
      setServerRefreshing(server.id, false, '');
      onLog(t('mcp.logs.refreshComplete', { name: serverName }), 'success', undefined, serverName);
    }, 1500);
    refreshTimersRef.current.push(step2);
  }, [cacheKeys, setServerTools, loadServerStatus, loadServerTools, t, onLog, setServerRefreshing]);

  // Toggle server enabled state
  const handleToggleServer = useCallback((server: McpServer, enabled: boolean) => {
    const updatedServer: McpServer = { ...server, enabled };

    sendAction(UPSTREAM.TOGGLE_MCP_SERVER, updatedServer);

    // A toggle invalidates the previous tool result. This forces a fresh
    // tools/list request after the server becomes connected again.
    clearToolsCache(server.id, cacheKeys);
    setServerTools(prev => {
      const next = { ...prev };
      delete next[server.id];
      return next;
    });

    onToast(
      enabled
        ? `${t('mcp.enabled')} ${server.name || server.id}`
        : `${t('mcp.disabled')} ${server.name || server.id}`,
      'success'
    );
    loadServers();
    loadServerStatus();
  }, [cacheKeys, setServerTools, onToast, t, loadServers, loadServerStatus]);

  return {
    serverRefreshStates,
    handleRefresh,
    handleRefreshSingleServer,
    handleToggleServer,
  };
}
