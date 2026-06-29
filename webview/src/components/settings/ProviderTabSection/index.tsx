import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { ProviderConfig, CodexProviderConfig, OpenCodeProviderConfig } from '../../../types/provider';
import ProviderManageSection from '../ProviderManageSection';
import CodexProviderSection from '../CodexProviderSection';
import OpenCodeProviderSection from '../OpenCodeProviderSection';
import styles from './style.module.less';

const BLOCK_STYLE: React.CSSProperties = { display: 'block' };
const NONE_STYLE: React.CSSProperties = { display: 'none' };

// SVG tab icon paths (24×24 viewBox, stroke-based)
const tabIconPaths: Record<string, string> = {
  // Claude - robot/AI face
  claude: '<path d="M12 8V4H8"/><rect x="5" y="7" width="14" height="11" rx="3"/><path d="M9 12h.01"/><path d="M15 12h.01"/><path d="M10 15h4"/>',
  // Codex - terminal/code
  codex: '<polyline points="4 17 10 11 4 5"/><line x1="12" x2="20" y1="19" y2="19"/>',
  // OpenCode - code brackets
  opencode: '<polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/>',
  // Plugin puzzle
  plugin: '<path d="M12 2v6M6 8h12M8 8v8a4 4 0 0 0 8 0V8"/>',
};

interface ProviderTabSectionProps {
  currentProvider: 'claude' | 'codex' | string;
  // Claude provider props
  providers: ProviderConfig[];
  loading: boolean;
  onAddProvider: () => void;
  onEditProvider: (provider: ProviderConfig) => void;
  onDeleteProvider: (provider: ProviderConfig) => void;
  onSwitchProvider: (id: string) => void;
  // Codex provider props
  codexProviders: CodexProviderConfig[];
  codexLoading: boolean;
  onAddCodexProvider: () => void;
  onEditCodexProvider: (provider: CodexProviderConfig) => void;
  onDeleteCodexProvider: (provider: CodexProviderConfig) => void;
  onSwitchCodexProvider: (id: string) => void;
  onRevokeCodexLocalConfigAuthorization: (fallbackProviderId?: string) => void;
  // OpenCode provider props
  openCodeProviders: OpenCodeProviderConfig[];
  openCodeLoading: boolean;
  onAddOpenCodeProvider: () => void;
  onEditOpenCodeProvider: (provider: OpenCodeProviderConfig) => void;
  onDeleteOpenCodeProvider: (provider: OpenCodeProviderConfig) => void;
  onSwitchOpenCodeProvider: (id: string) => void;
  onRevokeOpenCodeLocalConfigAuthorization: (fallbackProviderId?: string) => void;
  // Shared
  addToast: (message: string, type: 'info' | 'success' | 'warning' | 'error') => void;
}

const ProviderTabSection = ({
  currentProvider,
  providers,
  loading,
  onAddProvider,
  onEditProvider,
  onDeleteProvider,
  onSwitchProvider,
  codexProviders,
  codexLoading,
  onAddCodexProvider,
  onEditCodexProvider,
  onDeleteCodexProvider,
  onSwitchCodexProvider,
  onRevokeCodexLocalConfigAuthorization,
  openCodeProviders,
  openCodeLoading,
  onAddOpenCodeProvider,
  onEditOpenCodeProvider,
  onDeleteOpenCodeProvider,
  onSwitchOpenCodeProvider,
  onRevokeOpenCodeLocalConfigAuthorization,
  addToast,
}: ProviderTabSectionProps) => {
  const { t } = useTranslation();

  const [activeTab, setActiveTab] = useState<'claude' | 'codex' | 'opencode'>(
    () => currentProvider === 'codex' ? 'codex' : currentProvider === 'opencode' ? 'opencode' : 'claude'
  );

  return (
    <div className={styles.providerTabSection}>
      <h3 className={styles.sectionTitle}>{t('settings.providers')}</h3>
      <p className={styles.sectionDesc}>{t('settings.providersDesc')}</p>

      <div className={styles.tabSelector} role="tablist" aria-label={t('settings.providers')}>
        <button
          role="tab"
          aria-selected={activeTab === 'claude'}
          aria-controls="panel-claude-providers"
          className={`${styles.tabBtn} ${activeTab === 'claude' ? styles.active : ''}`}
          onClick={() => setActiveTab('claude')}
        >
          <span className={styles.tabIcon}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" dangerouslySetInnerHTML={{ __html: tabIconPaths.claude }} />
          </span>
          {t('settings.providerTab.claude')}
        </button>
        <button
          role="tab"
          aria-selected={activeTab === 'codex'}
          aria-controls="panel-codex-providers"
          className={`${styles.tabBtn} ${activeTab === 'codex' ? styles.active : ''}`}
          onClick={() => setActiveTab('codex')}
        >
          <span className={styles.tabIcon}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" dangerouslySetInnerHTML={{ __html: tabIconPaths.codex }} />
          </span>
          {t('settings.providerTab.codex')}
        </button>
        <button
          role="tab"
          aria-selected={activeTab === 'opencode'}
          aria-controls="panel-opencode-providers"
          className={`${styles.tabBtn} ${activeTab === 'opencode' ? styles.active : ''}`}
          onClick={() => setActiveTab('opencode')}
        >
          <span className={styles.tabIcon}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" dangerouslySetInnerHTML={{ __html: tabIconPaths.opencode }} />
          </span>
          {t('settings.providerTab.opencode')}
        </button>
      </div>

      {/* Use display to preserve component state across tab switches */}
      <div id="panel-claude-providers" role="tabpanel" style={activeTab === 'claude' ? BLOCK_STYLE : NONE_STYLE}>
        <ProviderManageSection
          providers={providers}
          loading={loading}
          onAddProvider={onAddProvider}
          onEditProvider={onEditProvider}
          onDeleteProvider={onDeleteProvider}
          onSwitchProvider={onSwitchProvider}
          addToast={addToast}
          showHeader={false}
        />
      </div>

      <div id="panel-codex-providers" role="tabpanel" style={activeTab === 'codex' ? BLOCK_STYLE : NONE_STYLE}>
        <CodexProviderSection
          codexProviders={codexProviders}
          codexLoading={codexLoading}
          onAddCodexProvider={onAddCodexProvider}
          onEditCodexProvider={onEditCodexProvider}
          onDeleteCodexProvider={onDeleteCodexProvider}
          onSwitchCodexProvider={onSwitchCodexProvider}
          onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
          showHeader={false}
        />
      </div>

      <div id="panel-opencode-providers" role="tabpanel" style={activeTab === 'opencode' ? BLOCK_STYLE : NONE_STYLE}>
        <OpenCodeProviderSection
          openCodeProviders={openCodeProviders}
          openCodeLoading={openCodeLoading}
          onAddOpenCodeProvider={onAddOpenCodeProvider}
          onEditOpenCodeProvider={onEditOpenCodeProvider}
          onDeleteOpenCodeProvider={onDeleteOpenCodeProvider}
          onSwitchOpenCodeProvider={onSwitchOpenCodeProvider}
          onRevokeOpenCodeLocalConfigAuthorization={onRevokeOpenCodeLocalConfigAuthorization}
          showHeader={false}
        />
      </div>
    </div>
  );
};

export default ProviderTabSection;
