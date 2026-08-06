import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import type { ClaudeMessage } from '../types';
import { BaseDialog, DialogHeader, DialogBody, DialogFooter } from './shared/BaseDialog';

export interface RewindableMessage {
  messageIndex: number;
  message: ClaudeMessage;
  displayContent: string;
  timestamp?: string;
  messagesAfterCount: number;
}

interface RewindSelectDialogProps {
  isOpen: boolean;
  rewindableMessages: RewindableMessage[];
  onSelect: (item: RewindableMessage) => void;
  onCancel: () => void;
}

const RewindSelectDialog = ({
  isOpen,
  rewindableMessages,
  onSelect,
  onCancel,
}: RewindSelectDialogProps) => {
  const { t } = useTranslation();

  // Sort messages by index descending (most recent first)
  const sortedMessages = useMemo(() => {
    return [...rewindableMessages].sort((a, b) => b.messageIndex - a.messageIndex);
  }, [rewindableMessages]);

  // Truncate message content for display
  const truncateContent = (content: string, maxLength: number = 60): string => {
    if (content.length <= maxLength) {
      return content;
    }
    return `${content.substring(0, maxLength)}...`;
  };

  return (
    <BaseDialog isOpen={isOpen} onClose={onCancel} ariaLabel={t('rewind.selectTitle')} animation="pop">
      <DialogHeader
        title={t('rewind.selectTitle', '选择回溯点')}
        icon={<span className="rewind-icon">&#x21BA;</span>}
      />
      <DialogBody className="rewind-select-body">
        {sortedMessages.length === 0 ? (
          <div className="rewind-select-empty">
            {t('rewind.noRewindableMessages', '当前会话中没有可回溯的消息')}
          </div>
        ) : (
          <div className="rewind-select-list">
            {sortedMessages.map((item, index) => (
              <div
                key={item.messageIndex}
                className="rewind-select-item"
                onClick={() => onSelect(item)}
              >
                <div className="rewind-select-item-content">
                  <span className="rewind-select-timestamp">[{sortedMessages.length - index}]</span>
                  <span className="rewind-select-text" title={item.displayContent}>
                    {truncateContent(item.displayContent)}
                  </span>
                </div>
                <div className="rewind-select-item-meta">
                  <span className="rewind-select-affected">
                    {item.messagesAfterCount} {t('rewind.messagesAffected', '条消息受影响')}
                  </span>
                </div>
              </div>
            ))}
          </div>
        )}
      </DialogBody>
      <DialogFooter>
        <button className="btn btn-secondary" onClick={onCancel}>
          {t('common.cancel', 'Cancel')}
        </button>
      </DialogFooter>
    </BaseDialog>
  );
};

export default RewindSelectDialog;
