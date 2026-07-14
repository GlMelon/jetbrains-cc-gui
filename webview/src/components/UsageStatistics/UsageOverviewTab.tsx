import { useTranslation } from 'react-i18next';
import type { ProjectStatistics } from '../../types/usage';
import { CommentIcon, CreditCardIcon, FolderIcon, GraphIcon, NumberIcon } from '../Icons';

function getBarFillStyle(percentage: number): React.CSSProperties {
  // 用 transform: scaleX 替代 width:百分比 —— transform 走 compositor 线程不触发
  // layout reflow,而 width 每次数据更新都会重排条内布局。圆角由 .token-bar-track
  // 的 border-radius + overflow:hidden 裁剪提供,fill 自身无需 border-radius。
  const ratio = Math.min(100, Math.max(0, percentage)) / 100;
  return { transform: `scaleX(${ratio})` };
}

interface OverviewTabProps {
  statistics: ProjectStatistics;
  formatCost: (cost: number) => string;
  formatNumber: (num: number) => string;
  renderTrend: (value: number) => { className: string; text: string };
  getTokenPercentage: (value: number) => number;
}

export const UsageOverviewTab = ({
  statistics, formatCost, formatNumber, renderTrend, getTokenPercentage,
}: OverviewTabProps) => {
  const { t } = useTranslation();

  const trendNode = (value: number) => {
    const { className, text } = renderTrend(value);
    return <span className={className}>{text}</span>;
  };

  return (
    <div className="overview-tab">
      <div className="project-info-simple">
        <FolderIcon size={16} />
        <span className="project-name">{statistics.projectName}</span>
      </div>

      <div className="stat-cards">
        <div className="stat-card cost-card">
          <div className="stat-icon"><CreditCardIcon size={16} /></div>
          <div className="stat-content">
            <div className="stat-label">{t('usage.totalCost')}</div>
            <div className="stat-value">{formatCost(statistics.estimatedCost)}</div>
            {statistics.weeklyComparison && trendNode(statistics.weeklyComparison.trends.cost)}
          </div>
        </div>

        <div className="stat-card sessions-card">
          <div className="stat-icon"><CommentIcon size={16} /></div>
          <div className="stat-content">
            <div className="stat-label">{t('usage.totalSessions')}</div>
            <div className="stat-value">{statistics.totalSessions}</div>
            {statistics.weeklyComparison && trendNode(statistics.weeklyComparison.trends.sessions)}
          </div>
        </div>

        <div className="stat-card tokens-card">
          <div className="stat-icon"><NumberIcon size={16} /></div>
          <div className="stat-content">
            <div className="stat-label">{t('usage.totalTokens')}</div>
            <div className="stat-value">{formatNumber(statistics.totalUsage.totalTokens)}</div>
            {statistics.weeklyComparison && trendNode(statistics.weeklyComparison.trends.tokens)}
          </div>
        </div>

        <div className="stat-card avg-card">
          <div className="stat-icon"><GraphIcon size={16} /></div>
          <div className="stat-content">
            <div className="stat-label">{t('usage.avgPerSession')}</div>
            <div className="stat-value">
              {statistics.totalSessions > 0
                ? formatCost(statistics.estimatedCost / statistics.totalSessions)
                : '$0.00'}
            </div>
          </div>
        </div>
      </div>

      <div className="token-breakdown-section">
        <h4>{t('usage.tokenBreakdown')}</h4>
        <div className="token-breakdown-independent">
          {([
            { labelKey: 'usage.input', value: statistics.totalUsage.inputTokens, cls: 'input' },
            { labelKey: 'usage.output', value: statistics.totalUsage.outputTokens, cls: 'output' },
            { labelKey: 'usage.cacheWrite', value: statistics.totalUsage.cacheWriteTokens, cls: 'cache-write' },
            { labelKey: 'usage.cacheRead', value: statistics.totalUsage.cacheReadTokens, cls: 'cache-read' },
          ] as const).map(({ labelKey, value, cls }) => (
            <div key={cls} className="token-bar-item">
              <div className="token-bar-header">
                <span className="token-bar-label">{t(labelKey)}</span>
                <span className="token-bar-value">{formatNumber(value)}</span>
              </div>
              <div className="token-bar-track">
                <div className={`token-bar-fill ${cls}`} style={getBarFillStyle(getTokenPercentage(value))} />
              </div>
            </div>
          ))}
        </div>
      </div>

      {statistics.byModel.length > 0 && (
        <div className="top-models-section">
          <h4>{t('usage.topModels')}</h4>
          <div className="top-models">
            {statistics.byModel.slice(0, 3).map((model, index) => (
              <div key={model.model} className="model-card">
                <div className="model-rank">#{index + 1}</div>
                <div className="model-info">
                  <div className="model-name">{model.model}</div>
                  <div className="model-stats">
                    <span>{formatCost(model.totalCost)}</span>
                    <span className="separator">•</span>
                    <span>{formatNumber(model.totalTokens)} tokens</span>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};
