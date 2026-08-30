import { useState, memo } from 'react';
import type { MessageBlockToolStatus } from '../../generated/protocol';
import { useTranslation } from 'react-i18next';
import type { ToolInput, ToolResultBlock } from '../../types';
import { useIsToolDenied } from '../../hooks/useIsToolDenied';
import { openFile } from '../../utils/bridge';
import { useResolvedFileLinkTooltip } from '../../hooks/useResolvedFileLinkTooltip';
import { getFileIcon, getFolderIcon } from '../../utils/fileIcons';
import { getToolLineInfo, resolveToolTarget } from '../../utils/toolPresentation';
import { isToolLifecycleTerminal } from '../../utils/toolLifecycle';
import { ToolBlockShell } from './ToolBlockShell';
import { codiconToIcon } from '../Icons';

interface ReadToolBlockProps {
  input?: ToolInput;
  result?: ToolResultBlock | null;
  toolStatus?: MessageBlockToolStatus;
  /** Unique ID of the tool call, used to determine if the user denied permission */
  toolId?: string;
}

const FILE_LINK_STYLE: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
};

const FILE_ICON_STYLE: React.CSSProperties = {
  marginRight: '4px',
  display: 'flex',
  alignItems: 'center',
  width: '16px',
  height: '16px',
};

const LINE_INFO_STYLE: React.CSSProperties = {
  marginLeft: '8px',
  fontSize: '12px',
};

const PARAMS_CONTAINER_STYLE: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: '8px',
  fontFamily: 'var(--idea-editor-font-family, monospace)',
  fontSize: '12px',
};

const PARAM_ROW_STYLE: React.CSSProperties = {
  color: '#858585',
  display: 'flex',
  alignItems: 'baseline',
  overflow: 'hidden',
};

const PARAM_KEY_STYLE: React.CSSProperties = {
  color: '#90caf9',
  fontWeight: 600,
  flexShrink: 0,
};

const PARAM_VALUE_STYLE: React.CSSProperties = {
  overflowX: 'auto',
  whiteSpace: 'nowrap',
  flex: 1,
};

const ReadToolBlock = memo(function ReadToolBlock({ input, result, toolStatus, toolId }: ReadToolBlockProps) {
  const [expanded, setExpanded] = useState(false);
  const { t } = useTranslation();
  const isDenied = useIsToolDenied(toolId);

  // Resolve target defensively so the tooltip hook below can run
  // unconditionally (rules of hooks), even while input is still streaming in.
  const target = input ? resolveToolTarget(input, 'read') : undefined;
  const filePath = target?.rawPath;
  const isDirectory = target?.isDirectory ?? false;

  // NOTE: this hook MUST stay above the early `return null` below — calling a
  // hook conditionally throws "Rendered fewer hooks than expected" when input
  // transitions between defined/undefined during streaming.
  const fileLinkTooltip = useResolvedFileLinkTooltip(
    !isDirectory ? filePath : undefined,
    !isDirectory ? (target?.displayPath || filePath || undefined) : undefined,
  );

  if (!input) {
    return null;
  }

  // Determine tool call status based on result.
  // While the model is still waiting on the read result, the indicator must
  // reflect "pending" (yellow breathing) instead of misleading green "completed".
  const isCompleted = isToolLifecycleTerminal(toolStatus, result) || isDenied;
  const isError = isDenied || (isCompleted && result?.is_error === true);

  const lineInfo = getToolLineInfo(input, target);
  const iconClass = isDirectory ? 'codicon-folder' : 'codicon-file-code';
  const actionText = isDirectory ? t('permission.tools.readDirectory') : t('permission.tools.Read');

  const handleFileClick = (e: React.MouseEvent) => {
    e.stopPropagation(); // Prevent bubbling to avoid triggering expand/collapse
    if (target?.isFile) {
      openFile(target.openPath, lineInfo.start, lineInfo.end);
    }
  };

  const getFileIconSvg = () => {
    if (!target) return '';
    if (isDirectory) {
      return getFolderIcon(target.cleanFileName);
    }
    const extension = target.cleanFileName.includes('.') ? target.cleanFileName.split('.').pop() : '';
    return getFileIcon(extension ?? '', target.cleanFileName);
  };

  // Get all input parameters for the expanded view, excluding Codex-specific fields
  const params = Object.entries(input).filter(([key]) =>
    key !== 'file_path' &&
    key !== 'target_file' &&
    key !== 'path' &&
    key !== 'command' &&    // Omit Codex command field
    key !== 'workdir' &&    // Omit Codex workdir field
    key !== 'description'   // Omit Codex description field
  );

  const titleContent = (
    <>
      {codiconToIcon(iconClass, 16, { className: 'tool-title-icon' })}

      <span className="tool-title-text">
        {actionText}
      </span>
      <span
        className={`tool-title-summary ${!isDirectory ? 'clickable-file' : ''}`}
        onClick={!isDirectory ? handleFileClick : undefined}
        {...(!isDirectory ? fileLinkTooltip : {})}
        style={FILE_LINK_STYLE}
      >
        <span
          style={FILE_ICON_STYLE}
          dangerouslySetInnerHTML={{ __html: getFileIconSvg() }}
        />
        {target?.displayPath || filePath}
      </span>

      {lineInfo.start && (
        <span className="tool-title-summary" style={LINE_INFO_STYLE}>
          {lineInfo.end && lineInfo.end !== lineInfo.start
            ? t('tools.lineRange', { start: lineInfo.start, end: lineInfo.end })
            : t('tools.lineSingle', { line: lineInfo.start })}
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
    >
      {params.length > 0 && (
        <div style={PARAMS_CONTAINER_STYLE}>
          {params.map(([key, value]) => (
            <div key={key} style={PARAM_ROW_STYLE}>
              <span style={PARAM_KEY_STYLE}>{key}：</span>
              <span style={PARAM_VALUE_STYLE}>
                {String(value)}
              </span>
            </div>
          ))}
        </div>
      )}
    </ToolBlockShell>
  );
});

export default ReadToolBlock;
