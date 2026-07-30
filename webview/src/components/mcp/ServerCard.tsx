/**
 * Server Card Component
 * 按设计稿 mcp-skill-settings-redesign 重构:常驻 meta pills(状态/工具数/LOCAL-REMOTE/命令) +
 * hover 上浮 + 操作半透明收纳 + chip 流展开区。
 */

import type { McpServer, McpServerStatusInfo } from '../../types/mcp';
import type { ServerRefreshState, ServerToolsState, McpTool } from './types';
import { getServerStatusInfo, getStatusText, getIconColor, getServerInitial, isServerEnabled } from './utils';
import { ServerToolsPanel } from './ServerToolsPanel';
import { BookIcon, ChevronRightIcon, CopyIcon, EditIcon, HomeIcon, TrashIcon } from '../Icons';

/** 状态 → pill 修饰类(ok/err/warn/muted),用于常驻状态 pill */
function getStatusPillClass(
  server: McpServer,
  status: McpServerStatusInfo['status'] | undefined,
  isCodexMode: boolean
): string {
  if (!isServerEnabled(server, isCodexMode)) return 'muted';
  switch (status) {
    case 'connected': return 'ok';
    case 'failed': return 'err';
    case 'needs-auth': return 'warn';
    default: return 'muted'; // pending / unknown
  }
}

/**
 * Server Card Component
 */
export function ServerCard({
  server,
  isExpanded,
  isCodexMode,
  serverStatus,
  toolsInfo,
  t,
  onToggleExpand,
  onToggleServer,
  onEdit,
  onDelete,
  onCopy,
  onLoadTools,
  onCopyUrl,
  onToolHover,
}: {
  server: McpServer;
  isExpanded: boolean;
  isCodexMode: boolean;
  serverStatus: Map<string, McpServerStatusInfo>;
  refreshState?: ServerRefreshState[string];
  toolsInfo?: ServerToolsState[string];
  t: (key: string, options?: Record<string, unknown>) => string;
  onToggleExpand: () => void;
  onToggleServer: (enabled: boolean) => void;
  onEdit: () => void;
  onDelete: () => void;
  onCopy: () => void;
  onRefresh: () => void;
  onLoadTools: (forceRefresh: boolean) => void;
  onCopyUrl: (url: string) => void;
  onToolHover: (tool: McpTool | null, position?: { x: number; y: number }) => void;
}) {
  const statusInfo = getServerStatusInfo(server, serverStatus);
  const status = statusInfo?.status;
  const effectiveStatus: McpServerStatusInfo['status'] | undefined =
    status === 'pending' && (toolsInfo?.tools?.length ?? 0) > 0
      ? 'connected'
      : status;
  const enabled = isServerEnabled(server, isCodexMode);
  const isConnected = effectiveStatus === 'connected';

  const iconStyle: React.CSSProperties = { background: getIconColor(server.id) };
  const statusPillClass = getStatusPillClass(server, effectiveStatus, isCodexMode);
  const statusText = getStatusText(server, effectiveStatus, isCodexMode, t);

  const hasUrl = !!server.server.url;
  const hasCommand = !!server.server.command;
  const commandDisplay = hasCommand
    ? [server.server.command, ...(server.server.args || [])].join(' ')
    : '';

  const toolCount = toolsInfo?.tools?.length;

  return (
    <div className={`item-card ${isExpanded ? 'expanded' : ''} ${!enabled ? 'disabled' : ''}`}>
      {/* 卡片行 */}
      <div className="card-row" onClick={onToggleExpand}>
        <span className="chev">
          <ChevronRightIcon size={16} />
        </span>
        <div className="card-icon" style={iconStyle} title={server.name || server.id}>
          {getServerInitial(server)}
        </div>
        <div className="card-main">
          <div className="card-title">{server.name || server.id}</div>
          {server.description && (
            <div className="card-desc" title={server.description}>{server.description}</div>
          )}
          <div className="card-meta">
            <span className={`pill ${statusPillClass}`} title={statusText}>
              <span className="pill-dot" />
              {statusText}
            </span>
            {toolCount != null && toolCount > 0 && (
              <span className="pill accent">{toolCount} {t('mcp.tools')}</span>
            )}
            {hasUrl ? (
              <span className="pill remote"><span className="pill-dot" />REMOTE</span>
            ) : hasCommand ? (
              <span className="pill local"><span className="pill-dot" />LOCAL</span>
            ) : null}
            {hasCommand && (
              <span className="pill muted mono" title={commandDisplay}>{commandDisplay}</span>
            )}
          </div>
        </div>
        <div className="card-actions" onClick={(e) => e.stopPropagation()}>
          <button
            className="act-btn"
            onClick={(e) => { e.stopPropagation(); onEdit(); }}
            title={t('chat.editConfig')}
          >
            <EditIcon size={16} />
          </button>
          <button
            className="act-btn"
            onClick={(e) => { e.stopPropagation(); onCopy(); }}
            title={t('chat.copyConfig')}
          >
            <CopyIcon size={16} />
          </button>
          <button
            className="act-btn danger"
            onClick={(e) => { e.stopPropagation(); onDelete(); }}
            title={t('chat.deleteServer')}
          >
            <TrashIcon size={16} />
          </button>
          <span className="act-divider" />
          <label className="toggle-switch">
            <input
              type="checkbox"
              checked={enabled}
              onChange={(e) => onToggleServer(e.target.checked)}
            />
            <span className="toggle-slider"></span>
          </label>
        </div>
      </div>

      {/* 展开内容 */}
      {isExpanded && (
        <div className="card-expand">
          <div className="expand-grid">
            {statusInfo?.serverInfo && (
              <>
                <span className="k">{t('mcp.serverVersion')}</span>
                <span className="v">{statusInfo.serverInfo.name} v{statusInfo.serverInfo.version}</span>
              </>
            )}
            {hasCommand && (
              <>
                <span className="k">{t('mcp.command')}</span>
                <span className="v code">{commandDisplay}</span>
              </>
            )}
            {hasUrl && (
              <>
                <span className="k">{t('mcp.url')}</span>
                <span className="v code">{server.server.url}</span>
              </>
            )}
          </div>

          {/* 工具 chip 流(替换原侧边栏布局) */}
          <ServerToolsPanel
            toolsInfo={toolsInfo}
            isConnected={isConnected}
            isCodexMode={isCodexMode}
            t={t}
            onLoadTools={onLoadTools}
            onToolHover={onToolHover}
          />

          {server.tags && server.tags.length > 0 && (
            <div className="expand-tags">
              {server.tags.map(tag => (
                <span key={tag} className="pill muted">{tag}</span>
              ))}
            </div>
          )}

          {(server.homepage || server.docs) && (
            <div className="expand-actions">
              {server.homepage && (
                <button
                  className="btn-ghost"
                  onClick={() => onCopyUrl(server.homepage!)}
                  title={t('chat.copyHomepageLink')}
                >
                  <HomeIcon size={16} />
                  {t('mcp.homepage')}
                </button>
              )}
              {server.docs && (
                <button
                  className="btn-ghost"
                  onClick={() => onCopyUrl(server.docs!)}
                  title={t('chat.copyDocsLink')}
                >
                  <BookIcon size={16} />
                  {t('mcp.docs')}
                </button>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
