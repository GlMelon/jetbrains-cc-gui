import { useCallback, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  ProviderConfig,
  CodexProviderConfig,
  OpenCodeProviderConfig,
} from '../../../types/provider';
import ProviderManageSection from '../ProviderManageSection';
import CodexProviderSection from '../CodexProviderSection';
import OpenCodeProviderSection from '../OpenCodeProviderSection';
import GrokProviderSection from '../GrokProviderSection';
import OmpProviderSection from '../OmpProviderSection';
import DshProviderSection from '../DshProviderSection';
import KimiProviderSection from '../KimiProviderSection';
import PiProviderSection from '../PiProviderSection';
import styles from './style.module.less';
import { useRovingTabs } from '../../shared/useRovingTabs';
import { FadeContent } from '../../react-bits';
import { useCliInstallStatus } from '../../../hooks/useCliInstallStatus';

const BLOCK_STYLE: React.CSSProperties = { display: 'block' };
const NONE_STYLE: React.CSSProperties = { display: 'none' };

type ProviderTab = 'claude' | 'codex' | 'opencode' | 'grok' | 'kimi' | 'pi' | 'omp' | 'dsh';
const PROVIDER_TABS: readonly ProviderTab[] = ['claude', 'codex', 'opencode', 'grok', 'kimi', 'pi', 'omp', 'dsh'];
const PROVIDER_TAB_IDS: Record<ProviderTab, string> = {
  claude: 'tab-claude-providers',
  codex: 'tab-codex-providers',
  opencode: 'tab-opencode-providers',
  grok: 'tab-grok-providers',
  kimi: 'tab-kimi-providers',
  pi: 'tab-pi-providers',
  omp: 'tab-omp-providers',
  dsh: 'tab-dsh-providers',
};
const PROVIDER_PANEL_IDS: Record<ProviderTab, string> = {
  claude: 'panel-claude-providers',
  codex: 'panel-codex-providers',
  opencode: 'panel-opencode-providers',
  grok: 'panel-grok-providers',
  kimi: 'panel-kimi-providers',
  pi: 'panel-pi-providers',
  omp: 'panel-omp-providers',
  dsh: 'panel-dsh-providers',
};

// SVG tab icon paths (24×24 viewBox, stroke-based)
const tabIconPaths: Record<string, string> = {
  // Claude - robot/AI face
  claude:
    '<path d="M12 8V4H8"/><rect x="5" y="7" width="14" height="11" rx="3"/><path d="M9 12h.01"/><path d="M15 12h.01"/><path d="M10 15h4"/>',
  // Codex - terminal/code
  codex: '<polyline points="4 17 10 11 4 5"/><line x1="12" x2="20" y1="19" y2="19"/>',
  // OpenCode - code brackets
  opencode: '<polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/>',
  // Grok - lightning bolt
  grok: '<polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>',
  // Kimi - chat bubble
  kimi: '<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>',
  // Pi - circle with dots
  pi: '<circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/>',
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

  const [activeTab, setActiveTab] = useState<ProviderTab>(() =>
    currentProvider === 'codex' ? 'codex'
      : currentProvider === 'opencode' ? 'opencode'
        : currentProvider === 'grok' ? 'grok'
          : currentProvider === 'kimi' ? 'kimi'
            : currentProvider === 'pi' ? 'pi'
              : currentProvider === 'omp' ? 'omp'
                : currentProvider === 'dsh' ? 'dsh'
                  : 'claude',
  );
  // CLI 未安装门控(方案A):6 个 CLI 类 tab 未安装→置灰+badge+拦截进入;
  // omp/dsh 不在 CLI 检测范围,判定天然放行
  const cliInstall = useCliInstallStatus();
  const cliBlocked = useCallback(
    (tab: ProviderTab) => cliInstall.isNotInstalled(tab),
    [cliInstall],
  );
  const handleTabActivate = useCallback((tab: ProviderTab) => {
    if (cliBlocked(tab)) {
      addToast(t('settings.cli.providerNotInstalledToast', {
        name: t(`providers.${tab}.label`, tab),
      }), 'warning');
      // 返回 false:useRovingTabs 保持焦点在原 tab(拒绝键盘切换)
      return false;
    }
    setActiveTab(tab);
    return undefined;
  }, [cliBlocked, addToast, t]);
  const { getTabProps } = useRovingTabs({
    values: PROVIDER_TABS,
    activeValue: activeTab,
    onActivate: handleTabActivate,
  });

  return (
    <div className={styles.providerTabSection}>
      <h3 className={styles.sectionTitle}>{t('settings.providers')}</h3>
      <p className={styles.sectionDesc}>{t('settings.providersDesc')}</p>

      <div className={styles.tabSelector} role="tablist" aria-label={t('settings.providers')}>
        <button
          {...getTabProps('claude')}
          id={PROVIDER_TAB_IDS.claude}
          type="button"
          role="tab"
          aria-selected={activeTab === 'claude'}
          aria-controls={PROVIDER_PANEL_IDS.claude}
          aria-disabled={cliBlocked('claude')}
          className={`${styles.tabBtn} ${activeTab === 'claude' ? styles.active : ''} ${cliBlocked('claude') ? styles.tabBlocked : ''}`}
          onClick={() => handleTabActivate('claude')}
        >
          <span className={styles.tabIcon}>
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              dangerouslySetInnerHTML={{ __html: tabIconPaths.claude }}
            />
          </span>
          {t('settings.providerTab.claude')}
          {cliBlocked('claude') && <span className={styles.tabBadge}>{t('settings.cli.notInstalled')}</span>}
        </button>
        <button
          {...getTabProps('codex')}
          id={PROVIDER_TAB_IDS.codex}
          type="button"
          role="tab"
          aria-selected={activeTab === 'codex'}
          aria-controls={PROVIDER_PANEL_IDS.codex}
          aria-disabled={cliBlocked('codex')}
          className={`${styles.tabBtn} ${activeTab === 'codex' ? styles.active : ''} ${cliBlocked('codex') ? styles.tabBlocked : ''}`}
          onClick={() => handleTabActivate('codex')}
        >
          <span className={styles.tabIcon}>
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              dangerouslySetInnerHTML={{ __html: tabIconPaths.codex }}
            />
          </span>
          {t('settings.providerTab.codex')}
          {cliBlocked('codex') && <span className={styles.tabBadge}>{t('settings.cli.notInstalled')}</span>}
        </button>
        <button
          {...getTabProps('opencode')}
          id={PROVIDER_TAB_IDS.opencode}
          type="button"
          role="tab"
          aria-selected={activeTab === 'opencode'}
          aria-controls={PROVIDER_PANEL_IDS.opencode}
          aria-disabled={cliBlocked('opencode')}
          className={`${styles.tabBtn} ${activeTab === 'opencode' ? styles.active : ''} ${cliBlocked('opencode') ? styles.tabBlocked : ''}`}
          onClick={() => handleTabActivate('opencode')}
        >
          <span className={styles.tabIcon}>
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              dangerouslySetInnerHTML={{ __html: tabIconPaths.opencode }}
            />
          </span>
          {t('settings.providerTab.opencode')}
          {cliBlocked('opencode') && <span className={styles.tabBadge}>{t('settings.cli.notInstalled')}</span>}
        </button>
        <button
          {...getTabProps('grok')}
          id={PROVIDER_TAB_IDS.grok}
          type="button"
          role="tab"
          aria-selected={activeTab === 'grok'}
          aria-controls={PROVIDER_PANEL_IDS.grok}
          aria-disabled={cliBlocked('grok')}
          className={`${styles.tabBtn} ${activeTab === 'grok' ? styles.active : ''} ${cliBlocked('grok') ? styles.tabBlocked : ''}`}
          onClick={() => handleTabActivate('grok')}
        >
          <span className={styles.tabIcon}>
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              dangerouslySetInnerHTML={{ __html: tabIconPaths.grok }}
            />
          </span>
          {t('settings.providerTab.grok', 'Grok')}
          {cliBlocked('grok') && <span className={styles.tabBadge}>{t('settings.cli.notInstalled')}</span>}
        </button>
        <button
          {...getTabProps('kimi')}
          id={PROVIDER_TAB_IDS.kimi}
          type="button"
          role="tab"
          aria-selected={activeTab === 'kimi'}
          aria-controls={PROVIDER_PANEL_IDS.kimi}
          aria-disabled={cliBlocked('kimi')}
          className={`${styles.tabBtn} ${activeTab === 'kimi' ? styles.active : ''} ${cliBlocked('kimi') ? styles.tabBlocked : ''}`}
          onClick={() => handleTabActivate('kimi')}
        >
          <span className={styles.tabIcon}>
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              dangerouslySetInnerHTML={{ __html: tabIconPaths.kimi }}
            />
          </span>
          {t('settings.providerTab.kimi', 'Kimi')}
          {cliBlocked('kimi') && <span className={styles.tabBadge}>{t('settings.cli.notInstalled')}</span>}
        </button>
        <button
          {...getTabProps('pi')}
          id={PROVIDER_TAB_IDS.pi}
          type="button"
          role="tab"
          aria-selected={activeTab === 'pi'}
          aria-controls={PROVIDER_PANEL_IDS.pi}
          aria-disabled={cliBlocked('pi')}
          className={`${styles.tabBtn} ${activeTab === 'pi' ? styles.active : ''} ${cliBlocked('pi') ? styles.tabBlocked : ''}`}
          onClick={() => handleTabActivate('pi')}
        >
          <span className={styles.tabIcon}>
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              dangerouslySetInnerHTML={{ __html: tabIconPaths.pi }}
            />
          </span>
          {t('settings.providerTab.pi', 'Pi')}
          {cliBlocked('pi') && <span className={styles.tabBadge}>{t('settings.cli.notInstalled')}</span>}
        </button>
        <button
          {...getTabProps('omp')}
          id={PROVIDER_TAB_IDS.omp}
          type="button"
          role="tab"
          aria-selected={activeTab === 'omp'}
          aria-controls={PROVIDER_PANEL_IDS.omp}
          aria-disabled={cliBlocked('omp')}
          className={`${styles.tabBtn} ${activeTab === 'omp' ? styles.active : ''} ${cliBlocked('omp') ? styles.tabBlocked : ''}`}
          onClick={() => handleTabActivate('omp')}
        >
          <span className={styles.tabIcon}>
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              dangerouslySetInnerHTML={{ __html: tabIconPaths.grok }}
            />
          </span>
          {t('settings.providerTab.omp', 'OMP')}
          {cliBlocked('omp') && <span className={styles.tabBadge}>{t('settings.cli.notInstalled')}</span>}
        </button>
        <button
          {...getTabProps('dsh')}
          id={PROVIDER_TAB_IDS.dsh}
          type="button"
          role="tab"
          aria-selected={activeTab === 'dsh'}
          aria-controls={PROVIDER_PANEL_IDS.dsh}
          aria-disabled={cliBlocked('dsh')}
          className={`${styles.tabBtn} ${activeTab === 'dsh' ? styles.active : ''} ${cliBlocked('dsh') ? styles.tabBlocked : ''}`}
          onClick={() => handleTabActivate('dsh')}
        >
          <span className={styles.tabIcon}>
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              dangerouslySetInnerHTML={{ __html: tabIconPaths.grok }}
            />
          </span>
          {t('settings.providerTab.dsh', 'DeepSeek Harness')}
          {cliBlocked('dsh') && <span className={styles.tabBadge}>{t('settings.cli.notInstalled')}</span>}
        </button>
      </div>

      {/* Use display to preserve component state across tab switches */}
      <FadeContent disabled={activeTab !== 'claude'} duration={180} offset={8}>
        <div
          id={PROVIDER_PANEL_IDS.claude}
          role="tabpanel"
          aria-labelledby={PROVIDER_TAB_IDS.claude}
          style={activeTab === 'claude' ? BLOCK_STYLE : NONE_STYLE}
        >
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
      </FadeContent>

      <FadeContent disabled={activeTab !== 'codex'} duration={180} offset={8}>
        <div
          id={PROVIDER_PANEL_IDS.codex}
          role="tabpanel"
          aria-labelledby={PROVIDER_TAB_IDS.codex}
          style={activeTab === 'codex' ? BLOCK_STYLE : NONE_STYLE}
        >
          <CodexProviderSection
            codexProviders={codexProviders}
            codexLoading={codexLoading}
            onAddCodexProvider={onAddCodexProvider}
            onEditCodexProvider={onEditCodexProvider}
            onDeleteCodexProvider={onDeleteCodexProvider}
            onSwitchCodexProvider={onSwitchCodexProvider}
            onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
            addToast={addToast}
            showHeader={false}
          />
        </div>
      </FadeContent>

      <FadeContent disabled={activeTab !== 'opencode'} duration={180} offset={8}>
        <div
          id={PROVIDER_PANEL_IDS.opencode}
          role="tabpanel"
          aria-labelledby={PROVIDER_TAB_IDS.opencode}
          style={activeTab === 'opencode' ? BLOCK_STYLE : NONE_STYLE}
        >
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
      </FadeContent>

      <FadeContent disabled={activeTab !== 'grok'} duration={180} offset={8}>
        <div
          id={PROVIDER_PANEL_IDS.grok}
          role="tabpanel"
          aria-labelledby={PROVIDER_TAB_IDS.grok}
          style={activeTab === 'grok' ? BLOCK_STYLE : NONE_STYLE}
        >
          <GrokProviderSection showHeader={false} />
        </div>
      </FadeContent>

      <FadeContent disabled={activeTab !== 'kimi'} duration={180} offset={8}>
        <div
          id={PROVIDER_PANEL_IDS.kimi}
          role="tabpanel"
          aria-labelledby={PROVIDER_TAB_IDS.kimi}
          style={activeTab === 'kimi' ? BLOCK_STYLE : NONE_STYLE}
        >
          <KimiProviderSection showHeader={false} />
        </div>
      </FadeContent>

      <FadeContent disabled={activeTab !== 'pi'} duration={180} offset={8}>
        <div
          id={PROVIDER_PANEL_IDS.pi}
          role="tabpanel"
          aria-labelledby={PROVIDER_TAB_IDS.pi}
          style={activeTab === 'pi' ? BLOCK_STYLE : NONE_STYLE}
        >
          <PiProviderSection showHeader={false} />
        </div>
      </FadeContent>

      <FadeContent disabled={activeTab !== 'omp'} duration={180} offset={8}>
        <div
          id={PROVIDER_PANEL_IDS.omp}
          role="tabpanel"
          aria-labelledby={PROVIDER_TAB_IDS.omp}
          style={activeTab === 'omp' ? BLOCK_STYLE : NONE_STYLE}
        >
          <OmpProviderSection showHeader={false} />
        </div>
      </FadeContent>

      <FadeContent disabled={activeTab !== 'dsh'} duration={180} offset={8}>
        <div
          id={PROVIDER_PANEL_IDS.dsh}
          role="tabpanel"
          aria-labelledby={PROVIDER_TAB_IDS.dsh}
          style={activeTab === 'dsh' ? BLOCK_STYLE : NONE_STYLE}
        >
          <DshProviderSection showHeader={false} />
        </div>
      </FadeContent>
    </div>
  );
};

export default ProviderTabSection;
