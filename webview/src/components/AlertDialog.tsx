
import { useTranslation } from 'react-i18next';
import { BaseDialog, DialogBody, DialogFooter } from './shared/BaseDialog';
import { codiconToIcon } from './Icons';
import { ClickSpark } from './react-bits';

export type AlertType = 'error' | 'warning' | 'info' | 'success';

interface AlertDialogProps {
  isOpen: boolean;
  type?: AlertType;
  title: string;
  message: string;
  confirmText?: string;
  onClose: () => void;
}

const AlertDialog = ({
  isOpen,
  type = 'info',
  title,
  message,
  confirmText,
  onClose,
}: AlertDialogProps) => {
  const { t } = useTranslation();
  const buttonText = confirmText || t('common.confirm');

  const getIconClass = () => {
    switch (type) {
      case 'error':
        return 'codicon-error';
      case 'warning':
        return 'codicon-warning';
      case 'success':
        return 'codicon-pass';
      default:
        return 'codicon-info';
    }
  };

  const getIconColor = () => {
    switch (type) {
      case 'error':
        return 'var(--color-error, #f48771)';
      case 'warning':
        return 'var(--color-warning, #cca700)';
      case 'success':
        return 'var(--color-success, #89d185)';
      default:
        return 'var(--color-info, #75beff)';
    }
  };

  return (
    <BaseDialog isOpen={isOpen} onClose={onClose} ariaLabel={title} className="alert-dialog-overlay" animation="pop">
      <div className="dialog-header" style={{ display: 'flex', alignItems: 'center' }}>
        {codiconToIcon(getIconClass(), 16, {
          style: {
            color: getIconColor(),
            marginRight: '8px',
            lineHeight: 1,
          },
        })}
        <h3 className="dialog-title" style={{ margin: 0, lineHeight: 1.2 }}>{title}</h3>
      </div>
      <DialogBody>
        <p className="alert-dialog-message">{message}</p>
      </DialogBody>
      <DialogFooter align="center">
        <ClickSpark>
          <button className="btn btn-primary" onClick={onClose} autoFocus>
            {buttonText}
          </button>
        </ClickSpark>
      </DialogFooter>
    </BaseDialog>
  );
};

export default AlertDialog;

