import type { ReactNode } from 'react';
import { BaseDialog, DialogHeader, DialogBody, DialogFooter } from './shared/BaseDialog';

interface ConfirmDialogProps {
  isOpen: boolean;
  title: string;
  message: string;
  confirmText?: string;
  cancelText?: string;
  onConfirm: () => void;
  onCancel: () => void;
  /**
   * Optional extra content rendered inside the dialog body, beneath the message
   * and above the footer. Used by AppDialogs to inject a "don't ask again"
   * checkbox for the new-session confirm dialog. Other call sites leave this
   * undefined and the dialog renders exactly as before.
   */
  children?: ReactNode;
}

const ConfirmDialog = ({
  isOpen,
  title,
  message,
  confirmText = '确定',
  cancelText = '取消',
  onConfirm,
  onCancel,
  children,
}: ConfirmDialogProps) => {
  return (
    <BaseDialog isOpen={isOpen} onClose={onCancel} ariaLabel={title}>
      <DialogHeader title={title} onClose={onCancel} />
      <DialogBody>
        <p className="confirm-dialog-message">{message}</p>
        {children}
      </DialogBody>
      <DialogFooter>
        <button className="btn btn-secondary" onClick={onCancel}>
          {cancelText}
        </button>
        <button className="btn btn-primary" onClick={onConfirm} autoFocus>
          {confirmText}
        </button>
      </DialogFooter>
    </BaseDialog>
  );
};

export default ConfirmDialog;
