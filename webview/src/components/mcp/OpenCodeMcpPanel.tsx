/**
 * OpenCode MCP Server 面板（只读）。
 *
 * <p>与 Claude/Codex 的 {@link McpProviderPanel} 差异:OpenCode 的 MCP server 在插件 channel 层
 * 无 getMcpServerStatus/列工具命令,故不复用 useServerData 的 isCodexMode 二元结构与 mcp_/codex_
 * 前缀通道。本面板用独立的 GET_OPENCODE_MCP_* action:
 * <ul>
 *   <li>server 列表:后端读 {@code ~/.config/opencode/opencode.json} 的 {@code mcp} 字段(global 层),
 *       适配成 McpServer 嵌套形状后经 OPENCODE_MCP_SERVER_LIST 下行。</li>
 *   <li>连接状态:后端取 MCP Gateway 聚合 statusJson,过滤 sourceProvider=="opencode" 并把 gateway
 *       state(READY/DEGRADED/STARTING/BACKOFF/STOPPED)映射到前端词表后经 OPENCODE_MCP_SERVER_STATUS 下行。</li>
 * </ul>
 * <p>不含增删改/工具列表(只读 + OpenCode 多层合并 + 有 mcp add 无 remove,与定位冲突)。展开卡片仍可见
 * command/url 详情(只读展示)。
 */

import { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import type { McpServer, McpServerStatusInfo } from '../../types/mcp';
import { sendAction, subscribeEvent } from '../../bridge/typed';
import { UPSTREAM, DOWNSTREAM } from '../../generated/protocol';
import { ServerCard } from './ServerCard';
import type { McpTool } from './types';
import { SkeletonList } from '../shared/SkeletonList';
import { PlugIcon, RefreshIcon, ServerIcon } from '../Icons';
import { UnifiedLoader } from '../UnifiedLoader';

export function OpenCodeMcpPanel() {
  const { t } = useTranslation();
  const [servers, setServers] = useState<McpServer[]>([]);
  const [serverStatus, setServerStatus] = useState<Map<string, McpServerStatusInfo>>(new Map());
  const [loading, setLoading] = useState(true);
  const [statusLoading, setStatusLoading] = useState(false);
  const [expandedServers, setExpandedServers] = useState<Set<string>>(new Set());

  const loadServers = useCallback(() => {
    setLoading(true);
    sendAction(UPSTREAM.GET_OPENCODE_MCP_SERVERS, {});
  }, []);

  const loadServerStatus = useCallback(() => {
    setStatusLoading(true);
    sendAction(UPSTREAM.GET_OPENCODE_MCP_SERVER_STATUS, {});
  }, []);

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
        setStatusLoading(false);
      }
    };

    const unsubList = subscribeEvent(DOWNSTREAM.OPENCODE_MCP_SERVER_LIST, (json) => handleListUpdate(json as string));
    const unsubStatus = subscribeEvent(DOWNSTREAM.OPENCODE_MCP_SERVER_STATUS, (json) => handleStatusUpdate(json as string));
    return () => {
      unsubList();
      unsubStatus();
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
        </div>
      </div>

      {/* 只读提示条 */}
      <div className="opencode-readonly-hint">
        {t('mcp.opencodeReadonlyHint')}
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
                  readOnly
                  onToggleExpand={() => toggleExpand(server.id)}
                  onToggleServer={() => {}}
                  onEdit={() => {}}
                  onDelete={() => {}}
                  onCopy={() => {}}
                  onRefresh={() => {}}
                  onLoadTools={() => {}}
                  onCopyUrl={() => {}}
                  onToolHover={((_tool: McpTool | null) => {})}
                  animationIndex={index}
                />
              ))}

              {servers.length === 0 && !loading && (
                <div className="empty-state">
                  <ServerIcon size={16} />
                  <p>{t('mcp.noServers')}</p>
                  <p className="hint">{t('mcp.opencodeReadonlyHint')}</p>
                </div>
              )}
            </div>
          ) : null}

          {loading && servers.length === 0 && (
            <SkeletonList label={t('mcp.loading')} />
          )}
        </div>
      </div>
    </div>
  );
}
