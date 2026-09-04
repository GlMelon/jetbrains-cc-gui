/**
 * Server Tools Panel Component
 * 按设计稿改为 chip 流式布局(替换原侧边栏),hover 触发 onToolHover 弹参数 tooltip。
 * 保留加载/错误/空态分支。
 */

import type { ServerToolsState, McpTool } from './types';
import { getToolIcon } from './utils';
import { SyncIcon, codiconToIcon } from '../Icons';
import { UnifiedLoader } from '../UnifiedLoader';

const WARNING_HEADER_STYLE: React.CSSProperties = { color: 'var(--color-warning)' };

/**
 * Server Tools Panel — chip 流式工具列表
 */
export function ServerToolsPanel({
  toolsInfo,
  isConnected,
  t,
  onLoadTools,
  onToolHover,
}: {
  toolsInfo?: ServerToolsState[string];
  isConnected: boolean;
  t: (key: string, options?: Record<string, unknown>) => string;
  onLoadTools: (forceRefresh: boolean) => void;
  onToolHover: (tool: McpTool | null, position?: { x: number; y: number }) => void;
}) {
  // Tool results are only meaningful while the server is connected. Keeping a
  // stale empty result visible after a disconnect makes the panel report
  // "no tools" instead of the actual connection state.
  const visibleToolsInfo = isConnected ? toolsInfo : undefined;
  const emptyToolsResult = isEmptyToolsResult(visibleToolsInfo);
  const toolCount = visibleToolsInfo?.tools?.length ?? 0;

  return (
    <>
      <div className="tools-head">
        <span className="expand-section-label">
          {t('mcp.tools')}{visibleToolsInfo?.tools ? ` (${toolCount})` : ''}
        </span>
        <span className="tools-actions">
          {visibleToolsInfo && !visibleToolsInfo.loading && (
            <button
              className="act-btn"
              onClick={(e) => { e.stopPropagation(); onLoadTools(true); }}
              title={t('mcp.logs.forceRefreshTools')}
            >
              <SyncIcon size={14} />
            </button>
          )}
          {visibleToolsInfo?.loading && (
            <UnifiedLoader type="bounce" size={14} />
          )}
        </span>
      </div>

      <div className="tool-chips">
        {!isConnected && (
          <span className="tool-empty">{t('mcp.notConnected')}</span>
        )}
        {visibleToolsInfo?.error && (
          <span className="tool-empty err">{t('mcp.loadFailed')}</span>
        )}
        {emptyToolsResult && (
          <span className="tool-empty" style={WARNING_HEADER_STYLE}>{t('mcp.noTools')}</span>
        )}
        {isConnected && !visibleToolsInfo && (
          <button
            className="tool-chip clickable"
            onClick={(e) => { e.stopPropagation(); onLoadTools(false); }}
          >
            {t('mcp.clickToLoad')}
          </button>
        )}
        {visibleToolsInfo?.tools?.map((tool, index) => (
          <span
            key={index}
            className="tool-chip"
            title={tool.description || tool.name}
            onMouseEnter={(e) => {
              const rect = e.currentTarget.getBoundingClientRect();
              onToolHover(tool, { x: rect.right + 8, y: rect.top });
            }}
            onMouseLeave={() => { onToolHover(null); }}
          >
            {codiconToIcon(getToolIcon(tool.name), 14, { className: 'tool-chip-icon' })}
            {tool.name}
          </span>
        ))}
      </div>
    </>
  );
}

export function isEmptyToolsResult(toolsInfo: ServerToolsState[string] | undefined): boolean {
  return toolsInfo != null
    && !toolsInfo.loading
    && !toolsInfo.error
    && toolsInfo.tools.length === 0;
}
