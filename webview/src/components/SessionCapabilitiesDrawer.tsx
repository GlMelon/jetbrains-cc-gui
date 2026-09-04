import { useEffect, useRef, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { SpinLoader } from './react-bits/SpinLoader';
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
  triggerRef: React.RefObject<HTMLButtonElement | null>;
  onClose: () => void;
  onRefresh: () => void;
}

function capabilityAvailabilityLabel(t: TFunction, value: boolean | null): string {
  if (value === null) {
    return t('chat.sessionCapabilities.availability.unknown', { defaultValue: 'unknown' });
  }
  return value
    ? t('chat.sessionCapabilities.availability.available', { defaultValue: 'available' })
    : t('chat.sessionCapabilities.availability.unavailable', { defaultValue: 'unavailable' });
}

function CapabilityState({ state, t }: { state: string; t: TFunction }) {
  const raw = state || 'unknown';
  return (
    <span className={`session-capability-state is-${raw}`}>
      <span className="session-capability-state-dot" aria-hidden="true" />
      {t(`chat.sessionCapabilities.state.${raw}`, { defaultValue: raw })}
    </span>
  );
}

function McpItem({ item, t }: { item: SessionMcpCapability; t: TFunction }) {
  return (
    <li className="session-capability-item">
      <span className="session-capability-item-icon is-mcp" aria-hidden="true">
        <ServerIcon size={14} />
      </span>
      <span className="session-capability-item-main">
        <strong>{item.name || item.id}</strong>
        <span>{item.provider || item.state}</span>
      </span>
      <CapabilityState state={item.state} t={t} />
    </li>
  );
}

function SkillItem({ item, t }: { item: SessionSkillCapability; t: TFunction }) {
  const scope = item.scope || item.source;
  return (
    <li className="session-capability-item">
      <span className="session-capability-item-icon is-skill" aria-hidden="true">
        <ToolsIcon size={14} />
      </span>
      <span className="session-capability-item-main">
        <strong>{item.name || item.id}</strong>
        <span>{t(`chat.sessionCapabilities.scope.${scope}`, { defaultValue: scope })}</span>
      </span>
      <CapabilityState state={item.state} t={t} />
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
  triggerRef,
  onClose,
  onRefresh,
}: SessionCapabilitiesDrawerProps): React.ReactElement | null {
  const { t } = useTranslation();
  const popoverRef = useRef<HTMLDivElement>(null);

  const updatePosition = useCallback(() => {
    if (!triggerRef.current || !popoverRef.current) return;
    const rect = triggerRef.current.getBoundingClientRect();
    const popover = popoverRef.current;
    const viewportWidth = window.innerWidth;
    const popoverWidth = popover.offsetWidth;

    let left = rect.right - popoverWidth;
    if (left < 8) left = 8;
    if (left + popoverWidth > viewportWidth - 8) left = viewportWidth - popoverWidth - 8;

    popover.style.position = 'fixed';
    popover.style.top = `${rect.bottom + 6}px`;
    popover.style.left = `${left}px`;
  }, [triggerRef]);

  useEffect(() => {
    if (!open) return undefined;

    updatePosition();

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };

    const handlePointerDown = (event: PointerEvent) => {
      const target = event.target as Node;
      if (
        popoverRef.current &&
        !popoverRef.current.contains(target) &&
        triggerRef.current &&
        !triggerRef.current.contains(target)
      ) {
        onClose();
      }
    };

    const handleResize = () => {
      if (!open) return;
      onClose();
    };

    window.addEventListener('keydown', handleKeyDown);
    document.addEventListener('pointerdown', handlePointerDown, true);
    window.addEventListener('resize', handleResize);

    return () => {
      window.removeEventListener('keydown', handleKeyDown);
      document.removeEventListener('pointerdown', handlePointerDown, true);
      window.removeEventListener('resize', handleResize);
    };
  }, [onClose, open, triggerRef, updatePosition]);

  if (!open) return null;

  const mcp = data?.mcp ?? [];
  const skills = data?.skills ?? [];
  const total = mcp.length + skills.length;

  return (
    <div
      ref={popoverRef}
      className="session-capabilities-popover"
      role="dialog"
      aria-label={t('chat.sessionCapabilities.title', { defaultValue: 'Session capabilities' })}
    >
        <header className="session-capabilities-popover-header">
          <div>
            <div className="session-capabilities-popover-title">
              <span className="session-capabilities-popover-title-icon" aria-hidden="true">
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

        <div className="session-capabilities-popover-body">
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
              <SpinLoader size={13} strokeWidth={2} duration={0.8} color="var(--accent-primary)" />
              {t('chat.sessionCapabilities.loading', { defaultValue: 'Refreshing snapshot…' })}
            </div>
          )}
          {data && (
            <>
              <section
                className="session-capabilities-runtime"
                aria-label={t('chat.sessionCapabilities.runtime.ariaLabel', {
                  defaultValue: 'Runtime capabilities',
                })}
              >
                <div className="session-capabilities-runtime-row">
                  <span>
                    {t('chat.sessionCapabilities.runtime.sessionState', {
                      defaultValue: 'Session state',
                    })}
                  </span>
                  <CapabilityState state={data.state} t={t} />
                </div>
                <div className="session-capabilities-runtime-row">
                  <span>
                    {t('chat.sessionCapabilities.runtime.channel', { defaultValue: 'Channel' })}
                  </span>
                  <strong>{data.channel}</strong>
                </div>
                <div className="session-capabilities-runtime-row">
                  <span>
                    {t('chat.sessionCapabilities.runtime.thinking', { defaultValue: 'Thinking' })}
                  </span>
                  <strong>{capabilityAvailabilityLabel(t, data.thinkingAvailable)}</strong>
                </div>
                <div className="session-capabilities-runtime-row">
                  <span>
                    {t('chat.sessionCapabilities.runtime.tools', { defaultValue: 'Tools' })}
                  </span>
                  <strong>{capabilityAvailabilityLabel(t, data.toolsAvailable)}</strong>
                </div>
                <div className="session-capabilities-runtime-row">
                  <span>
                    {t('chat.sessionCapabilities.runtime.sessionMcp', {
                      defaultValue: 'Session MCP',
                    })}
                  </span>
                  <strong>{capabilityAvailabilityLabel(t, data.sessionMcpAvailable)}</strong>
                </div>
                {data.degraded && data.degradationReason && (
                  <div className="session-capabilities-notice" role="status">
                    <AlertIcon size={14} />
                    <span>{data.degradationReason}</span>
                  </div>
                )}
              </section>
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
                renderItem={(item) => <McpItem key={item.id} item={item} t={t} />}
              />
              <CapabilityGroup<SessionSkillCapability>
                title={t('chat.sessionCapabilities.skills', { defaultValue: 'Skills' })}
                count={skills.length}
                items={skills}
                emptyLabel={t('chat.sessionCapabilities.emptySkills', {
                  defaultValue: 'No skills observed.',
                })}
                renderItem={(item) => <SkillItem key={item.id} item={item} t={t} />}
              />
            </>
          )}
        </div>

        <footer className="session-capabilities-popover-footer">
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
      </div>
  );
}
