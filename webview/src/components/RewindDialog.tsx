import { useTranslation } from 'react-i18next';
import { BaseDialog, DialogHeader, DialogBody, DialogFooter } from './shared/BaseDialog';
import { UnifiedLoader } from './UnifiedLoader';
import { ClickSpark } from './react-bits';

export interface RewindRequest {
  sessionId: string;
  userMessageId: string;
  messageContent: string;
  messageTimestamp?: string;
  messagesAfterCount: number;
}

interface RewindDialogProps {
  isOpen: boolean;
  request: RewindRequest | null;
  isLoading?: boolean;
  onConfirm: (sessionId: string, userMessageId: string) => void;
  onCancel: () => void;
}

const RewindDialog = ({
  isOpen,
  request,
  isLoading = false,
  onConfirm,
  onCancel,
}: RewindDialogProps) => {
  const { t } = useTranslation();

  if (!isOpen || !request) {
    return null;
  }

  const handleConfirm = () => {
    onConfirm(request.sessionId, request.userMessageId);
  };

  // Truncate message content for display
  const displayContent = request.messageContent.length > 50
    ? `${request.messageContent.substring(0, 50)}...`
    : request.messageContent;

  return (
    <BaseDialog isOpen={isOpen} onClose={onCancel} ariaLabel={t('rewind.title')} animation="pop">
      <DialogHeader
        title={t('rewind.title', 'Rewind Files to Previous State')}
        icon={<span className="rewind-icon">&#x21BA;</span>}
      />
      <DialogBody>
        {isLoading ? (
          <div className="rewind-loading">
            <UnifiedLoader type="spin" size={20} className="rewind-loading-icon" />
            <span className="rewind-loading-text">{t('rewind.restoring', 'Restoring files...')}</span>
          </div>
        ) : (
          <>
            <div className="rewind-target">
              <div className="rewind-target-label">{t('rewind.rewindTo', 'Rewind to')}:</div>
              <div className="rewind-target-message">
                {request.messageTimestamp && (
                  <span className="rewind-timestamp">[{request.messageTimestamp}]</span>
                )}
                <span className="rewind-content">"{displayContent}"</span>
              </div>
            </div>

            <div className="rewind-warning">
              <div className="rewind-warning-icon">&#x26A0;</div>
              <div className="rewind-warning-content">
                <div className="rewind-warning-title">{t('rewind.impact', 'Impact')}:</div>
                <ul className="rewind-warning-list">
                  <li>{t('rewind.willRestore', 'Will restore files to their state at this message')}</li>
                  <li>
                    {t('rewind.changesLost', 'Changes made after this point will be lost')}
                    {request.messagesAfterCount > 0 && (
                      <span className="rewind-affected-count">
                        ({request.messagesAfterCount} {t('rewind.messagesAffected', 'messages affected')})
                      </span>
                    )}
                  </li>
                  <li>{t('rewind.historyKept', 'Conversation history will be kept')}</li>
                </ul>
              </div>
            </div>

            <p className="rewind-note">
              {t('rewind.cannotUndo', 'This action cannot be undone.')}
            </p>
          </>
        )}
      </DialogBody>
      <DialogFooter>
        {isLoading ? (
          <button className="btn btn-secondary" onClick={onCancel}>
            {t('common.close', 'Close')}
          </button>
        ) : (
          <>
            <button className="btn btn-secondary" onClick={onCancel}>
              {t('common.cancel', 'Cancel')}
            </button>
            <ClickSpark>
              <button
                className="btn btn-primary rewind-confirm-button"
                onClick={handleConfirm}
                autoFocus
              >
                {t('rewind.restoreFiles', 'Restore Files')}
              </button>
            </ClickSpark>
          </>
        )}
      </DialogFooter>
    </BaseDialog>
  );
};

export default RewindDialog;
