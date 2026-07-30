/**
 * Server Tools Panel Component
 * 按设计稿改为 chip 流式布局(替换原侧边栏),hover 触发 onToolHover 弹参数 tooltip。
 * 保留加载/错误/空态分支。
 */

import type { ServerToolsState, McpTool } from './types';
import { getToolIcon } from './utils';
import { SyncIcon, codiconToIcon } from '../Icons';

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
  isCodexMode: boolean;
  t: (key: string, options?: Record<string, unknown>) => string;
  onLoadTools: (forceRefresh: boolean) => void;
  onToolHover: (tool: McpTool | null, position?: { x: number; y: number }) => void;
}) {
  const toolCount = toolsInfo?.tools?.length ?? 0;

  return (
    <>
      <div className="tools-head">
        <span className="expand-section-label">
          {t('mcp.tools')}{toolsInfo?.tools ? ` (${toolCount})` : ''}
        </span>
        <span className="tools-actions">
          {toolsInfo && !toolsInfo.loading && (
            <button
              className="act-btn"
              onClick={(e) => { e.stopPropagation(); onLoadTools(true); }}
              title={t('mcp.logs.forceRefreshTools')}
            >
              <SyncIcon size={14} />
            </button>
          )}
          {toolsInfo?.loading && (
            <span className="codicon codicon-loading codicon-modifier-spin"></span>
          )}
        </span>
      </div>

      <div className="tool-chips">
        {!isConnected && !toolsInfo && (
          <span className="tool-empty">{t('mcp.notConnected')}</span>
        )}
        {toolsInfo?.error && (
          <span className="tool-empty err">{t('mcp.loadFailed')}</span>
        )}
        {toolsInfo?.tools && toolCount === 0 && (
          <span className="tool-empty">{t('mcp.noTools')}</span>
        )}
        {isConnected && !toolsInfo && (
          <button
            className="tool-chip clickable"
            onClick={(e) => { e.stopPropagation(); onLoadTools(false); }}
          >
            {t('mcp.clickToLoad')}
          </button>
        )}
        {toolsInfo?.tools?.map((tool, index) => (
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
