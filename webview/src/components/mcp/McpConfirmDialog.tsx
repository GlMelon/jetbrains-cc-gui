import { BaseDialog, DialogHeader, DialogBody, DialogFooter } from '../shared/BaseDialog';

interface McpConfirmDialogProps {
  title: string;
  message: string;
  confirmText?: string;
  cancelText?: string;
  onConfirm: () => void;
  onCancel: () => void;
}

/**
 * MCP Confirm Dialog
 *
 * 基于 BaseDialog(架构债务 §D4):复用遮罩层 / ESC 关闭 / 点击遮罩关闭 /
 * 无障碍属性 / 统一 `.dialog-overlay`·`.dialog-base` className 体系。
 * `isOpen` 恒为 true —— 显隐由父级条件渲染控制(McpSettingsSection)。
 */
export function McpConfirmDialog({
  title,
  message,
  confirmText = '确定',
  cancelText = '取消',
  onConfirm,
  onCancel,
}: McpConfirmDialogProps) {
  return (
    <BaseDialog isOpen onClose={onCancel} ariaLabel={title} size="sm">
      <DialogHeader title={title} onClose={onCancel} />
      <DialogBody>
        <div className="confirm-content">
          <span className="codicon codicon-warning confirm-icon"></span>
          <p className="confirm-message">{message}</p>
        </div>
      </DialogBody>
      <DialogFooter>
        <button className="btn btn-secondary" onClick={onCancel}>
          {cancelText}
        </button>
        <button className="btn btn-danger" onClick={onConfirm}>
          {confirmText}
        </button>
      </DialogFooter>
    </BaseDialog>
  );
}
