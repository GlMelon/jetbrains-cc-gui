/**
 * Tools List Update Hook
 * Listens for tools list update events and handles state updates
 *
 * [归一化] 经 bridgeHub 订阅 server_tools 事件,替代旧 window.updateMcpServerTools 覆盖。
 * 双轨: registerLegacyAlias 兼容后端旧 callJavaScript(legacyName) 调用路径。
 */

import { useEffect } from 'react';
import type { ServerToolsState, McpTool, RefreshLog, CacheKeys } from '../types';
import { writeToolsCache } from '../utils';
import { registerLegacyAlias } from '../../../bridge';
import { subscribeEvent } from '../../../bridge/typed';
import { DOWNSTREAM } from '../../../generated/protocol';

/**
 * Tools List Update Hook
 * 订阅 mcp.server_tools 事件(归一化下行总线)。
 */
export function useToolsUpdate({
  cacheKeys,
  setServerTools,
  acceptToolsResponse,
  failPendingToolsRequests,
  onLog,
}: {
  cacheKeys: CacheKeys;
  setServerTools: React.Dispatch<React.SetStateAction<ServerToolsState>>;
  acceptToolsResponse: (serverId: string, requestId: string) => boolean;
  failPendingToolsRequests: (error: string) => void;
  onLog: (message: string, type: RefreshLog['type'], details?: string, serverName?: string, requestInfo?: string, errorReason?: string) => void;
}): void {
  useEffect(() => {
    const type = DOWNSTREAM.MCP_SERVER_TOOLS;
    const legacyName = 'updateMcpServerTools';

    // Tools list update handler
    const handleToolsUpdate = (jsonStr: string) => {
      try {
        const result = JSON.parse(jsonStr);
        const { requestId, serverId, serverName, tools, error } = result;

        if (!serverId || !requestId) {
          console.warn('[MCP] Tools update missing requestId or serverId');
          return;
        }
        if (!acceptToolsResponse(serverId, requestId)) {
          console.debug('[MCP] Ignored stale tools response:', { requestId, serverId });
          return;
        }

        const toolList: McpTool[] = tools || [];

        // When tools are available, treat as (partial) success even if error exists
        if (toolList.length > 0) {
          setServerTools(prev => ({
            ...prev,
            [serverId]: {
              tools: toolList,
              loading: false,
              error: error || undefined
            }
          }));

          writeToolsCache(serverId, toolList, cacheKeys);

          onLog(
            `Tools loaded: ${toolList.length} tool(s)`,
            error ? 'warning' : 'success',
            `Tools: ${toolList.slice(0, 5).map(t => t.name).join(', ')}${toolList.length > 5 ? '...' : ''}`,
            serverName || serverId
          );
          return;
        }

        // No tools and has error — full failure
        if (error) {
          setServerTools(prev => ({
            ...prev,
            [serverId]: {
              tools: prev[serverId]?.tools || [],
              loading: false,
              error: error
            }
          }));
          onLog(
            `获取工具列表失败: ${error}`,
            'error',
            error,
            serverName || serverId
          );
          return;
        }

        // No tools, no error — empty result
        setServerTools(prev => ({
          ...prev,
          [serverId]: {
            tools: [],
            loading: false,
            error: undefined
          }
        }));

        onLog(
          `工具列表为空，服务器已连接但没有可用工具`,
          'warning',
          undefined,
          serverName || serverId
        );
      } catch (e) {
        console.error('[MCP] Failed to parse tools update:', e);
        failPendingToolsRequests(String(e));
        onLog(
          `解析工具列表失败: ${e}`,
          'error'
        );
      }
    };

    // [归一化] 双轨订阅:
    //  - subscribe(type): 接收后端新路径 dispatchEvent(type)
    //  - registerLegacyAlias(legacyName, type): 兼容后端旧 callJavaScript(legacyName)
    registerLegacyAlias(legacyName, type);
    const unsubscribe = subscribeEvent(type, (json) => handleToolsUpdate(json as string));

    return () => {
      unsubscribe();
    };
  }, [cacheKeys, setServerTools, acceptToolsResponse, failPendingToolsRequests, onLog]);
}
