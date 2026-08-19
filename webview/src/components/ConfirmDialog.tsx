import type { ReactNode } from 'react';
import { BaseDialog, DialogHeader, DialogBody, DialogFooter } from './shared/BaseDialog';
import { ClickSpark, GradientText } from './react-bits';

interface ConfirmDialogProps {
  isOpen: boolean;
  title: string;
  message: string;
  confirmText?: string;
  cancelText?: string;
  onConfirm: () => void;
  onCancel: () => void;
  /**
   * Optional type badge rendered in the header next to the title, e.g.
   * `<span className="u-badge u-badge--session">会话</span>`. Unified design language.
   */
  badge?: ReactNode;
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
  badge,
  children,
}: ConfirmDialogProps) => {
  return (
    <BaseDialog isOpen={isOpen} onClose={onCancel} ariaLabel={title} animation="pop">
      <div className="u-aurora-strip" />
      <DialogHeader
        title={
          <GradientText colors={['#6d8cff', '#b06cff']} angle={90}>
            {title}
          </GradientText>
        }
        badge={badge}
        onClose={onCancel}
      />
      <DialogBody>
        <p className="confirm-dialog-message">{message}</p>
        {children}
      </DialogBody>
      <DialogFooter>
        <button className="btn btn-secondary u-btn-lift" onClick={onCancel}>
          {cancelText}
        </button>
        <ClickSpark>
          <button className="btn btn-primary u-btn-lift" onClick={onConfirm} autoFocus>
            {confirmText}
          </button>
        </ClickSpark>
      </DialogFooter>
    </BaseDialog>
  );
};

export default ConfirmDialog;
