import { useState, memo } from 'react';
import { useTranslation } from 'react-i18next';
import type { ToolInput, ToolResultBlock } from '../../types';
import { useIsToolDenied } from '../../hooks/useIsToolDenied';
import { ToolBlockShell } from './ToolBlockShell';
import { TerminalIcon, XCircleIcon } from '../Icons';

const TASK_CONTENT_WRAPPER_STYLE: React.CSSProperties = {
  paddingLeft: '40px',
  position: 'relative',
  zIndex: 1,
};
const ERROR_ICON_STYLE: React.CSSProperties = { fontSize: '14px', marginTop: '1px' };

interface BashToolBlockProps {
  name?: string;
  input?: ToolInput;
  result?: ToolResultBlock | null;
  /** Unique ID of the tool call, used to determine if the user denied permission */
  toolId?: string;
}

const BashToolBlock = memo(function BashToolBlock({ input, result, toolId }: BashToolBlockProps) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  // Keep all hook(-shaped) calls above the early return below so the hook
  // order stays stable while input streams in.
  const isDenied = useIsToolDenied(toolId);

  if (!input) {
    return null;
  }

  const command = typeof input.command === 'string' ? input.command.trim() : '';
  const description = typeof input.description === 'string' ? input.description.trim() : '';
  if (!command && !description) {
    return null;
  }

  // Determine tool call status based on result
  // If denied, treat as completed (show error state)
  const isCompleted = (result !== undefined && result !== null) || isDenied;
  // If denied, show as error state
  const isError = isDenied || (isCompleted && result?.is_error === true);

  let output = '';

  if (result) {
    const content = result.content;
    if (typeof content === 'string') {
      output = content;
    } else if (Array.isArray(content)) {
      output = content.map((block) => block.text ?? '').join('\n');
    }
  }

  const titleContent = (
    <>
      <TerminalIcon size={16} className="bash-tool-icon" />
      <span className="bash-tool-title">{t('tools.runCommand')}</span>
      <span className="bash-tool-description">{description}</span>
    </>
  );

  return (
    <ToolBlockShell
      expanded={expanded}
      onToggle={() => setExpanded((prev) => !prev)}
      isCompleted={isCompleted}
      isError={isError}
      titleContent={titleContent}
      headerClassName="bash-tool-header"
    >
      <div className="bash-tool-content">
        <div className="bash-tool-line" />
        <div className="task-content-wrapper" style={TASK_CONTENT_WRAPPER_STYLE}>
          <div className="bash-command-block">{command}</div>

          {output && (
            <div className={`bash-output-block ${isError ? 'error' : 'normal'}`}>
              {isError && <XCircleIcon size={16} style={ERROR_ICON_STYLE} />}
              <span className="bash-output-text">{output}</span>
            </div>
          )}
        </div>
      </div>
    </ToolBlockShell>
  );
});

export default BashToolBlock;
