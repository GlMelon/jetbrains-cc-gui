import { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import type { McpServer, McpServerSpec } from '../../types/mcp';
import { sendAction, subscribeEvent } from '../../bridge/typed';
import { UPSTREAM, DOWNSTREAM } from '../../generated/protocol';
import {
  searchMcpMarket,
  getMcpMarketDetail,
  type SmitheryServerSummary,
  type McpMarketDetailResult,
} from '../../utils/bridge';
import { BaseDialog, DialogHeader, DialogBody, DialogFooter } from '../shared/BaseDialog';
import { PlusIcon, InfoIcon, SearchIcon, KeyIcon, ExternalLinkIcon, BookIcon, StarIcon, ShieldCheckIcon } from '../Icons';
import { UnifiedLoader } from '../UnifiedLoader';
import { ClickSpark } from '../react-bits';

interface SmitheryKeyStatus {
  hasKey: boolean;
  masked: string;
}

interface McpMarketDialogProps {
  isCodexMode: boolean;
  onClose: () => void;
  onSelect: (server: McpServer) => void;
}

/**
 * Smithery server 详情连接配置 → 本地 McpServerSpec。
 * remote→{type:http,url};local→{type:stdio,command,args,env};无可用配置→null。
 */
function buildServerSpec(detail: McpMarketDetailResult): McpServerSpec | null {
  const conn = detail.connection || {};
  const url = conn.mcpUrl || conn.url || conn.deploymentUrl;
  if (detail.remote) {
    if (!url) return null;
    return { type: 'http', url, headers: {} };
  }
  if (conn.command) {
    const args = Array.isArray(conn.args) ? conn.args : (conn.args ? [conn.args] : []);
    return { type: 'stdio', command: conn.command, args, env: conn.env || {} };
  }
  // 非 remote 但无 command,若有 url 退化 http
  if (url) {
    return { type: 'http', url, headers: {} };
  }
  return null;
}

/** 错误码 → i18n 文案。 */
function mapErrorMessage(code: string | undefined, fallback: string, t: (k: string, o?: Record<string, unknown>) => string): string {
  switch (code) {
    case 'MISSING_API_KEY': return t('mcp.market.errorKeyMissing');
    case 'INVALID_API_KEY': return t('mcp.market.errorKeyInvalid');
    case 'NETWORK_ERROR': return t('mcp.market.errorNetwork');
    case 'TIMEOUT': return t('mcp.market.errorTimeout');
    case 'PARSE_ERROR': return t('mcp.market.errorParse');
    default:
      // HTTP_401/403 已在前置逻辑转 INVALID_API_KEY;其他 HTTP_xxx(5xx 等)用通用文案带状态码
      if (code && code.startsWith('HTTP_')) {
        return t('mcp.market.errorHttpStatus', { code: code.replace('HTTP_', '') });
      }
      return fallback;
  }
}

// 哈希配色(复用 McpPresetDialog 配色逻辑,保持卡片视觉一致)
const ICON_COLORS = ['#3B82F6', '#10B981', '#8B5CF6', '#F59E0B', '#EF4444', '#EC4899', '#06B6D4', '#6366F1'];
function getIconColor(id: string): string {
  let hash = 0;
  for (let i = 0; i < id.length; i++) {
    hash = id.charCodeAt(i) + ((hash << 5) - hash);
  }
  return ICON_COLORS[Math.abs(hash) % ICON_COLORS.length];
}

function safeParse(raw: unknown): { hasKey?: boolean; masked?: string } | null {
  if (typeof raw === 'object' && raw !== null) return raw as { hasKey?: boolean; masked?: string };
  if (typeof raw === 'string') {
    try { return JSON.parse(raw); } catch { return null; }
  }
  return null;
}

/**
 * MCP 市场(Smithery Registry)搜索 + 安装弹窗。
 *
 * <p>仿 {@link McpPresetDialog} 裸 div + 卡片模式(同目录视觉一致)。
 * 内嵌 Smithery API Key 配置(广播模式:GET/SET_SMITHERY_API_KEY → CONFIG_SMITHERY_API_KEY,
 * 无 __requestId,组件内 subscribeEvent 监听状态)。
 *
 * <p>选中 server → GET_MCP_MARKET_DETAIL → 构建 McpServer(预填 connection)→ onSelect
 * (父组件打开 McpServerDialog 预填新建模式,用户填 API key/headers 后保存)。
 */
export function McpMarketDialog({ isCodexMode, onClose, onSelect }: McpMarketDialogProps) {
  const { t } = useTranslation();
  const [query, setQuery] = useState('');
  const [servers, setServers] = useState<SmitheryServerSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<{ message: string; code?: string; retry?: () => void } | null>(null);
  const [searched, setSearched] = useState(false);
  const [keyStatus, setKeyStatus] = useState<SmitheryKeyStatus>({ hasKey: false, masked: '' });
  const [showKeyInput, setShowKeyInput] = useState(false);
  const [keyInput, setKeyInput] = useState('');

  // 详情弹窗:列表描述 CSS 截断不展开,点击详情按钮按需拉取单 server 完整信息
  const [detailServer, setDetailServer] = useState<SmitheryServerSummary | null>(null);
  const [detail, setDetail] = useState<McpMarketDetailResult | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  // 订阅 Key 状态 + 初始拉取(广播:GET_SMITHERY_API_KEY → CONFIG_SMITHERY_API_KEY)
  useEffect(() => {
    const unsub = subscribeEvent(DOWNSTREAM.CONFIG_SMITHERY_API_KEY, (raw) => {
      const data = safeParse(raw);
      if (data) {
        setKeyStatus({ hasKey: !!data.hasKey, masked: data.masked || '' });
      }
    });
    sendAction(UPSTREAM.GET_SMITHERY_API_KEY);
    return unsub;
  }, []);

  const handleSearch = useCallback(async (q: string, p = 1) => {
    if (!keyStatus.hasKey) {
      setError({ message: t('mcp.market.errorKeyMissing'), code: 'MISSING_API_KEY' });
      setShowKeyInput(true);
      return;
    }
    setLoading(true);
    setError(null);
    setSearched(true);
    const result = await searchMcpMarket(q, p, 20);
    setLoading(false);
    if (result.error) {
      const isKeyErr = result.errorCode === 'MISSING_API_KEY' || result.errorCode === 'INVALID_API_KEY';
      // 搜索失败:非 key 类错误(网络/超时/HTTP)挂重试闭包重新搜索,key 类错误走 key 输入引导不挂重试
      setError({
        message: mapErrorMessage(result.errorCode, result.error, t),
        code: result.errorCode,
        retry: isKeyErr ? undefined : () => handleSearch(q, p),
      });
      if (isKeyErr) setShowKeyInput(true);
      setServers([]);
    } else {
      setServers(result.servers || []);
    }
  }, [keyStatus.hasKey, t]);

  const handleSelect = useCallback(async (server: SmitheryServerSummary) => {
    const namespace = server.namespace || '';
    const slug = server.slug || '';
    if (!namespace || !slug) {
      setError({ message: t('mcp.market.errorNoConnection'), code: 'NO_CONNECTION' });
      return;
    }
    setLoading(true);
    setError(null);
    const detail = await getMcpMarketDetail(namespace, slug);
    setLoading(false);
    if (detail.error) {
      // 详情拉取失败:挂重试闭包重新拉取该 server 详情
      setError({
        message: mapErrorMessage(detail.errorCode, detail.error, t),
        code: detail.errorCode,
        retry: () => handleSelect(server),
      });
      return;
    }
    const spec = buildServerSpec(detail);
    if (!spec) {
      // 详情端点未含连接配置 → 引导手动配置
      setError({ message: t('mcp.market.errorNoConnection'), code: 'NO_CONNECTION' });
      return;
    }
    const id = slug || server.qualifiedName || `smithery-${Date.now()}`;
    const mcpServer: McpServer = {
      id,
      name: server.displayName || server.qualifiedName || id,
      description: server.description || '',
      tags: [server.remote ? 'remote' : 'stdio', 'smithery'],
      server: spec,
      apps: { claude: !isCodexMode, codex: isCodexMode },
      homepage: server.homepage,
      enabled: true,
    };
    onSelect(mcpServer);
  }, [isCodexMode, onSelect, t]);

  const handleShowDetail = useCallback(async (server: SmitheryServerSummary) => {
    const namespace = server.namespace || '';
    const slug = server.slug || '';
    setDetailServer(server);
    setDetail(null);
    if (!namespace || !slug) {
      setDetail({ error: t('mcp.market.errorNoConnection'), errorCode: 'NO_CONNECTION' });
      return;
    }
    setDetailLoading(true);
    const result = await getMcpMarketDetail(namespace, slug);
    setDetailLoading(false);
    setDetail(result);
  }, [t]);

  const handleCloseDetail = useCallback(() => {
    setDetailServer(null);
    setDetail(null);
    setDetailLoading(false);
  }, []);

  const handleSaveKey = useCallback(() => {
    sendAction(UPSTREAM.SET_SMITHERY_API_KEY, { smitheryApiKey: keyInput });
    setKeyInput('');
    setShowKeyInput(false);
  }, [keyInput]);

  const handleQueryKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') handleSearch(query);
  };

  return (
    <BaseDialog isOpen onClose={onClose} animation="pop" size="lg">
      <DialogHeader title={t('mcp.market.title')} onClose={onClose} />

        <DialogBody>
          <p className="dialog-desc">{t('mcp.market.desc')}</p>

          {/* Smithery API Key 配置区(ok=已配置 / warn=未配置,左边框着色) */}
          <div className={`market-key-section ${keyStatus.hasKey ? 'ok' : 'warn'}`}>
            <div className="market-key-status">
              <InfoIcon size={14} />
              {keyStatus.hasKey ? (
                <span>{t('mcp.market.keyConfigured')} <code>{keyStatus.masked}</code></span>
              ) : (
                <span>{t('mcp.market.keyRequired')}</span>
              )}
            </div>
            <button className="market-key-btn" onClick={() => setShowKeyInput(!showKeyInput)}>
              <KeyIcon size={14} />
              {keyStatus.hasKey ? t('mcp.market.changeKey') : t('mcp.market.configureKey')}
            </button>
            {showKeyInput && (
              <div className="market-key-input">
                <input
                  type="password"
                  value={keyInput}
                  onChange={(e) => setKeyInput(e.target.value)}
                  placeholder={t('mcp.market.keyPlaceholder')}
                />
                <ClickSpark>
                  <button className="btn btn-primary" onClick={handleSaveKey} disabled={!keyInput.trim()}>
                    {t('mcp.market.saveKey')}
                  </button>
                </ClickSpark>
              </div>
            )}
          </div>

          {/* 搜索框 */}
          <div className="market-search">
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={handleQueryKeyDown}
              placeholder={t('mcp.market.searchPlaceholder')}
              disabled={loading}
            />
            <ClickSpark>
              <button className="btn btn-primary" onClick={() => handleSearch(query)} disabled={loading}>
                <SearchIcon size={14} />
                {t('mcp.market.search')}
              </button>
            </ClickSpark>
          </div>

          {/* 错误态(含重试:搜索/详情失败时重新执行失败操作;.market-error 已是 flex 容器) */}
          {error && (
            <div className="market-error">
              <span>{error.message}</span>
              {error.retry && (
                <button className="btn btn-secondary" onClick={error.retry}>{t('mcp.market.retry')}</button>
              )}
            </div>
          )}

          {/* 加载态 */}
          {loading && (
            <div className="market-loading">
              <UnifiedLoader type="bounce" size={16} /> {t('mcp.market.loading')}
            </div>
          )}

          {/* 结果统计行(共 N 个结果 / 来源) */}
          {!loading && !error && servers.length > 0 && (
            <div className="result-meta">
              <span className="count">{t('mcp.market.resultCount', { count: servers.length })}</span>
              <span>{t('mcp.market.source')}</span>
            </div>
          )}

          {/* 卡片列表(复用 preset-item CSS,保持同目录视觉一致) */}
          {!loading && !error && servers.length > 0 && (
            <div className="preset-list">
              {servers.map((s) => {
                const idKey = s.qualifiedName || s.id || s.slug || '';
                return (
                  <div key={idKey} className="preset-item" onClick={() => handleSelect(s)}>
                    <div className="preset-icon" style={{ background: getIconColor(idKey) }}>
                      {(s.displayName || s.qualifiedName || '?').charAt(0).toUpperCase()}
                    </div>
                    <div className="preset-info">
                      <h4 className="preset-name">{s.displayName || s.qualifiedName}</h4>
                      {s.description && <p className="preset-desc clamp">{s.description}</p>}
                      <div className="preset-meta">
                        <span className={`pill ${s.remote ? 'remote' : 'local'}`}>
                          <span className="pill-dot" />
                          {s.remote ? 'REMOTE' : 'LOCAL'}
                        </span>
                        {s.verified && (
                          <span className="pill verified"><ShieldCheckIcon size={14} />{t('mcp.market.verified')}</span>
                        )}
                        {s.useCount != null && s.useCount > 0 && (
                          <span className="pill muted"><StarIcon size={14} />{s.useCount} {t('mcp.market.installs')}</span>
                        )}
                      </div>
                    </div>
                    <button
                      className="detail-btn"
                      onClick={(e) => { e.stopPropagation(); handleShowDetail(s); }}
                      title={t('mcp.market.detail')}
                      aria-label={t('mcp.market.detail')}
                    >
                      <InfoIcon size={14} />
                    </button>
                    <button
                      className="add-btn"
                      onClick={(e) => { e.stopPropagation(); handleSelect(s); }}
                      title={t('mcp.add')}
                      aria-label={t('mcp.add')}
                    >
                      <PlusIcon size={16} />
                    </button>
                  </div>
                );
              })}
            </div>
          )}

          {/* 空态 */}
          {!loading && !error && searched && servers.length === 0 && (
            <div className="market-empty">{t('mcp.market.emptyHint')}</div>
          )}
          {!loading && !error && !searched && (
            <div className="market-empty">{t('mcp.market.startHint')}</div>
          )}
        </DialogBody>

        <DialogFooter>
          <div className="footer-hint">
            <ExternalLinkIcon size={14} />
            <a href="https://smithery.ai/account/api-keys" target="_blank" rel="noreferrer">{t('mcp.market.getKey')}</a>
          </div>
          <button className="btn btn-secondary" onClick={onClose}>{t('mcp.cancel')}</button>
        </DialogFooter>

        {/* 详情弹窗:按需拉取单 server 完整信息(readme/connection/homepage) */}
        {detailServer && (
          <BaseDialog isOpen onClose={handleCloseDetail} size="lg" ariaLabel={t('mcp.market.detail')} className="market-detail-dialog" animation="pop">
            <DialogHeader title={detailServer.displayName || detailServer.qualifiedName || t('mcp.market.detail')} onClose={handleCloseDetail} />
            <DialogBody>
              {detailLoading && (
                <div className="market-loading">
                  <UnifiedLoader type="orbit" size={16} /> {t('mcp.market.detailLoading')}
                </div>
              )}
              {!detailLoading && detail?.error && (
                <div className="market-error">
                  <span>{mapErrorMessage(detail.errorCode, detail.error, t)}</span>
                  <button
                    className="btn btn-secondary"
                    onClick={() => handleShowDetail(detailServer)}
                  >
                    {t('mcp.market.retry')}
                  </button>
                </div>
              )}
              {!detailLoading && detail && !detail.error && (
                <div className="market-detail-content">
                  {detail.description && (
                    <section className="detail-section">
                      <h5 className="detail-label">{t('mcp.description')}</h5>
                      <p className="detail-text">{detail.description}</p>
                    </section>
                  )}
                  {detail.readme && (
                    <section className="detail-section">
                      <h5 className="detail-label"><BookIcon size={14} /> README</h5>
                      <pre className="detail-readme">{detail.readme}</pre>
                    </section>
                  )}
                  <div className="detail-meta-grid">
                    <div className="detail-meta-item">
                      <span className="detail-meta-label">{t('mcp.market.verified')}</span>
                      <span className="detail-meta-value">{detail.verified ? <ShieldCheckIcon size={14} /> : '—'}</span>
                    </div>
                    <div className="detail-meta-item">
                      <span className="detail-meta-label">{t('mcp.market.installs')}</span>
                      <span className="detail-meta-value">{detail.useCount ?? '—'}</span>
                    </div>
                    {detail.homepage && (
                      <div className="detail-meta-item">
                        <span className="detail-meta-label">{t('mcp.homepage')}</span>
                        <a className="detail-link" href={detail.homepage} target="_blank" rel="noreferrer">{detail.homepage}</a>
                      </div>
                    )}
                  </div>
                </div>
              )}
            </DialogBody>
            <DialogFooter>
              <button className="btn btn-secondary" onClick={handleCloseDetail}>{t('mcp.cancel')}</button>
              <ClickSpark>
                <button
                  className="btn btn-primary"
                  onClick={() => { handleCloseDetail(); handleSelect(detailServer); }}
                  disabled={!detail || !!detail.error}
                >
                  <PlusIcon size={14} />
                  {t('mcp.add')}
                </button>
              </ClickSpark>
            </DialogFooter>
          </BaseDialog>
        )}
      </BaseDialog>
  );
}
