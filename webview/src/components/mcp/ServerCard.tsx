/**
 * Server Card Component
 * Displays information, status, and actions for a single MCP server
 */

import type { McpServer, McpServerStatusInfo } from '../../types/mcp';
import type { ServerRefreshState, ServerToolsState, McpTool } from './types';
import { getServerStatusInfo, getStatusIcon, getStatusColor, getStatusText, getIconColor, getServerInitial, isServerEnabled } from './utils';
import { ServerToolsPanel } from './ServerToolsPanel';
import { BookIcon, ChevronDownIcon, ChevronRightIcon, CopyIcon, EditIcon, HomeIcon, TrashIcon, codiconToIcon } from '../Icons';

export interface ServerCardProps {
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
}: ServerCardProps) {
  const statusInfo = getServerStatusInfo(server, serverStatus);
  const status = statusInfo?.status;
  const effectiveStatus: McpServerStatusInfo['status'] | undefined =
    status === 'pending' && (toolsInfo?.tools?.length ?? 0) > 0
      ? 'connected'
      : status;
  const enabled = isServerEnabled(server, isCodexMode);
  const isConnected = effectiveStatus === 'connected';

  const iconStyle: React.CSSProperties = { background: getIconColor(server.id) };
  const statusColorStyle: React.CSSProperties = { color: getStatusColor(server, effectiveStatus, isCodexMode) };

  return (
    <div
      className={`server-card ${isExpanded ? 'expanded' : ''} ${!enabled ? 'disabled' : ''}`}
    >
      {/* Card header */}
      <div className="card-header" onClick={onToggleExpand}>
        <div className="header-left-section">
          {isExpanded ? <ChevronDownIcon size={16} className="expand-icon" /> : <ChevronRightIcon size={16} className="expand-icon" />}
          <div className="server-icon" style={iconStyle}>
            {getServerInitial(server)}
          </div>
          <span className="server-name">{server.name || server.id}</span>
          {/* Connection status indicator */}
          <span
            className="status-indicator"
            style={statusColorStyle}
            title={getStatusText(server, effectiveStatus, isCodexMode, t)}
          >
            {codiconToIcon(getStatusIcon(server, effectiveStatus, isCodexMode), 16)}
          </span>
        </div>
        <div className="header-right-section" onClick={(e) => e.stopPropagation()}>
          {/* Edit button */}
          <button
            className="icon-btn edit-btn"
            onClick={(e) => {
              e.stopPropagation();
              onEdit();
            }}
            title={t('chat.editConfig')}
          >
            <EditIcon size={16} />
          </button>
          {/* Copy button */}
          <button
            className="icon-btn copy-btn"
            onClick={(e) => {
              e.stopPropagation();
              onCopy();
            }}
            title={t('chat.copyConfig')}
          >
            <CopyIcon size={16} />
          </button>
          {/* Delete button */}
          <button
            className="icon-btn delete-btn"
            onClick={(e) => {
              e.stopPropagation();
              onDelete();
            }}
            title={t('chat.deleteServer')}
          >
            <TrashIcon size={16} />
          </button>
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

      {/* Expanded content */}
      {isExpanded && (
        <div className="card-content">
          {/* Connection status info */}
          <div className="status-section">
            <div className="info-row">
              <span className="info-label">{t('mcp.connectionStatus')}:</span>
              <span
                className="info-value status-value"
                style={statusColorStyle}
              >
                {codiconToIcon(getStatusIcon(server, effectiveStatus, isCodexMode), 16)}
                {' '}{getStatusText(server, effectiveStatus, isCodexMode, t)}
              </span>
            </div>
            {statusInfo?.serverInfo && (
              <div className="info-row">
                <span className="info-label">{t('mcp.serverVersion')}:</span>
                <span className="info-value">
                  {statusInfo.serverInfo.name} v{statusInfo.serverInfo.version}
                </span>
              </div>
            )}
          </div>

          {/* Server info */}
          <div className="info-section">
            {server.description && (
              <div className="info-row">
                <span className="info-label">{t('mcp.description')}:</span>
                <span className="info-value">{server.description}</span>
              </div>
            )}
            {server.server.command && (
              <div className="info-row">
                <span className="info-label">{t('mcp.command')}:</span>
                <code className="info-value command">
                  {server.server.command} {(server.server.args || []).join(' ')}
                </code>
              </div>
            )}
            {server.server.url && (
              <div className="info-row">
                <span className="info-label">{t('mcp.url')}:</span>
                <code className="info-value command">{server.server.url}</code>
              </div>
            )}
          </div>

          {/* Tools list panel */}
          <ServerToolsPanel
            toolsInfo={toolsInfo}
            isConnected={isConnected}
            isCodexMode={isCodexMode}
            t={t}
            onLoadTools={onLoadTools}
            onToolHover={onToolHover}
          />

          {/* Tags */}
          {server.tags && server.tags.length > 0 && (
            <div className="tags-section">
              {server.tags.map(tag => (
                <span key={tag} className="tag">{tag}</span>
              ))}
            </div>
          )}

          {/* Action buttons */}
          <div className="actions-section">
            {server.homepage && (
              <button
                className="action-btn"
                onClick={() => onCopyUrl(server.homepage!)}
                title={t('chat.copyHomepageLink')}
              >
                <HomeIcon size={16} />
                {t('mcp.homepage')}
              </button>
            )}
            {server.docs && (
              <button
                className="action-btn"
                onClick={() => onCopyUrl(server.docs!)}
                title={t('chat.copyDocsLink')}
              >
                <BookIcon size={16} />
                {t('mcp.docs')}
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
