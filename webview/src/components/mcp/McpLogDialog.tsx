import { useTranslation } from 'react-i18next';
import type { McpLogEntry } from '../../types/mcp';
import { ClearAllIcon, LogIcon, codiconToIcon } from '../Icons';
import { BaseDialog, DialogHeader, DialogBody, DialogFooter } from '../shared/BaseDialog';
import { ClickSpark } from '../react-bits';

function getLevelColorStyle(color: string): React.CSSProperties {
  return { color };
}

interface McpLogDialogProps {
  logs: McpLogEntry[];
  onClose: () => void;
  onClear: () => void;
}

export function McpLogDialog({ logs, onClose, onClear }: McpLogDialogProps) {
  const { t } = useTranslation();

  const formatTimestamp = (date: Date): string => {
    return date.toLocaleTimeString(undefined, {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
  };

  const getLevelIcon = (level: McpLogEntry['level']): string => {
    switch (level) {
      case 'success':
        return 'codicon-check';
      case 'error':
        return 'codicon-error';
      case 'warn':
        return 'codicon-warning';
      case 'info':
      default:
        return 'codicon-info';
    }
  };

  const getLevelColor = (level: McpLogEntry['level']): string => {
    switch (level) {
      case 'success':
        return '#10B981';
      case 'error':
        return '#EF4444';
      case 'warn':
        return '#F59E0B';
      case 'info':
      default:
        return '#6B7280';
    }
  };

  return (
    <BaseDialog isOpen onClose={onClose} animation="pop" size="lg">
      <DialogHeader
        title={t('mcp.logs.title')}
        icon={<LogIcon size={16} />}
        onClose={onClose}
      >
        {logs.length > 0 && (
          <button
            className="clear-btn"
            onClick={onClear}
            title={t('mcp.logs.clear')}
            type="button"
          >
            <ClearAllIcon size={16} />
          </button>
        )}
      </DialogHeader>
      <DialogBody>
        {logs.length === 0 ? (
          <div className="empty-logs">
            <LogIcon size={16} />
            <p>{t('mcp.logs.empty')}</p>
          </div>
        ) : (
          <div className="log-list">
            {logs.map((log) => (
              <div key={log.id} className={`log-entry log-${log.level}`}>
                <span className="log-time">{formatTimestamp(log.timestamp)}</span>
                {codiconToIcon(getLevelIcon(log.level), 16, {
                  className: 'log-level',
                  style: getLevelColorStyle(getLevelColor(log.level)),
                })}
                <span className="log-server">[{log.serverName}]</span>
                <span className="log-message">{log.message}</span>
              </div>
            ))}
          </div>
        )}
      </DialogBody>
      <DialogFooter>
        <span className="log-count">
          {t('mcp.logs.count', { count: logs.length })}
        </span>
        <ClickSpark>
          <button className="btn btn-primary" onClick={onClose}>
            {t('mcp.logs.close')}
          </button>
        </ClickSpark>
      </DialogFooter>
    </BaseDialog>
  );
}
