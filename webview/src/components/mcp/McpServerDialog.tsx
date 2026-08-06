import { useState, useEffect, useRef, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import type { McpServer, McpServerSpec } from '../../types/mcp';
import { InfoIcon, XCircleIcon } from '../Icons';
import { UnifiedLoader } from '../UnifiedLoader';
import { BaseDialog, DialogHeader, DialogBody, DialogFooter } from '../shared/BaseDialog';
import { ClickSpark } from '../react-bits';

interface McpServerDialogProps {
  server?: McpServer | null;
  existingIds?: string[];
  currentProvider?: 'claude' | 'codex' | string;
  isPreset?: boolean;
  onClose: () => void;
  onSave: (server: McpServer) => void;
}

export function McpServerDialog({ server, existingIds = [], currentProvider = 'claude', isPreset = false, onClose, onSave }: McpServerDialogProps) {
  const { t } = useTranslation();
  const isCodexMode = currentProvider === 'codex';
  const [saving, setSaving] = useState(false);
  const [jsonContent, setJsonContent] = useState('');
  const [parseError, setParseError] = useState('');
  const editorRef = useRef<HTMLTextAreaElement>(null);

  const claudePlaceholder = `// demo:
// {
//   "mcpServers": {
//     "example-server": {
//       "command": "npx",
//       "args": [
//         "-y",
//         "mcp-server-example"
//       ]
//     }
//   }
// }`;

  const codexPlaceholder = `// Codex MCP Server Example:
// {
//   "mcpServers": {
//     "context7": {
//       "command": "npx",
//       "args": ["-y", "@upstash/context7-mcp"],
//       "env": {
//         "CONTEXT7_API_KEY": "your-api-key"
//       },
//       "startup_timeout_sec": 20,
//       "tool_timeout_sec": 60
//     }
//   }
// }`;

  const placeholder = isCodexMode ? codexPlaceholder : claudePlaceholder;
  const lineCount = Math.max((jsonContent || placeholder).split('\n').length, 12);

  const isValid = useCallback(() => {
    if (!jsonContent.trim()) return false;
    const cleanedContent = jsonContent
      .split('\n')
      .filter(line => !line.trim().startsWith('//'))
      .join('\n');
    if (!cleanedContent.trim()) return false;
    try {
      const parsed = JSON.parse(cleanedContent);
      if (parsed.mcpServers && typeof parsed.mcpServers === 'object') {
        return Object.keys(parsed.mcpServers).length > 0;
      }
      if (parsed.command || parsed.url) {
        return true;
      }
      return false;
    } catch {
      return false;
    }
  }, [jsonContent]);

  const handleInput = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setJsonContent(e.target.value);
    setParseError('');
  };

  const handleTab = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Tab') {
      e.preventDefault();
      const textarea = editorRef.current;
      if (!textarea) return;
      const start = textarea.selectionStart;
      const end = textarea.selectionEnd;
      const value = textarea.value;
      setJsonContent(value.substring(0, start) + '  ' + value.substring(end));
      setTimeout(() => {
        textarea.selectionStart = textarea.selectionEnd = start + 2;
      }, 0);
    }
  };

  const parseConfig = (): McpServer[] | null => {
    try {
      const cleanedContent = jsonContent
        .split('\n')
        .filter(line => !line.trim().startsWith('//'))
        .join('\n');
      const parsed = JSON.parse(cleanedContent);
      const servers: McpServer[] = [];
      if (parsed.mcpServers && typeof parsed.mcpServers === 'object') {
        for (const [id, config] of Object.entries(parsed.mcpServers)) {
          if ((!server || isPreset) && existingIds.includes(id)) {
            setParseError(t('mcp.serverDialog.errors.idExists', { id }));
            return null;
          }
          const serverConfig = config as any;
          const serverSpec = {
            ...serverConfig,
            type: serverConfig.type || (serverConfig.command ? 'stdio' : serverConfig.url ? 'http' : 'stdio'),
          };
          delete serverSpec.name;
          servers.push({
            id,
            name: serverConfig.name || id,
            server: serverSpec as McpServerSpec,
            apps: { claude: !isCodexMode, codex: isCodexMode, gemini: false },
            enabled: true,
          });
        }
      } else if (parsed.command || parsed.url) {
        const id = `server-${Date.now()}`;
        const serverSpec = { ...parsed, type: parsed.type || (parsed.command ? 'stdio' : 'http') };
        delete serverSpec.name;
        servers.push({
          id,
          name: parsed.name || id,
          server: serverSpec as McpServerSpec,
          apps: { claude: !isCodexMode, codex: isCodexMode, gemini: false },
          enabled: true,
        });
      }
      if (servers.length === 0) {
        setParseError(t('mcp.serverDialog.errors.unrecognizedFormat'));
        return null;
      }
      return servers;
    } catch (e) {
      setParseError(t('mcp.serverDialog.errors.jsonParseError', { message: (e as Error).message }));
      return null;
    }
  };

  const handleConfirm = async () => {
    const servers = parseConfig();
    if (!servers) return;
    setSaving(true);
    try {
      for (const srv of servers) {
        onSave(srv);
      }
      onClose();
    } finally {
      setSaving(false);
    }
  };

  useEffect(() => {
    if (server) {
      const config: any = { mcpServers: { [server.id]: { ...server.server } } };
      setJsonContent(JSON.stringify(config, null, 2));
    }
  }, [server]);

  return (
    <BaseDialog isOpen onClose={onClose} animation="pop" size="lg">
      <DialogHeader
        title={server && !isPreset ? t('mcp.serverDialog.editTitle') : t('mcp.serverDialog.addTitle')}
        onClose={onClose}
      >
        <button className="mode-btn active">
          {t('mcp.serverDialog.rawConfig')}
        </button>
      </DialogHeader>
      <DialogBody>
        <p className="dialog-desc">
          {t('mcp.serverDialog.description')}
        </p>
        <div className="json-editor">
          <div className="line-numbers">
            {Array.from({ length: lineCount }, (_, i) => (
              <div key={i + 1} className="line-num">{i + 1}</div>
            ))}
          </div>
          <textarea
            ref={editorRef}
            value={jsonContent}
            className="json-textarea"
            placeholder={placeholder}
            spellCheck="false"
            onChange={handleInput}
            onKeyDown={handleTab}
          />
        </div>
        {parseError && (
          <div className="error-message">
            <XCircleIcon size={16} />
            {parseError}
          </div>
        )}
      </DialogBody>
      <DialogFooter>
        <div className="footer-hint">
          <InfoIcon size={16} />
          {t('mcp.serverDialog.securityWarning')}
        </div>
        <button className="btn btn-secondary" onClick={onClose}>{t('common.cancel')}</button>
        <ClickSpark>
          <button
            className="btn btn-primary"
            onClick={handleConfirm}
            disabled={!isValid() || saving}
          >
            {saving && <UnifiedLoader type="pulse" size={14} />}
            {saving ? t('mcp.serverDialog.saving') : t('common.confirm')}
          </button>
        </ClickSpark>
      </DialogFooter>
    </BaseDialog>
  );
}
