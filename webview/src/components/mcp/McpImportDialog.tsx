import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { McpImportPreviewResponse, McpServer } from '../../types/mcp';
import { sendAction, subscribeEvent } from '../../bridge/typed';
import { UPSTREAM, DOWNSTREAM, MCP_TRANSPORT } from '../../generated/protocol';
import { UnifiedLoader } from '../UnifiedLoader';
import { BaseDialog, DialogHeader, DialogBody, DialogFooter } from '../shared/BaseDialog';
import { ClickSpark } from '../react-bits';

interface McpImportDialogProps {
  currentProvider?: 'claude' | 'codex' | string;
  existingIds?: string[];
  onClose: () => void;
  onImport: (servers: McpServer[]) => void;
}

interface PreviewItem {
  server: McpServer;
  originalId: string;
  finalId: string;
  renamed: boolean;
}

function uniqueId(baseId: string, taken: Set<string>): string {
  if (!taken.has(baseId)) {
    return baseId;
  }
  let counter = 2;
  while (taken.has(`${baseId}-${counter}`)) {
    counter++;
  }
  return `${baseId}-${counter}`;
}

export function McpImportDialog({ currentProvider = 'claude', existingIds = [], onClose, onImport }: McpImportDialogProps) {
  const { t } = useTranslation();
  const isCodexMode = currentProvider === 'codex';
  const [jsonContent, setJsonContent] = useState('');
  const [preview, setPreview] = useState<PreviewItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const buildPreview = useCallback((servers: McpServer[]): PreviewItem[] => {
    const taken = new Set<string>(existingIds);
    return servers.map(server => {
      const originalId = server.id;
      const finalId = uniqueId(originalId, taken);
      taken.add(finalId);
      return { server, originalId, finalId, renamed: finalId !== originalId };
    });
  }, [existingIds]);

  useEffect(() => {
    const unsubscribe = subscribeEvent<string>(DOWNSTREAM.MCP_IMPORT_PREVIEW, (json) => {
      setLoading(false);
      try {
        const response = JSON.parse(json) as McpImportPreviewResponse;
        if (response.error) {
          setError(response.error);
          setPreview([]);
          return;
        }
        setError(null);
        setPreview(buildPreview(response.servers || []));
      } catch (parseError) {
        setError(String(parseError));
        setPreview([]);
      }
    });
    return unsubscribe;
  }, [buildPreview]);

  const handlePreview = useCallback(() => {
    if (!jsonContent.trim()) {
      return;
    }
    setLoading(true);
    setError(null);
    sendAction(UPSTREAM.PARSE_COPILOT_MCP_CONFIG, { json: jsonContent, isCodexMode });
  }, [jsonContent, isCodexMode]);

  const handleContentChange = (value: string) => {
    setJsonContent(value);
    setPreview([]);
    setError(null);
  };

  const handleConfirm = () => {
    if (preview.length === 0) {
      return;
    }
    onImport(preview.map(item => ({ ...item.server, id: item.finalId })));
    onClose();
  };

  const renamedCount = useMemo(() => preview.filter(item => item.renamed).length, [preview]);

  return (
    <BaseDialog isOpen onClose={onClose} animation="pop" size="lg">
      <DialogHeader title={t('mcp.import.title')} onClose={onClose} />
      <DialogBody className="mcp-import-body">
        <div className="mcp-import-subtitle">{t('mcp.import.description')}</div>
        <textarea
          className="mcp-import-textarea"
          value={jsonContent}
          placeholder={t('mcp.import.placeholder')}
          spellCheck={false}
          onChange={event => handleContentChange(event.target.value)}
        />

        <div className="mcp-import-actions-row">
          <button className="btn btn-secondary" onClick={handlePreview} disabled={!jsonContent.trim() || loading}>
            {loading ? <UnifiedLoader type="spin" size={14} /> : <span className="codicon codicon-eye"></span>}
            {t('mcp.import.previewButton')}
          </button>
          {renamedCount > 0 && (
            <span className="mcp-import-rename-hint">
              <span className="codicon codicon-info"></span>
              {t('mcp.import.renameSummary', { count: renamedCount })}
            </span>
          )}
        </div>

        {error && (
          <div className="mcp-import-error">
            <span className="codicon codicon-warning"></span>
            {error}
          </div>
        )}

        <div className="mcp-import-preview">
          {preview.length === 0 && !error ? (
            <div className="mcp-import-empty">{t('mcp.import.empty')}</div>
          ) : (
            <>
              {preview.length > 0 && <div className="mcp-import-preview-title">{t('mcp.import.previewTitle')}</div>}
              {preview.map(item => (
                <div key={item.finalId} className="mcp-import-item">
                  <div className="mcp-import-item-icon">{(item.server.name || item.finalId).charAt(0).toUpperCase()}</div>
                  <div className="mcp-import-item-info">
                    <div className="mcp-import-item-title-row">
                      <span className="mcp-import-item-name">{item.server.name || item.finalId}</span>
                      <span className="mcp-import-type-badge">{item.server.server?.type || MCP_TRANSPORT.STDIO}</span>
                      {item.renamed && (
                        <span className="mcp-import-renamed">{t('mcp.import.renamedFrom', { id: item.originalId })}</span>
                      )}
                    </div>
                    <div className="mcp-import-item-id">{item.finalId}</div>
                    <pre className="mcp-import-item-spec">{JSON.stringify(item.server.server, null, 2)}</pre>
                  </div>
                </div>
              ))}
            </>
          )}
        </div>
      </DialogBody>
      <DialogFooter>
        <div className="footer-hint">
          <span className="codicon codicon-shield"></span>
          {t('mcp.import.footerHint')}
        </div>
        <button className="btn btn-secondary" onClick={onClose}>{t('mcp.cancel')}</button>
        <ClickSpark>
          <button className="btn btn-primary" onClick={handleConfirm} disabled={preview.length === 0}>
            {t('mcp.import.confirm', { count: preview.length })}
          </button>
        </ClickSpark>
      </DialogFooter>
    </BaseDialog>
  );
}
