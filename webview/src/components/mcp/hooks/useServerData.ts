/**
 * Server Data Loading and Initialization Hook
 * Manages loading of server list, status, and cache
 */

import { useState, useEffect, useRef, useCallback } from 'react';
import { subscribeEvent, sendAction } from '../../../bridge/typed';
import { DOWNSTREAM, UPSTREAM } from '../../../generated/protocol';
import { registerLegacyAlias } from '../../../bridge';
import type { McpServer, McpServerStatusInfo, ServerToolsState, RefreshLog, CacheKeys } from '../types';
import { readCache, readToolsCache, writeCache } from '../utils';

interface UseServerDataOptions {
  isCodexMode: boolean;
  messagePrefix: string;
  cacheKeys: CacheKeys;
  t: (key: string, options?: Record<string, unknown>) => string;
  onLog: (message: string, type: RefreshLog['type'], details?: string, serverName?: string, requestInfo?: string, errorReason?: string) => void;
}

const TERMINAL_STATUSES = new Set(['failed', 'needs-auth', 'disabled']);

/**
 * Java may never answer a status query (node spawn hangs, bridge not ready,
 * latch 65s) — don't leave the spinner / disabled button on forever. Matches
 * useCliModels' 15s front-end timeout policy. The Java side still has its own
 * (65s) latch, but the UI recovers sooner.
 */
const STATUS_TIMEOUT_MS = 15_000;

function getTerminalStatusNames(statusList: McpServerStatusInfo[]): Set<string> {
  const names = new Set<string>();
  for (const status of statusList) {
    if (TERMINAL_STATUSES.has(status.status)) {
      names.add(status.name);
    }
  }
  return names;
}

function clearToolsForTerminalStatuses(
  toolsState: ServerToolsState,
  servers: McpServer[],
  terminalNames: Set<string>,
): ServerToolsState {
  if (terminalNames.size === 0) return toolsState;
  const next = { ...toolsState };
  let changed = false;
  for (const server of servers) {
    if (server.name && terminalNames.has(server.name) && next[server.id]) {
      delete next[server.id];
      changed = true;
    }
  }
  return changed ? next : toolsState;
}

interface UseServerDataReturn {
  // State
  servers: McpServer[];
  serverStatus: Map<string, McpServerStatusInfo>;
  loading: boolean;
  statusLoading: boolean;
  serverTools: ServerToolsState;
  expandedServers: Set<string>;

  // State update functions
  setServers: React.Dispatch<React.SetStateAction<McpServer[]>>;
  setServerStatus: React.Dispatch<React.SetStateAction<Map<string, McpServerStatusInfo>>>;
  setServerTools: React.Dispatch<React.SetStateAction<ServerToolsState>>;
  setExpandedServers: React.Dispatch<React.SetStateAction<Set<string>>>;

  // Data loading functions
  loadServers: () => void;
  loadServerStatus: () => void;
  loadServerTools: (server: McpServer, forceRefresh?: boolean) => void;
  acceptToolsResponse: (serverId: string, requestId: string) => boolean;
  failPendingToolsRequests: (error: string) => void;
}

/**
 * Server Data Loading and Initialization Hook
 */
export function useServerData({
  isCodexMode,
  messagePrefix,
  cacheKeys,
  t,
  onLog
}: UseServerDataOptions): UseServerDataReturn {
  // State
  const [servers, setServersState] = useState<McpServer[]>([]);
  const [serverStatus, setServerStatus] = useState<Map<string, McpServerStatusInfo>>(new Map());
  const [loading, setLoading] = useState(true);
  const [statusLoading, setStatusLoading] = useState(false);
  const [serverTools, setServerTools] = useState<ServerToolsState>({});
  const [expandedServers, setExpandedServers] = useState<Set<string>>(new Set());

  // Aliases for state setters used in callbacks
  const setServers = setServersState;

  // Refs
  const refreshTimersRef = useRef<number[]>([]);
  const toolsRequestCounterRef = useRef(0);
  const latestToolsRequestIdsRef = useRef<Map<string, string>>(new Map());
  const terminalStatusNamesRef = useRef<Set<string>>(new Set());
  const serversRef = useRef<McpServer[]>([]);
  /** status 查询的前端超时句柄:超时复位 statusLoading,允许重试(对齐 useCliModels)。 */
  const statusTimeoutRef = useRef<number | null>(null);

  useEffect(() => {
    latestToolsRequestIdsRef.current.clear();
    setServerTools({});
  }, [isCodexMode, cacheKeys]);

  // Keep serversRef in sync with servers state
  useEffect(() => {
    serversRef.current = servers;
  }, [servers]);

  const acceptToolsResponse = useCallback((serverId: string, requestId: string): boolean => {
    if (!serverId || !requestId) {
      return false;
    }
    const latestRequestId = latestToolsRequestIdsRef.current.get(serverId);
    if (latestRequestId !== requestId) {
      return false;
    }
    latestToolsRequestIdsRef.current.delete(serverId);
    return true;
  }, []);

  const failPendingToolsRequests = useCallback((error: string): void => {
    const pendingServerIds = Array.from(latestToolsRequestIdsRef.current.keys());
    latestToolsRequestIdsRef.current.clear();
    if (pendingServerIds.length === 0) {
      return;
    }
    setServerTools(prev => {
      const next = { ...prev };
      pendingServerIds.forEach(serverId => {
        next[serverId] = {
          tools: prev[serverId]?.tools || [],
          loading: false,
          error,
        };
      });
      return next;
    });
  }, []);

  // Load server list
  const loadServers = useCallback(() => {
    setLoading(true);
    onLog(
      t('mcp.logs.loadingServers'),
      'info',
      undefined,
      undefined,
      `get_${messagePrefix}mcp_servers request to backend`
    );
    sendAction(isCodexMode ? UPSTREAM.GET_CODEX_MCP_SERVERS : UPSTREAM.GET_MCP_SERVERS, {});
  }, [messagePrefix, isCodexMode, t, onLog]);

  // Load server status
  const loadServerStatus = useCallback(() => {
    setStatusLoading(true);
    // 前端超时兜底:Java 侧 latch 65s,但 node 挂起/bridge 未就绪时按钮会假死 ~75s;
    // 超时复位允许重试(对齐 useCliModels 的 15s 策略)。收到下行事件时在 handleServerStatusUpdate 清除。
    if (statusTimeoutRef.current) window.clearTimeout(statusTimeoutRef.current);
    statusTimeoutRef.current = window.setTimeout(() => {
      setStatusLoading(false);
      statusTimeoutRef.current = null;
    }, STATUS_TIMEOUT_MS);
    onLog(
      t('mcp.logs.refreshingStatus'),
      'info',
      undefined,
      undefined,
      `get_${messagePrefix}mcp_server_status request to backend`,
      `Querying MCP server connection status via ${isCodexMode ? 'Codex' : 'Claude'} SDK`
    );
    sendAction(isCodexMode ? UPSTREAM.GET_CODEX_MCP_SERVER_STATUS : UPSTREAM.GET_MCP_SERVER_STATUS, {});
  }, [messagePrefix, isCodexMode, t, onLog]);

  // Load server tools list
  const loadServerTools = useCallback((server: McpServer, forceRefresh = false) => {
    // Check cache (unless force refresh)
    if (!forceRefresh) {
      const cachedTools = readToolsCache(server.id, cacheKeys);
      if (cachedTools && cachedTools.length > 0) {
        setServerTools(prev => ({
          ...prev,
          [server.id]: {
            tools: cachedTools,
            loading: false,
            error: undefined
          }
        }));
        onLog(
          t('mcp.logs.loadedToolsFromCache', { name: server.name || server.id, count: cachedTools.length }),
          'info',
          undefined,
          server.name || server.id
        );
        return;
      }
    }

    // Set loading state
    setServerTools(prev => ({
      ...prev,
      [server.id]: {
        tools: [],
        loading: true,
        error: undefined
      }
    }));

    onLog(
      forceRefresh
        ? t('mcp.logs.forceRefreshingTools', { name: server.name || server.id })
        : t('mcp.logs.loadingTools', { name: server.name || server.id }),
      'info',
      undefined,
      server.name || server.id,
      `get_${messagePrefix}mcp_server_tools request to backend`
    );

    toolsRequestCounterRef.current += 1;
    const requestId = `${Date.now().toString(36)}-${toolsRequestCounterRef.current.toString(36)}`;
    latestToolsRequestIdsRef.current.set(server.id, requestId);
    sendAction(
      isCodexMode ? UPSTREAM.GET_CODEX_MCP_SERVER_TOOLS : UPSTREAM.GET_MCP_SERVER_TOOLS,
      { requestId, serverId: server.id, forceRefresh }
    );
  }, [cacheKeys, messagePrefix, isCodexMode, t, onLog]);

  // Initialization and data loading
  useEffect(() => {
    const clearRefreshTimers = () => {
      refreshTimersRef.current.forEach((timerId) => window.clearTimeout(timerId));
      refreshTimersRef.current = [];
    };

    // Load data from cache
    const loadFromCache = (): boolean => {
      terminalStatusNamesRef.current = new Set();
      const cachedServers = readCache<McpServer[]>(cacheKeys.SERVERS, cacheKeys);
      const hasValidCache = !!cachedServers && cachedServers.length > 0;

      if (hasValidCache) {
        setServers(cachedServers);
        setLoading(false);
        const cacheAge = Date.now() - (JSON.parse(localStorage.getItem(cacheKeys.SERVERS) || '{}').timestamp || 0);
        if (cacheAge < 60000) {
          onLog(t('mcp.logs.fastLoadCache', { count: cachedServers.length, seconds: Math.round(cacheAge/1000) }), 'info');
        }
      }

      // 状态缓存恢复(codex 此前只写不读 → 重开面板状态列空白)。读取后若缺失/过期,
      // 下方 hasCache 分支会补发 loadServerStatus(后端有 30s 缓存+合并+负缓存兜成本)。
      const cachedStatus = readCache<McpServerStatusInfo[]>(cacheKeys.STATUS, cacheKeys);
      if (cachedStatus && cachedStatus.length > 0) {
        const terminalStatusNames = getTerminalStatusNames(cachedStatus);
        terminalStatusNamesRef.current = terminalStatusNames;
        setServerTools(prev => clearToolsForTerminalStatuses(prev, cachedServers || [], terminalStatusNames));
        const statusMap = new Map<string, McpServerStatusInfo>();
        cachedStatus.forEach((status) => {
          statusMap.set(status.name, status);
        });
        setServerStatus(statusMap);
        setStatusLoading(false);
      }

      // Restore last expanded server
      if (hasValidCache) {
        try {
          const lastServerId = localStorage.getItem(cacheKeys.LAST_SERVER_ID);
          if (lastServerId) {
            const serverExists = cachedServers.some(s => s.id === lastServerId);
            if (serverExists) {
              setExpandedServers(new Set([lastServerId]));
              const cachedTools = readToolsCache(lastServerId, cacheKeys);
              if (cachedTools && cachedTools.length > 0) {
                setServerTools(prev => ({
                  ...prev,
                  [lastServerId]: {
                    tools: cachedTools,
                    loading: false,
                    error: undefined
                  }
                }));
                onLog(t('mcp.logs.loadedToolsFromCacheSimple', { count: cachedTools.length }), 'info', undefined, lastServerId);
              }
            }
          }
        } catch (e) {
          console.warn('[MCP] Failed to restore last expanded server:', e);
        }
      }

      return hasValidCache;
    };

    // Try loading data from cache first
    const hasCache = loadFromCache();

    if (hasCache) {
      onLog(t('mcp.logs.usingCacheStrategy'), 'info');
      // server 缓存有效但状态缓存缺失或已过期 → 补发一次状态查询(后端有 30s 缓存+合并+负缓存兜成本),
      // 避免重开面板时状态列空白不自动补拉(codex 模式此前因状态缓存只写不读而必然空白)。
      const statusCacheEntry = localStorage.getItem(cacheKeys.STATUS);
      const statusCacheAge = statusCacheEntry
        ? Date.now() - (JSON.parse(statusCacheEntry).timestamp || 0)
        : Infinity;
      if (!statusCacheEntry || statusCacheAge > 30_000) {
        loadServerStatus();
      }
    } else {
      onLog(t('mcp.logs.firstLoad'), 'info');
      loadServers();
      loadServerStatus();
    }

    return () => {
      clearRefreshTimers();
      if (statusTimeoutRef.current) {
        window.clearTimeout(statusTimeoutRef.current);
        statusTimeoutRef.current = null;
      }
    };
  }, [cacheKeys, isCodexMode, loadServers, loadServerStatus, t, onLog, clearToolsForTerminalStatuses]);

  // Register server list update callback
  useEffect(() => {
    const handleServerListUpdate = (jsonStr: string) => {
      try {
        const serverList: McpServer[] = JSON.parse(jsonStr);
        setServers(serverList);
        setServerTools(prev => clearToolsForTerminalStatuses(prev, serverList, terminalStatusNamesRef.current));
        setLoading(false);
        // Persist to cache so subsequent mounts can load instantly
        writeCache(cacheKeys.SERVERS, serverList);
        onLog(t('mcp.logs.loadedServersSuccess', { count: serverList.length }), 'success');
      } catch (error) {
        console.error('[McpSettings] Failed to parse servers:', error);
        setLoading(false);
        onLog(t('mcp.logs.loadedServersFailed', { error: String(error) }), 'error');
      }
    };

    const handleServerStatusUpdate = (jsonStr: string) => {
      try {
        const statusList: McpServerStatusInfo[] = JSON.parse(jsonStr);
        const statusMap = new Map<string, McpServerStatusInfo>();
        statusList.forEach((status) => {
          statusMap.set(status.name, status);
        });
        setServerStatus(statusMap);
        const terminalStatusNames = getTerminalStatusNames(statusList);
        terminalStatusNamesRef.current = terminalStatusNames;
        setServerTools(prev => clearToolsForTerminalStatuses(prev, serversRef.current, terminalStatusNames));
        // 收到响应:清除前端超时兜底
        if (statusTimeoutRef.current) {
          window.clearTimeout(statusTimeoutRef.current);
          statusTimeoutRef.current = null;
        }
        setStatusLoading(false);
        // Persist status to cache
        writeCache(cacheKeys.STATUS, statusList);

        const statusCount = {
          connected: statusList.filter(s => s.status === 'connected').length,
          failed: statusList.filter(s => s.status === 'failed').length,
          pending: statusList.filter(s => s.status === 'pending').length,
          needsAuth: statusList.filter(s => s.status === 'needs-auth').length
        };

        onLog(
          t('mcp.logs.statusUpdateComplete', {
            total: statusList.length,
            connected: statusCount.connected,
            failed: statusCount.failed,
            pending: statusCount.pending,
            needsAuth: statusCount.needsAuth
          }),
          statusCount.failed > 0 ? 'warning' : 'success'
        );
      } catch (error) {
        console.error('[McpSettings] Failed to parse server status:', error);
        setStatusLoading(false);
        onLog(t('mcp.logs.loadedStatusFailed', { error: String(error) }), 'error');
      }
    };

    // Register callbacks（[归一化] 经 bridgeHub 订阅，替代旧 window.xxx 覆盖）
    if (isCodexMode) {
      registerLegacyAlias('updateCodexMcpServers', DOWNSTREAM.CODEX_MCP_SERVER_LIST);
      registerLegacyAlias('updateCodexMcpServerStatus', DOWNSTREAM.CODEX_MCP_SERVER_STATUS);
      const unsubList = subscribeEvent(DOWNSTREAM.CODEX_MCP_SERVER_LIST, (json) => handleServerListUpdate(json as string));
      const unsubStatus = subscribeEvent(DOWNSTREAM.CODEX_MCP_SERVER_STATUS, (json) => handleServerStatusUpdate(json as string));
      return () => {
        unsubList();
        unsubStatus();
      };
    } else {
      registerLegacyAlias('updateMcpServers', DOWNSTREAM.MCP_SERVER_LIST);
      registerLegacyAlias('updateMcpServerStatus', DOWNSTREAM.MCP_SERVER_STATUS);
      const unsubList = subscribeEvent(DOWNSTREAM.MCP_SERVER_LIST, (json) => handleServerListUpdate(json as string));
      const unsubStatus = subscribeEvent(DOWNSTREAM.MCP_SERVER_STATUS, (json) => handleServerStatusUpdate(json as string));
      return () => {
        unsubList();
        unsubStatus();
      };
    }
  }, [isCodexMode, t, onLog]);

  return {
    // State
    servers,
    serverStatus,
    loading,
    statusLoading,
    serverTools,
    expandedServers,

    // State update functions
    setServers,
    setServerStatus,
    setServerTools,
    setExpandedServers,

    // Data loading functions
    loadServers,
    loadServerStatus,
    loadServerTools,
    acceptToolsResponse,
    failPendingToolsRequests,
  };
}
