import { memo, useMemo, useState } from 'react';
import type { MessageBlockToolStatus } from '../../generated/protocol';
import type { ToolInput, ToolResultBlock } from '../../types';
import { formatParamValue, truncate } from '../../utils/helpers';
import { parseMcpToolName } from '../../utils/toolConstants';
import { isToolLifecycleTerminal } from '../../utils/toolLifecycle';
import { ToolBlockShell } from './ToolBlockShell';
import { ServerIcon } from '../Icons';

interface McpToolBlockProps {
  name?: string;
  input?: ToolInput;
  result?: ToolResultBlock | null;
  toolStatus?: MessageBlockToolStatus;
}

function extractResultText(result?: ToolResultBlock | null): string {
  if (!result) return '';
  if (typeof result.content === 'string') return result.content;
  if (Array.isArray(result.content)) {
    return result.content
      .map((item) => (item && typeof item.text === 'string' ? item.text : ''))
      .filter(Boolean)
      .join('\n');
  }
  return '';
}

const McpToolBlock = memo(function McpToolBlock({ name, input, result, toolStatus }: McpToolBlockProps) {
  const [expanded, setExpanded] = useState(false);
  const mcp = parseMcpToolName(name);
  const resultText = useMemo(() => extractResultText(result), [result]);

  if (!mcp) {
    return null;
  }

  const isCompleted = isToolLifecycleTerminal(toolStatus, result);
  const isError = isCompleted && result?.is_error === true;
  const titleContent = (
    <>
      <ServerIcon size={16} className="tool-title-icon" />
      <span className="tool-title-text">MCP: {mcp.server}.{mcp.tool}</span>
      <span className="tool-meta-badge">mcp</span>
      <span className="tool-meta-badge">{mcp.server}</span>
      {!expanded && resultText && (
        <span className="task-summary-text tool-title-summary" title={resultText}>
          {truncate(resultText, 72)}
        </span>
      )}
    </>
  );

  return (
    <ToolBlockShell
      expanded={expanded}
      onToggle={() => setExpanded((prev) => !prev)}
      isCompleted={isCompleted}
      isError={isError}
      titleContent={titleContent}
      className="mcp-tool-card"
    >
      <div className="task-details">
        <div className="task-content-wrapper">
          <div className="task-field">
            <div className="task-field-label">server</div>
            <div className="task-field-content">{mcp.server}</div>
          </div>
          <div className="task-field">
            <div className="task-field-label">tool</div>
            <div className="task-field-content">{mcp.tool}</div>
          </div>
          {Object.entries(input ?? {}).map(([key, value]) => (
            <div key={key} className="task-field">
              <div className="task-field-label">{key}</div>
              <div className="task-field-content">{formatParamValue(value)}</div>
            </div>
          ))}
          {resultText && (
            <div className="task-field">
              <div className="task-field-label">result</div>
              <div className="task-field-content">{resultText}</div>
            </div>
          )}
        </div>
      </div>
    </ToolBlockShell>
  );
});

export default McpToolBlock;
