import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { AlertIcon, CloseIcon, RefreshIcon, ServerIcon, ToolsIcon } from './Icons';
import type {
  SessionCapabilities,
  SessionMcpCapability,
  SessionSkillCapability,
} from '../hooks/useSessionCapabilities';

export interface SessionCapabilitiesDrawerProps {
  open: boolean;
  data: SessionCapabilities | null;
  loading: boolean;
  error: boolean;
  onClose: () => void;
  onRefresh: () => void;
}

function CapabilityState({ state }: { state: string }) {
  return (
    <span className={`session-capability-state is-${state || 'unknown'}`}>
      <span className="session-capability-state-dot" aria-hidden="true" />
      {state || 'unknown'}
    </span>
  );
}

function McpItem({ item }: { item: SessionMcpCapability }) {
  return (
    <li className="session-capability-item">
      <span className="session-capability-item-icon is-mcp" aria-hidden="true">
        <ServerIcon size={14} />
      </span>
      <span className="session-capability-item-main">
        <strong>{item.name || item.id}</strong>
        <span>{item.provider || item.state}</span>
      </span>
      <CapabilityState state={item.state} />
    </li>
  );
}

function SkillItem({ item }: { item: SessionSkillCapability }) {
  return (
    <li className="session-capability-item">
      <span className="session-capability-item-icon is-skill" aria-hidden="true">
        <ToolsIcon size={14} />
      </span>
      <span className="session-capability-item-main">
        <strong>{item.name || item.id}</strong>
        <span>{item.scope || item.source}</span>
      </span>
      <CapabilityState state={item.state} />
    </li>
  );
}

function CapabilityGroup<T>({
  title,
  count,
  items,
  emptyLabel,
  renderItem,
}: {
  title: string;
  count: number;
  items: T[];
  emptyLabel: string;
  renderItem: (item: T) => React.ReactNode;
}) {
  return (
    <section className="session-capability-group">
      <div className="session-capability-group-heading">
        <h3>{title}</h3>
        <span>{count}</span>
      </div>
      {items.length > 0 ? (
        <ul className="session-capability-list">{items.map(renderItem)}</ul>
      ) : (
        <p className="session-capability-empty">{emptyLabel}</p>
      )}
    </section>
  );
}

export function SessionCapabilitiesDrawer({
  open,
  data,
  loading,
  error,
  onClose,
  onRefresh,
}: SessionCapabilitiesDrawerProps): React.ReactElement | null {
  const { t } = useTranslation();

  useEffect(() => {
    if (!open) return undefined;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onClose, open]);

  if (!open) return null;

  const mcp = data?.mcp ?? [];
  const skills = data?.skills ?? [];
  const total = mcp.length + skills.length;

  return (
    <>
      <button
        type="button"
        className="session-capabilities-backdrop"
        aria-label={t('chat.sessionCapabilities.dismiss', { defaultValue: 'Dismiss capabilities' })}
        onClick={onClose}
      />
      <aside
        className="session-capabilities-drawer"
        aria-label={t('chat.sessionCapabilities.title', { defaultValue: 'Session capabilities' })}
      >
        <header className="session-capabilities-drawer-header">
          <div>
            <div className="session-capabilities-drawer-title">
              <span className="session-capabilities-drawer-title-icon" aria-hidden="true">
                <ToolsIcon size={16} />
              </span>
              <strong>
                {t('chat.sessionCapabilities.title', { defaultValue: 'Session capabilities' })}
              </strong>
            </div>
            <p>
              {loading
                ? t('chat.sessionCapabilities.loading', { defaultValue: 'Refreshing snapshot…' })
                : t('chat.sessionCapabilities.summary', {
                    defaultValue: '{{count}} capabilities visible to this session',
                    count: total,
                  })}
            </p>
          </div>
          <button
            type="button"
            className="icon-button session-capabilities-close"
            onClick={onClose}
            aria-label={t('chat.sessionCapabilities.close', { defaultValue: 'Close capabilities' })}
          >
            <CloseIcon size={16} />
          </button>
        </header>

        <div className="session-capabilities-drawer-body">
          {error && (
            <div className="session-capabilities-notice is-error" role="alert">
              <AlertIcon size={14} />
              <span>
                {t('chat.sessionCapabilities.error', {
                  defaultValue: 'Unable to load session capabilities.',
                })}
              </span>
            </div>
          )}
          {data?.mcpError && (
            <div className="session-capabilities-notice" role="status">
              <AlertIcon size={14} />
              <span>{data.mcpError}</span>
            </div>
          )}
          {!data && loading && (
            <div className="session-capabilities-loading" aria-live="polite">
              <span className="session-capabilities-spinner" aria-hidden="true" />
              {t('chat.sessionCapabilities.loading', { defaultValue: 'Refreshing snapshot…' })}
            </div>
          )}
          {data && (
            <>
              <div className="session-capabilities-summary-grid">
                <div>
                  <strong>{mcp.length}</strong>
                  <span>{t('chat.sessionCapabilities.mcp', { defaultValue: 'MCP servers' })}</span>
                </div>
                <div>
                  <strong>{skills.length}</strong>
                  <span>{t('chat.sessionCapabilities.skills', { defaultValue: 'Skills' })}</span>
                </div>
              </div>
              <CapabilityGroup<SessionMcpCapability>
                title={t('chat.sessionCapabilities.mcp', { defaultValue: 'MCP servers' })}
                count={mcp.length}
                items={mcp}
                emptyLabel={t('chat.sessionCapabilities.emptyMcp', {
                  defaultValue: 'No MCP servers observed.',
                })}
                renderItem={(item) => <McpItem key={item.id} item={item} />}
              />
              <CapabilityGroup<SessionSkillCapability>
                title={t('chat.sessionCapabilities.skills', { defaultValue: 'Skills' })}
                count={skills.length}
                items={skills}
                emptyLabel={t('chat.sessionCapabilities.emptySkills', {
                  defaultValue: 'No skills observed.',
                })}
                renderItem={(item) => <SkillItem key={item.id} item={item} />}
              />
            </>
          )}
        </div>

        <footer className="session-capabilities-drawer-footer">
          <span>
            {data?.observedAt
              ? t('chat.sessionCapabilities.updatedAt', {
                  defaultValue: 'Updated {{time}}',
                  time: new Date(data.observedAt).toLocaleTimeString(),
                })
              : t('chat.sessionCapabilities.notUpdated', { defaultValue: 'Not updated yet' })}
          </span>
          <button
            type="button"
            className="session-capabilities-refresh"
            onClick={onRefresh}
            disabled={loading}
          >
            <RefreshIcon size={14} />
            {t('chat.sessionCapabilities.refresh', { defaultValue: 'Refresh' })}
          </button>
        </footer>
      </aside>
    </>
  );
}
