import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { CheckIcon, ChevronDownIcon, ChevronUpIcon, CommandLineIcon, InfoIcon, KeyIcon } from '../../Icons';;
import {
  SPECIAL_PROVIDER_IDS,
  type CodexProviderConfig,
  type OpenCodeProviderConfig,
  type ProviderConfig,
} from '../../../types/provider';
import { sendAction } from '../../../bridge/typed';
import { UPSTREAM } from '../../../generated/protocol';
import {
  subscribeActiveCodexProvider,
  subscribeActiveOpenCodeProvider,
  subscribeActiveProvider,
  subscribeCodexProviderList,
  subscribeOpenCodeProviderList,
  subscribeProviderList,
} from '../../../utils/runtimeProviderCapabilities';
import { useDropdownPosition } from '../../../hooks/useDropdownPosition';

const DISABLED_OPTION_STYLE: React.CSSProperties = { cursor: 'default' };
const PROVIDER_INFO_STYLE: React.CSSProperties = { display: 'flex', flexDirection: 'column', minWidth: 0, flex: 1 };
const RELATIVE_INLINE_BLOCK_STYLE: React.CSSProperties = { position: 'relative', display: 'inline-block' };
const CHEVRON_ICON_STYLE: React.CSSProperties = { fontSize: '10px', marginLeft: '2px' };

interface RuntimeProviderSelectProps {
  currentProvider: string;
  embedded?: boolean;
  triggerRef?: React.RefObject<HTMLElement | null>;
  onClose?: () => void;
  onProviderSwitched?: (providerName: string) => void;
}

type RuntimeProvider = ProviderConfig | CodexProviderConfig | OpenCodeProviderConfig;

type ProviderKind = 'claude' | 'codex' | 'opencode';

const isProviderKind = (provider: string): provider is ProviderKind =>
  provider === 'claude' || provider === 'codex' || provider === 'opencode';

const parseProviderList = (json: string): RuntimeProvider[] => {
  const parsed = JSON.parse(json);
  return Array.isArray(parsed) ? parsed : [];
};

/**
 * RuntimeProviderSelect - lightweight active-provider switcher for current engine.
 * Claude/Codex/OpenCode 三 provider 对称切换(Principle 6)。
 */
export const RuntimeProviderSelect = ({ currentProvider, embedded = false, triggerRef, onClose, onProviderSwitched }: RuntimeProviderSelectProps) => {
  const { t } = useTranslation();
  const [isOpen, setIsOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [providersByKind, setProvidersByKind] = useState<Record<ProviderKind, RuntimeProvider[]>>({
    claude: [],
    codex: [],
    opencode: [],
  });
  const buttonRef = useRef<HTMLButtonElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const { positionedStyle, maxHeight: viewportMaxHeight, recalculate } = useDropdownPosition({
    buttonRef: (embedded ? triggerRef : buttonRef) as React.RefObject<HTMLElement | null>,
    dropdownRef,
    submenu: embedded,
    isOpen,
    minWidth: embedded ? 260 : 200,
  });

  const providerKind: ProviderKind = currentProvider === 'codex'
    ? 'codex'
    : currentProvider === 'opencode'
      ? 'opencode'
      : 'claude';
  const visibleProviders = providersByKind[providerKind];
  const activeProvider = useMemo(
    () => visibleProviders.find((provider) => provider.isActive),
    [visibleProviders]
  );

  const getProviderDisplayName = useCallback((provider: RuntimeProvider, kind: ProviderKind) => {
    if (kind === 'claude') {
      if (provider.id === SPECIAL_PROVIDER_IDS.LOCAL_SETTINGS) {
        return t('settings.provider.localProviderName');
      }
      if (provider.id === SPECIAL_PROVIDER_IDS.CLI_LOGIN) {
        return t('settings.provider.cliLoginProviderName');
      }
      if (provider.id === SPECIAL_PROVIDER_IDS.DISABLED) {
        return t('settings.provider.disabled', { defaultValue: 'Disabled' });
      }
    }

    if (kind === 'codex' && provider.id === SPECIAL_PROVIDER_IDS.CODEX_CLI_LOGIN) {
      return t('settings.codexProvider.dialog.cliLoginProviderName');
    }

    if (kind === 'opencode' && provider.id === SPECIAL_PROVIDER_IDS.OPENCODE_LOCAL_CONFIG) {
      return t('settings.openCodeProvider.dialog.localConfigProviderName');
    }

    return provider.name || provider.id;
  }, [t]);

  const requestProviders = useCallback((kind: ProviderKind) => {
    setLoading(true);
    if (kind === 'codex') {
      sendAction(UPSTREAM.GET_CODEX_PROVIDERS);
    } else if (kind === 'opencode') {
      sendAction(UPSTREAM.GET_OPENCODE_PROVIDERS);
    } else {
      sendAction(UPSTREAM.GET_PROVIDERS);
    }
  }, []);

  const handleToggle = useCallback((event: React.MouseEvent) => {
    event.stopPropagation();
    if (!isProviderKind(currentProvider)) {
      return;
    }
    const nextOpen = !isOpen;
    setIsOpen(nextOpen);
    if (nextOpen) {
      recalculate();
      requestProviders(providerKind);
    }
  }, [currentProvider, isOpen, providerKind, recalculate, requestProviders]);

  const handleSelect = useCallback((provider: RuntimeProvider) => {
    if (providerKind === 'codex') {
      sendAction(UPSTREAM.SWITCH_CODEX_PROVIDER, JSON.stringify({ id: provider.id }));
    } else if (providerKind === 'opencode') {
      sendAction(UPSTREAM.SWITCH_OPENCODE_PROVIDER, JSON.stringify({ id: provider.id }));
    } else {
      sendAction(UPSTREAM.SWITCH_PROVIDER, JSON.stringify({ id: provider.id }));
    }
    onProviderSwitched?.(getProviderDisplayName(provider, providerKind));
    setProvidersByKind((previous) => ({
      ...previous,
      [providerKind]: previous[providerKind].map((item) => ({
        ...item,
        isActive: item.id === provider.id,
      })),
    }));
    setIsOpen(false);
    onClose?.();
  }, [getProviderDisplayName, onClose, onProviderSwitched, providerKind]);

  useEffect(() => {
    const unsubscribeProviders = subscribeProviderList((json) => {
      try {
        const providers = parseProviderList(json);
        setProvidersByKind((previous) => ({ ...previous, claude: providers }));
        setLoading(false);
      } catch (error) {
        console.error('[RuntimeProviderSelect] Failed to parse Claude providers:', error);
        setLoading(false);
      }
    });

    const unsubscribeActiveProvider = subscribeActiveProvider((json) => {
      try {
        const activeProvider = JSON.parse(json) as RuntimeProvider;
        if (!activeProvider?.id) return;
        setProvidersByKind((previous) => ({
          ...previous,
          claude: previous.claude.map((provider) => ({
            ...provider,
            isActive: provider.id === activeProvider.id,
          })),
        }));
      } catch (error) {
        console.error('[RuntimeProviderSelect] Failed to parse active Claude provider:', error);
      }
    });

    const unsubscribeCodexProviders = subscribeCodexProviderList((json) => {
      try {
        const providers = parseProviderList(json);
        setProvidersByKind((previous) => ({ ...previous, codex: providers }));
        setLoading(false);
      } catch (error) {
        console.error('[RuntimeProviderSelect] Failed to parse Codex providers:', error);
        setLoading(false);
      }
    });

    const unsubscribeActiveCodexProvider = subscribeActiveCodexProvider((json) => {
      try {
        const activeProvider = JSON.parse(json) as RuntimeProvider;
        if (!activeProvider?.id) return;
        setProvidersByKind((previous) => ({
          ...previous,
          codex: previous.codex.map((provider) => ({
            ...provider,
            isActive: provider.id === activeProvider.id,
          })),
        }));
      } catch (error) {
        console.error('[RuntimeProviderSelect] Failed to parse active Codex provider:', error);
      }
    });

    const unsubscribeOpenCodeProviders = subscribeOpenCodeProviderList((json) => {
      try {
        const providers = parseProviderList(json);
        setProvidersByKind((previous) => ({ ...previous, opencode: providers }));
        setLoading(false);
      } catch (error) {
        console.error('[RuntimeProviderSelect] Failed to parse OpenCode providers:', error);
        setLoading(false);
      }
    });

    const unsubscribeActiveOpenCodeProvider = subscribeActiveOpenCodeProvider((json) => {
      try {
        const activeProvider = JSON.parse(json) as RuntimeProvider;
        if (!activeProvider?.id) return;
        setProvidersByKind((previous) => ({
          ...previous,
          opencode: previous.opencode.map((provider) => ({
            ...provider,
            isActive: provider.id === activeProvider.id,
          })),
        }));
      } catch (error) {
        console.error('[RuntimeProviderSelect] Failed to parse active OpenCode provider:', error);
      }
    });

    return () => {
      unsubscribeProviders();
      unsubscribeActiveProvider();
      unsubscribeCodexProviders();
      unsubscribeActiveCodexProvider();
      unsubscribeOpenCodeProviders();
      unsubscribeActiveOpenCodeProvider();
    };
  }, []);

  useEffect(() => {
    if (!isOpen) return;

    const handleClickOutside = (event: MouseEvent) => {
      if (
        dropdownRef.current &&
        !dropdownRef.current.contains(event.target as Node) &&
        buttonRef.current &&
        !buttonRef.current.contains(event.target as Node)
      ) {
        setIsOpen(false);
      }
    };

    const timer = setTimeout(() => {
      document.addEventListener('mousedown', handleClickOutside);
    }, 0);

    return () => {
      clearTimeout(timer);
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, [isOpen]);

  useEffect(() => {
    if (!embedded) return;
    requestProviders(providerKind);
  }, [embedded, providerKind, requestProviders]);

  useLayoutEffect(() => {
    if (!embedded) return;
    recalculate();
  }, [embedded, recalculate, visibleProviders.length, loading]);

  useLayoutEffect(() => {
    if (embedded || !isOpen) return;
    recalculate();
  }, [embedded, isOpen, recalculate, visibleProviders.length, loading]);

  if (!isProviderKind(currentProvider)) {
    return null;
  }

  const activeName = activeProvider ? getProviderDisplayName(activeProvider, providerKind) : t('config.runtimeProvider.title');

  const dropdownMaxHeight = viewportMaxHeight ? `${Math.min(300, viewportMaxHeight)}px` : '300px';
  const dropdownStyle: React.CSSProperties = {
    minWidth: '260px',
    maxWidth: '360px',
    maxHeight: dropdownMaxHeight,
    overflowY: 'auto',
    ...positionedStyle,
  };

  const renderProviderDropdown = () => (
    <div
      ref={dropdownRef}
      role="listbox"
      className="selector-dropdown runtime-provider-dropdown"
      style={dropdownStyle}
      onMouseEnter={(event) => event.stopPropagation()}
    >
      {loading && visibleProviders.length === 0 ? (
        <div className="selector-option disabled" style={DISABLED_OPTION_STYLE}>
          <span className="codicon codicon-loading codicon-modifier-spin" />
          <span>{t('config.runtimeProvider.loading')}</span>
        </div>
      ) : visibleProviders.length === 0 ? (
        <div className="selector-option disabled" style={DISABLED_OPTION_STYLE}>
          <InfoIcon size={16} />
          <span>{t('config.runtimeProvider.empty')}</span>
        </div>
      ) : (
        visibleProviders.map((provider) => {
          const selected = !!provider.isActive;
          // OpenCode provider 携带 baseURL/apiBase 作描述(对称 claude 的 remark/websiteUrl)
          const description = provider.remark
            || ('websiteUrl' in provider ? provider.websiteUrl : undefined)
            || ('baseURL' in provider ? provider.baseURL : undefined)
            || ('apiBase' in provider ? provider.apiBase : undefined);
          return (
            <div
              key={provider.id}
              className={`selector-option ${selected ? 'selected' : ''}`}
              onClick={() => handleSelect(provider)}
              title={description || getProviderDisplayName(provider, providerKind)}
            >
              <KeyIcon size={16} />
              <div style={PROVIDER_INFO_STYLE}>
                <span className="runtime-provider-name">{getProviderDisplayName(provider, providerKind)}</span>
                {description ? <span className="model-description">{description}</span> : null}
              </div>
              {selected && <CheckIcon size={16} className="check-mark" />}
            </div>
          );
        })
      )}
    </div>
  );

  if (embedded) {
    return renderProviderDropdown();
  }

  return (
    <div style={RELATIVE_INLINE_BLOCK_STYLE}>
      <button
        ref={buttonRef}
        type="button"
        className="selector-button runtime-provider-button"
        onClick={handleToggle}
        aria-label={t('config.runtimeProvider.title')}
        title={`${t('config.runtimeProvider.title')}: ${activeName}`}
      >
        <CommandLineIcon size={14} />
        <span className="selector-button-text runtime-provider-text">{activeName}</span>
        {isOpen ? <ChevronUpIcon size={16} style={CHEVRON_ICON_STYLE} /> : <ChevronDownIcon size={16} style={CHEVRON_ICON_STYLE} />}
      </button>

      {isOpen && renderProviderDropdown()}
    </div>
  );
};
