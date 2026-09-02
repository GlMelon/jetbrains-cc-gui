import { memo } from 'react';
import type { Attachment, SelectedAgent, QueuedMessage } from './types.js';
import { AttachmentList } from './AttachmentList.js';
import { ContextBar } from './ContextBar.js';
import { MessageQueue } from './MessageQueue.js';

export const ChatInputBoxHeader = memo(function ChatInputBoxHeader({
  currentProvider,
  attachments,
  onRemoveAttachment,
  activeFile,
  selectedLines,
  usagePercentage,
  usageUsedTokens,
  usageMaxTokens,
  tokenDetail,
  showUsage,
  onClearContext,
  onAddAttachment,
  selectedAgent,
  onClearAgent,
  hasMessages,
  onRewind,
  statusPanelExpanded,
  onToggleStatusPanel,
  messageQueue,
  onRemoveFromQueue,
  autoOpenFileEnabled,
  onRequestEnableFileContext,
  selectedModel,
}: {
  currentProvider: string;
  attachments: Attachment[];
  onRemoveAttachment: (id: string) => void;
  activeFile?: string;
  selectedLines?: string;
  usagePercentage: number;
  usageUsedTokens?: number;
  usageMaxTokens?: number;
  tokenDetail?: any;
  showUsage: boolean;
  onClearContext?: () => void;
  onAddAttachment: (files: FileList) => void;
  selectedAgent?: SelectedAgent | null;
  onClearAgent: () => void;
  hasMessages: boolean;
  onRewind?: () => void;
  statusPanelExpanded: boolean;
  onToggleStatusPanel?: () => void;
  messageQueue?: QueuedMessage[];
  onRemoveFromQueue?: (id: string) => void;
  autoOpenFileEnabled?: boolean;
  onRequestEnableFileContext?: () => void;
  selectedModel?: string;
}) {
  return (
    <>
      {/* Message queue */}
      {messageQueue && messageQueue.length > 0 && (
        <MessageQueue
          queue={messageQueue}
          onRemove={onRemoveFromQueue ?? (() => {})}
        />
      )}

      {/* Attachment list */}
      {attachments.length > 0 && (
        <AttachmentList attachments={attachments} onRemove={onRemoveAttachment} />
      )}

      {/* Context bar (Top Control Bar) */}
      <ContextBar
        activeFile={activeFile}
        selectedLines={selectedLines}
        percentage={usagePercentage}
        usedTokens={usageUsedTokens}
        maxTokens={usageMaxTokens}
        tokenDetail={tokenDetail}
        showUsage={showUsage}
        onClearFile={onClearContext}
        onAddAttachment={onAddAttachment}
        selectedAgent={selectedAgent}
        onClearAgent={onClearAgent}
        currentProvider={currentProvider}
        hasMessages={hasMessages}
        onRewind={onRewind}
        statusPanelExpanded={statusPanelExpanded}
        onToggleStatusPanel={onToggleStatusPanel}
        autoOpenFileEnabled={autoOpenFileEnabled}
        onRequestEnableFileContext={onRequestEnableFileContext}
        selectedModel={selectedModel}
      />
    </>
  );
});

