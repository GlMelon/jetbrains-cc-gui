import { useState, useEffect, useCallback, useId, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { BaseDialog, DialogHeader, DialogBody, DialogFooter } from '../shared/BaseDialog';
import {
  listSkillMarket,
  installSkillFromMarket,
  getSkillMarketDetail,
  type SkillMarketItem,
  type SkillMarketSourceInfo,
  type ListSkillMarketResult,
  type SkillMarketDetailResult,
} from '../../utils/bridge';
import { DownloadIcon, GlobeIcon, FolderIcon, InfoIcon } from '../Icons';
import { SKILL_SCOPE } from '../../types/skill';
import type { SkillScope } from '../../types/skill';
import { useRovingTabs } from '../shared/useRovingTabs';

interface SkillMarketDialogProps {
  currentProvider: string;
  onClose: () => void;
  /** 安装成功后刷新父列表(复用 GET_ALL_SKILLS 重拉) */
  onInstalled: () => void;
}

// 哈希配色(复用 McpMarketDialog/SkillsSettingsSection 配色,保持卡片视觉一致)
const ICON_COLORS = [
  '#3B82F6',
  '#10B981',
  '#8B5CF6',
  '#F59E0B',
  '#EF4444',
  '#EC4899',
  '#06B6D4',
  '#6366F1',
];
function toDomIdPart(value: string): string {
  return value.replace(/[^a-zA-Z0-9_-]/g, '-');
}

function getIconColor(id: string): string {
  let hash = 0;
  for (let i = 0; i < id.length; i++) {
    hash = id.charCodeAt(i) + ((hash << 5) - hash);
  }
  return ICON_COLORS[Math.abs(hash) % ICON_COLORS.length];
}

/** 错误码 → i18n 文案(后端 MarketFetchException 错误分级)。 */
function mapErrorMessage(
  code: string | undefined,
  fallback: string,
  t: (k: string, o?: Record<string, unknown>) => string,
): string {
  switch (code) {
    case 'UNKNOWN_SOURCE':
      return t('skills.market.errorUnknownSource');
    case 'INVALID_SKILL_NAME':
      return t('skills.market.errorInvalidName');
    case 'HASH_MISMATCH':
      return t('skills.market.errorHashMismatch');
    case 'HTTP_404':
      return t('skills.market.errorNotFound');
    case 'HTTP_403':
      return t('skills.market.errorRateLimit');
    case 'NETWORK_ERROR':
      return t('skills.market.errorNetwork');
    case 'TIMEOUT':
      return t('skills.market.errorTimeout');
    case 'PARSE_ERROR':
      return t('skills.market.errorParse');
    case 'INSTALL_FAILED':
      return t('skills.market.errorInstall');
    default:
      // HTTP_404/HTTP_403 已有专门文案;其他 HTTP_xxx(5xx 等)用通用文案带状态码
      if (code && code.startsWith('HTTP_')) {
        return t('skills.market.errorHttpStatus', { code: code.replace('HTTP_', '') });
      }
      return fallback;
  }
}

/**
 * Skills 市场弹窗:从 GitHub 仓库(anthropics/skills、vercel-labs/agent-skills、obra/superpowers)
 * 浏览 + 安装 skill(tarball 下载 + SHA-256 校验 + 解压)。
 *
 * <p>基于 {@link BaseDialog}。顶部按源仓库切换 Tab(3 源,源列表从后端 SOURCES SSOT 下发);
 * scope 按 provider 归一(Codex=user/repo,其余 global/local),与 SkillsSettingsSection 一致。
 *
 * <p>安装流程:installSkillFromMarket(source, skillPath, scope) → loading →
 * 收到 SKILL_MARKET_INSTALL_RESULT → onInstalled 刷新父列表 + toast。
 * provider 由后端从 HandlerContext 读(不前端传,防伪造)。
 */
export function SkillMarketDialog({
  currentProvider,
  onClose,
  onInstalled,
}: SkillMarketDialogProps) {
  const { t } = useTranslation();
  const isCodex = currentProvider === 'codex';
  const primaryScope: SkillScope = isCodex ? SKILL_SCOPE.USER : SKILL_SCOPE.GLOBAL;
  const secondaryScope: SkillScope = isCodex ? SKILL_SCOPE.REPO : SKILL_SCOPE.LOCAL;

  const [sources, setSources] = useState<SkillMarketSourceInfo[]>([]);
  const [activeSource, setActiveSource] = useState('anthropics');
  const [skills, setSkills] = useState<SkillMarketItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [installing, setInstalling] = useState<string | null>(null); // 正在安装的 skill path
  const [error, setError] = useState<{ message: string; code?: string } | null>(null);
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const [scope, setScope] = useState<SkillScope>(primaryScope);
  const tabsInstanceId = useId();
  const sourcePanelId = `${tabsInstanceId}-source-panel`;
  const sourceIds = useMemo(() => sources.map((source) => source.id), [sources]);

  // 详情弹窗:列表只展示 name/path(Contents API 快速路径),详情按需拉取 SKILL.md frontmatter
  const [detailSkill, setDetailSkill] = useState<SkillMarketItem | null>(null);
  const [detail, setDetail] = useState<SkillMarketDetailResult | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const fetchSkills = useCallback(
    async (sourceId: string) => {
      setLoading(true);
      setError(null);
      const result: ListSkillMarketResult = await listSkillMarket(sourceId);
      setLoading(false);
      if (result.error) {
        setError({
          message: mapErrorMessage(result.errorCode, result.error, t),
          code: result.errorCode,
        });
        setSkills([]);
        return;
      }
      if (result.sources && result.sources.length > 0) {
        setSources(result.sources);
      }
      setSkills(result.skills || []);
    },
    [t],
  );

  // 初始拉取默认源(anthropics)
  useEffect(() => {
    fetchSkills('anthropics');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleSwitchSource = (sourceId: string) => {
    if (sourceId === activeSource) return;
    setActiveSource(sourceId);
    setScope(primaryScope); // 切源重置 scope 为 primary
    fetchSkills(sourceId);
  };
  const { getTabProps } = useRovingTabs({
    values: sourceIds,
    activeValue: activeSource,
    onActivate: handleSwitchSource,
  });
  const getSourceTabId = (sourceId: string) =>
    `${tabsInstanceId}-source-${toDomIdPart(sourceId)}-tab`;

  const handleInstall = useCallback(
    async (skill: SkillMarketItem) => {
      setInstalling(skill.path);
      setError(null);
      setToast(null);
      const result = await installSkillFromMarket(activeSource, skill.path, scope);
      setInstalling(null);
      if (result.success) {
        setToast({
          message: t('skills.market.installSuccess', { name: result.skillName || skill.name }),
          type: 'success',
        });
        onInstalled();
      } else {
        setToast({
          message: mapErrorMessage(
            result.errorCode,
            result.error || t('skills.market.errorInstall'),
            t,
          ),
          type: 'error',
        });
      }
    },
    [activeSource, scope, onInstalled, t],
  );

  const handleShowDetail = useCallback(
    async (skill: SkillMarketItem) => {
      setDetailSkill(skill);
      setDetail(null);
      setDetailLoading(true);
      const result = await getSkillMarketDetail(activeSource, skill.path);
      setDetailLoading(false);
      setDetail(result);
    },
    [activeSource],
  );

  const handleCloseDetail = useCallback(() => {
    setDetailSkill(null);
    setDetail(null);
    setDetailLoading(false);
  }, []);

  return (
    <BaseDialog
      isOpen
      onClose={onClose}
      size="lg"
      ariaLabel={t('skills.market.title')}
      className="skill-market-dialog"
    >
      <DialogHeader title={t('skills.market.title')} onClose={onClose} />
      <DialogBody>
        <p className="dialog-desc">{t('skills.market.desc')}</p>

        {/* 源仓库 Tab(后端 SOURCES SSOT 下发) */}
        {sources.length > 0 && (
          <div className="market-source-tabs" role="tablist">
            {sources.map((s) => (
              <button
                {...getTabProps(s.id)}
                id={getSourceTabId(s.id)}
                key={s.id}
                type="button"
                className={`tab-item ${activeSource === s.id ? 'active' : ''}`}
                role="tab"
                aria-selected={activeSource === s.id}
                aria-controls={sourcePanelId}
                onClick={() => handleSwitchSource(s.id)}
              >
                {s.label}
              </button>
            ))}
          </div>
        )}

        <div
          id={sourcePanelId}
          role="tabpanel"
          aria-labelledby={sources.length > 0 ? getSourceTabId(activeSource) : undefined}
        >
          {/* scope 选择(按 provider 归一) */}
          <div className="market-scope-select">
            <span className="scope-label">{t('skills.market.installScope')}</span>
            <button
              className={`scope-btn ${scope === primaryScope ? 'active' : ''}`}
              onClick={() => setScope(primaryScope)}
            >
              <GlobeIcon size={14} />
              {isCodex ? t('skills.user') : t('skills.global')}
            </button>
            <button
              className={`scope-btn ${scope === secondaryScope ? 'active' : ''}`}
              onClick={() => setScope(secondaryScope)}
            >
              <DownloadIcon size={14} />
              {isCodex ? t('skills.repo') : t('skills.local')}
            </button>
          </div>

          {/* 错误态(含重试:列表加载失败时重新拉取当前源;.market-error 已是 flex 容器) */}
          {error && (
            <div className="market-error">
              <span>{error.message}</span>
              <button className="btn btn-secondary" onClick={() => fetchSkills(activeSource)}>
                {t('skills.market.retry')}
              </button>
            </div>
          )}

          {/* toast(安装成功/失败反馈) */}
          {toast && <div className={`market-toast ${toast.type}`}>{toast.message}</div>}

          {/* 加载态 */}
          {loading && (
            <div className="market-loading">
              <span className="codicon codicon-loading codicon-modifier-spin"></span>{' '}
              {t('skills.market.loading')}
            </div>
          )}

          {/* 结果统计行(共 N 个 Skills / 源) */}
          {!loading && !error && skills.length > 0 && (
            <div className="result-meta">
              <span className="count">
                {t('skills.market.resultCount', { count: skills.length })}
              </span>
              <span>
                {t('skills.market.sourceLabel', {
                  source: sources.find((s) => s.id === activeSource)?.label || activeSource,
                })}
              </span>
            </div>
          )}

          {/* 卡片列表(复用 preset-item CSS,保持同目录视觉一致) */}
          {!loading && !error && skills.length > 0 && (
            <div className="preset-list skill-market-list">
              {skills.map((s) => {
                const installingThis = installing === s.path;
                return (
                  <div key={s.path} className="preset-item">
                    <div className="preset-icon" style={{ background: getIconColor(s.name) }}>
                      <FolderIcon size={16} />
                    </div>
                    <div className="preset-info">
                      <h4 className="preset-name">{s.name}</h4>
                      <span className="pill muted mono">{s.path}</span>
                    </div>
                    <button
                      className="detail-btn"
                      onClick={() => handleShowDetail(s)}
                      title={t('skills.market.detail')}
                      aria-label={t('skills.market.detail')}
                    >
                      <InfoIcon size={14} />
                    </button>
                    <button
                      className="btn btn-primary install-btn"
                      onClick={() => handleInstall(s)}
                      disabled={installingThis}
                      title={t('skills.market.install')}
                    >
                      {installingThis ? (
                        <span className="codicon codicon-loading codicon-modifier-spin"></span>
                      ) : (
                        <DownloadIcon size={14} />
                      )}
                      {installingThis ? t('common.loading') : t('skills.market.install')}
                    </button>
                  </div>
                );
              })}
            </div>
          )}

          {/* 空态 */}
          {!loading && !error && skills.length === 0 && (
            <div className="market-empty">{t('skills.market.emptyHint')}</div>
          )}
        </div>
      </DialogBody>
      <DialogFooter>
        <button className="btn btn-secondary" onClick={onClose}>
          {t('common.close')}
        </button>
      </DialogFooter>

      {/* 详情弹窗:按需拉取 SKILL.md frontmatter(name/description/license/compatibility/allowedTools/paths) */}
      {detailSkill && (
        <BaseDialog
          isOpen
          onClose={handleCloseDetail}
          size="lg"
          ariaLabel={t('skills.market.detail')}
          className="market-detail-dialog"
        >
          <DialogHeader title={detail?.name || detailSkill.name} onClose={handleCloseDetail} />
          <DialogBody>
            {detailLoading && (
              <div className="market-loading">
                <span className="codicon codicon-loading codicon-modifier-spin"></span>{' '}
                {t('skills.market.detailLoading')}
              </div>
            )}
            {!detailLoading && detail?.error && (
              <div className="market-error">
                <span>
                  {mapErrorMessage(
                    detail.errorCode,
                    detail.error || t('skills.market.errorParse'),
                    t,
                  )}
                </span>
                <button
                  className="btn btn-secondary"
                  onClick={() => handleShowDetail(detailSkill)}
                >
                  {t('skills.market.retry')}
                </button>
              </div>
            )}
            {!detailLoading && detail && !detail.error && (
              <div className="market-detail-content">
                {detail.description ? (
                  <section className="detail-section">
                    <h5 className="detail-label">{t('skills.description')}</h5>
                    <p className="detail-text">{detail.description}</p>
                  </section>
                ) : (
                  <p className="detail-text muted">{t('skills.noDescription')}</p>
                )}
                <div className="detail-meta-grid">
                  {detail.sourceLabel && (
                    <div className="detail-meta-item">
                      <span className="detail-meta-label">{t('skills.market.fieldSource')}</span>
                      <span className="detail-meta-value">{detail.sourceLabel}</span>
                    </div>
                  )}
                  {detail.license && (
                    <div className="detail-meta-item">
                      <span className="detail-meta-label">{t('skills.market.fieldLicense')}</span>
                      <span className="detail-meta-value">{detail.license}</span>
                    </div>
                  )}
                  {detail.compatibility && (
                    <div className="detail-meta-item">
                      <span className="detail-meta-label">
                        {t('skills.market.fieldCompatibility')}
                      </span>
                      <span className="detail-meta-value">{detail.compatibility}</span>
                    </div>
                  )}
                  <div className="detail-meta-item">
                    <span className="detail-meta-label">
                      {t('skills.market.fieldUserInvocable')}
                    </span>
                    <span className="detail-meta-value">
                      {detail.userInvocable
                        ? t('skills.market.valueYes')
                        : t('skills.market.valueNo')}
                    </span>
                  </div>
                  {detail.allowedTools && (
                    <div className="detail-meta-item">
                      <span className="detail-meta-label">
                        {t('skills.market.fieldAllowedTools')}
                      </span>
                      <span className="detail-meta-value">{detail.allowedTools}</span>
                    </div>
                  )}
                </div>
                {detail.paths && detail.paths.length > 0 && (
                  <section className="detail-section">
                    <h5 className="detail-label">{t('skills.market.fieldPaths')}</h5>
                    <div className="detail-paths">
                      {detail.paths.map((p, i) => (
                        <code key={i} className="detail-path-tag">
                          {p}
                        </code>
                      ))}
                    </div>
                  </section>
                )}
              </div>
            )}
          </DialogBody>
          <DialogFooter>
            <button className="btn btn-secondary" onClick={handleCloseDetail}>
              {t('common.close')}
            </button>
            <button
              className="btn btn-primary"
              onClick={() => {
                handleCloseDetail();
                handleInstall(detailSkill);
              }}
              disabled={installing === detailSkill.path}
            >
              {installing === detailSkill.path ? (
                <span className="codicon codicon-loading codicon-modifier-spin"></span>
              ) : (
                <DownloadIcon size={14} />
              )}
              {t('skills.market.install')}
            </button>
          </DialogFooter>
        </BaseDialog>
      )}
    </BaseDialog>
  );
}
