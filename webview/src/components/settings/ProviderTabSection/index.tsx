import { useCallback, useState, type ReactNode } from 'react';
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
import MiniMaxProviderSection from '../MiniMaxProviderSection';
import KimiProviderSection from '../KimiProviderSection';
import PiProviderSection from '../PiProviderSection';
import styles from './style.module.less';
import { useRovingTabs } from '../../shared/useRovingTabs';
import { FadeContent } from '../../react-bits';
import { useCliInstallStatus } from '../../../hooks/useCliInstallStatus';
import { ALL_PROVIDER_IDS } from '../../../hooks/providers/cliProviders';
import { type ProviderType } from '../../../generated/protocol';

const BLOCK_STYLE: React.CSSProperties = { display: 'block' };
const NONE_STYLE: React.CSSProperties = { display: 'none' };

// Deep-link target from the provider dropdown / UIStateContext. Local providers
// settings has per-provider tabs instead of upstream's 'cli' surface; 'cli' is
// rerouted to the dependencies tab (CliEnvironmentSection) by settings/index.tsx.
export type ProviderManageTab = 'claude' | 'codex' | 'cli';

// ProviderTab 即协议 SSOT 的 ProviderType(generated/protocol.ts,Java 枚举生成);
// tab/panel id 与 label fallback 均为 Record<ProviderType,…> 穷尽表 —— 后端新增 provider
// 而此处未接线时编译报错,不再依赖人工记忆同步。
type ProviderTab = ProviderType;
const PROVIDER_TABS: readonly ProviderTab[] = ALL_PROVIDER_IDS;
const PROVIDER_TAB_IDS = Object.fromEntries(
  PROVIDER_TABS.map((id) => [id, `tab-${id}-providers`]),
) as Record<ProviderTab, string>;
const PROVIDER_PANEL_IDS = Object.fromEntries(
  PROVIDER_TABS.map((id) => [id, `panel-${id}-providers`]),
) as Record<ProviderTab, string>;

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
  // DSH - hexagon node
  dsh: '<path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/>',
  // MiniMax - four-point star
  minimax: '<path d="M12 3l2.4 6.6L21 12l-6.6 2.4L12 21l-2.4-6.6L3 12l6.6-2.4z"/>',
};

/** i18n 缺 key 时的页签 fallback 文案(穷尽表;有 locale 走 locale)。 */
const TAB_FALLBACK_LABELS: Record<ProviderTab, string> = {
  claude: 'Claude Code',
  codex: 'Codex',
  opencode: 'OpenCode',
  grok: 'Grok',
  kimi: 'Kimi',
  pi: 'Pi',
  omp: 'OMP',
  dsh: 'DeepSeek Harness',
  minimax: 'MiniMax',
};

interface ProviderTabSectionProps {
  currentProvider: 'claude' | 'codex' | string;
  /** Deep-linked sub-tab (e.g. from the provider dropdown's CLI entry); wins over currentProvider inference */
  initialSubTab?: ProviderManageTab;
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
  initialSubTab,
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

  // Deep-linked sub-tab (claude/codex) wins over currentProvider inference;
  // 'cli' is rerouted to the dependencies tab by settings/index.tsx and never lands here.
  const [activeTab, setActiveTab] = useState<ProviderTab>(() => {
    if (initialSubTab === 'claude' || initialSubTab === 'codex') return initialSubTab;
    return currentProvider === 'codex' ? 'codex'
      : currentProvider === 'opencode' ? 'opencode'
        : currentProvider === 'grok' ? 'grok'
          : currentProvider === 'kimi' ? 'kimi'
            : currentProvider === 'pi' ? 'pi'
              : currentProvider === 'omp' ? 'omp'
                : currentProvider === 'dsh' ? 'dsh'
                : currentProvider === 'minimax' ? 'minimax'
                  : 'claude';
  });
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

  // Panel 渲染表:Record<ProviderTab,…> 穷尽 —— 后端新增 provider 未接线时编译报错。
  // claude/codex/opencode 闭包捕获各自管理 props;其余为固定原生配置提示组件。
  const panelContent: Record<ProviderTab, ReactNode> = {
    claude: (
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
    ),
    codex: (
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
    ),
    opencode: (
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
    ),
    grok: <GrokProviderSection showHeader={false} />,
    kimi: <KimiProviderSection showHeader={false} />,
    pi: <PiProviderSection showHeader={false} />,
    omp: <OmpProviderSection showHeader={false} />,
    dsh: <DshProviderSection showHeader={false} />,
    minimax: <MiniMaxProviderSection showHeader={false} />,
  };

  return (
    <div className={styles.providerTabSection}>
      <h3 className={styles.sectionTitle}>{t('settings.providers')}</h3>
      <p className={styles.sectionDesc}>{t('settings.providersDesc')}</p>

      <div className={styles.tabSelector} role="tablist" aria-label={t('settings.providers')}>
        {PROVIDER_TABS.map((tab) => (
          <button
            key={tab}
            {...getTabProps(tab)}
            id={PROVIDER_TAB_IDS[tab]}
            type="button"
            role="tab"
            aria-selected={activeTab === tab}
            aria-controls={PROVIDER_PANEL_IDS[tab]}
            aria-disabled={cliBlocked(tab)}
            className={`${styles.tabBtn} ${activeTab === tab ? styles.active : ''} ${cliBlocked(tab) ? styles.tabBlocked : ''}`}
            onClick={() => handleTabActivate(tab)}
          >
            <span className={styles.tabIcon}>
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinecap="round"
                strokeLinejoin="round"
                dangerouslySetInnerHTML={{ __html: tabIconPaths[tab] }}
              />
            </span>
            {t(`settings.providerTab.${tab}`, TAB_FALLBACK_LABELS[tab])}
            {cliBlocked(tab) && <span className={styles.tabBadge}>{t('settings.cli.notInstalled')}</span>}
          </button>
        ))}
      </div>

      {/* Use display to preserve component state across tab switches */}
      {PROVIDER_TABS.map((tab) => (
        <FadeContent key={tab} disabled={activeTab !== tab} duration={180} offset={8}>
          <div
            id={PROVIDER_PANEL_IDS[tab]}
            role="tabpanel"
            aria-labelledby={PROVIDER_TAB_IDS[tab]}
            style={activeTab === tab ? BLOCK_STYLE : NONE_STYLE}
          >
            {panelContent[tab]}
          </div>
        </FadeContent>
      ))}
    </div>
  );
};

export default ProviderTabSection;
