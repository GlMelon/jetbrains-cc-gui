import { useTranslation } from 'react-i18next';
import { BaseDialog, DialogHeader, DialogBody, DialogFooter } from '../shared/BaseDialog';
import { AlertIcon } from '../Icons';
import { ClickSpark } from '../react-bits';
import type { PackageRunnerInfo } from './packageRunner';

export interface PackageConfirmItem {
  serverName: string;
  info: PackageRunnerInfo;
}

interface McpPackageConfirmDialogProps {
  items: PackageConfirmItem[];
  onConfirm: () => void;
  onCancel: () => void;
}

/**
 * MCP 外部包二次确认弹窗(B3/SEC-06)。
 *
 * npx/uvx 等包管理型 runner 会从网络拉取并执行任意包,docker/podman 会拉取并运行任意镜像。
 * 安装前在此明示 runner + 包名/镜像 + 完整命令,让用户确认信任来源后再放行。
 * 复用 BaseDialog(§D4):遮罩 / ESC / 焦点管理 / 统一 className 体系。
 */
export function McpPackageConfirmDialog({ items, onConfirm, onCancel }: McpPackageConfirmDialogProps) {
  const { t } = useTranslation();
  const isBatch = items.length > 1;

  const describe = (info: PackageRunnerInfo) => {
    if (info.kind === 'container') {
      return info.packageName
        ? t('mcp.packageConfirm.messageContainer', { runner: info.runner, package: info.packageName })
        : t('mcp.packageConfirm.messageNoPackage', { runner: info.runner });
    }
    return info.packageName
      ? t('mcp.packageConfirm.message', { runner: info.runner, package: info.packageName })
      : t('mcp.packageConfirm.messageNoPackage', { runner: info.runner });
  };

  return (
    <BaseDialog isOpen onClose={onCancel} ariaLabel={t('mcp.packageConfirm.title')} size="sm" animation="pop">
      <DialogHeader title={t('mcp.packageConfirm.title')} onClose={onCancel} />
      <DialogBody>
        <div className="confirm-content">
          <AlertIcon size={16} className="confirm-icon" />
          <div className="confirm-detail">
            {isBatch && <p className="confirm-message">{t('mcp.packageConfirm.batchPrefix')}</p>}
            {items.map((item) => (
              <div key={item.serverName} className="confirm-item">
                {!isBatch && <p className="confirm-message">{describe(item.info)}</p>}
                <div className="confirm-item-meta">
                  <span className="confirm-server">{item.serverName}</span>
                  {isBatch && <span className="confirm-desc">{describe(item.info)}</span>}
                </div>
                {item.info.fullCommand && (
                  <code className="confirm-command">{item.info.fullCommand}</code>
                )}
              </div>
            ))}
            <p className="confirm-risk">{t('mcp.packageConfirm.risk')}</p>
          </div>
        </div>
      </DialogBody>
      <DialogFooter>
        <button className="btn btn-secondary" onClick={onCancel}>
          {t('mcp.packageConfirm.cancel')}
        </button>
        <ClickSpark>
          <button className="btn btn-danger" onClick={onConfirm}>
            {t('mcp.packageConfirm.confirm')}
          </button>
        </ClickSpark>
      </DialogFooter>
    </BaseDialog>
  );
}
