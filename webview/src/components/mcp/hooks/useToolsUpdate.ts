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

export interface UseToolsUpdateOptions {
  isCodexMode: boolean;
  cacheKeys: CacheKeys;
  setServerTools: React.Dispatch<React.SetStateAction<ServerToolsState>>;
  onLog: (message: string, type: RefreshLog['type'], details?: string, serverName?: string, requestInfo?: string, errorReason?: string) => void;
}

/**
 * Tools List Update Hook
 * 订阅 mcp.server_tools / codex.mcp.server_tools 事件(归一化下行总线)。
 */
export function useToolsUpdate({
  isCodexMode,
  cacheKeys,
  setServerTools,
  onLog,
}: UseToolsUpdateOptions): void {
  useEffect(() => {
    const type = isCodexMode ? DOWNSTREAM.CODEX_MCP_SERVER_TOOLS : DOWNSTREAM.MCP_SERVER_TOOLS;
    const legacyName = isCodexMode ? 'updateCodexMcpServerTools' : 'updateMcpServerTools';

    // Tools list update handler
    const handleToolsUpdate = (jsonStr: string) => {
      try {
        const result = JSON.parse(jsonStr);
        const { serverId, serverName, tools, error } = result;

        if (!serverId) {
          console.warn('[MCP] Tools update missing serverId');
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
          `工具列表加载完成: 0 个工具`,
          'success',
          undefined,
          serverName || serverId
        );
      } catch (e) {
        console.error('[MCP] Failed to parse tools update:', e);
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

    // Cleanup
    return () => {
      unsubscribe();
    };
  }, [isCodexMode, cacheKeys, setServerTools, onLog]);
}
