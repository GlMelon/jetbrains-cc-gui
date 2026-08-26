import { sendAction } from '../bridge/typed';
import { UPSTREAM } from '../generated/protocol';
import { useCallback, type RefObject } from 'react';
import type { TFunction } from 'i18next';
import type { ClaudeContentBlock, ClaudeMessage } from '../types';
import type { Attachment, ChatInputBoxHandle, PermissionMode, SelectedAgent } from '../components/ChatInputBox/types';
import { expandQuoteTokens } from '../components/ChatInputBox/utils/quoteRegistry';

/**
 * Handles message building, validation, and sending to the backend.
 *
 * Plugin-local slash commands (/clear, /plan, /context, /model, /help, ...) are NOT
 * handled here — they are resolved via useLocalSlashCommands in App.tsx, driven by
 * backend-annotated localAction metadata (SSOT: SlashCommandRegistry).
 */
export function useMessageSender({
  t,
  addToast,
  currentProvider,
  dshPreset,
  selectedAgent,
  sentAttachmentsRef,
  chatInputRef,
  messagesContainerRef,
  isUserAtBottomRef,
  userPausedRef,
  isStreamingRef,
  setMessages,
  setLoading,
  setLoadingStartTime,
  setStreamingActive,
}: {
  t: TFunction;
  addToast: (message: string, type?: 'info' | 'success' | 'warning' | 'error') => void;
  currentProvider: string;
  selectedModel: string;
  permissionMode: PermissionMode;
  dshPreset: string;
  selectedAgent: SelectedAgent | null;
  sentAttachmentsRef: RefObject<Map<string, Array<{ fileName: string; mediaType: string }>>>;
  chatInputRef: RefObject<ChatInputBoxHandle | null>;
  messagesContainerRef: RefObject<HTMLDivElement | null>;
  isUserAtBottomRef: RefObject<boolean>;
  userPausedRef: RefObject<boolean>;
  isStreamingRef: RefObject<boolean>;
  setMessages: React.Dispatch<React.SetStateAction<ClaudeMessage[]>>;
  setLoading: React.Dispatch<React.SetStateAction<boolean>>;
  setLoadingStartTime: React.Dispatch<React.SetStateAction<number | null>>;
  setStreamingActive: React.Dispatch<React.SetStateAction<boolean>>;
}) {
  const getProviderDisplayName = useCallback((provider: string): string => {
    return provider === 'codex' ? 'Codex' : 'Claude Code';
  }, []);

  /**
   * Check for unimplemented slash commands
   */
  const checkUnimplementedCommand = useCallback((text: string): boolean => {
    if (!text.startsWith('/')) return false;

    const command = text.split(/\s+/)[0].toLowerCase();
    const unimplementedCommands = ['/plugin', '/plugins'];

    if (unimplementedCommands.includes(command)) {
      const userMessage: ClaudeMessage = {
        type: 'user',
        content: text,
        timestamp: new Date().toISOString(),
      };
      const assistantMessage: ClaudeMessage = {
        type: 'assistant',
        content: t('chat.commandNotImplemented', { command }),
        timestamp: new Date().toISOString(),
      };
      setMessages((prev) => [...prev, userMessage, assistantMessage]);
      return true;
    }
    return false;
  }, [t, setMessages]);

  /**
   * Build content blocks for the user message
   */
  const buildUserContentBlocks = useCallback((
    text: string,
    attachments: Attachment[] | undefined
  ): ClaudeContentBlock[] => {
    const blocks: ClaudeContentBlock[] = [];

    const hasImageAttachments = Array.isArray(attachments) &&
      attachments.some(att => att.mediaType?.startsWith('image/'));

    if (Array.isArray(attachments) && attachments.length > 0) {
      for (const att of attachments) {
        if (att.mediaType?.startsWith('image/')) {
          blocks.push({
            type: 'image',
            src: `data:${att.mediaType};base64,${att.data}`,
            mediaType: att.mediaType,
            sourceKind: 'base64',
          });
        } else {
          blocks.push({
            type: 'attachment',
            fileName: att.fileName,
            mediaType: att.mediaType,
          });
        }
      }
    }

    // Filter placeholder text: skip if there are image attachments and text is placeholder
    const isPlaceholderText = text && text.trim().startsWith('[Uploaded ');

    if (text && !(hasImageAttachments && isPlaceholderText)) {
      blocks.push({ type: 'text', text });
    }

    return blocks;
  }, []);

  /**
   * Send message to backend
   */
  const sendMessageToBackend = useCallback((
    text: string,
    attachments: Attachment[] | undefined,
    agentInfo: { id: string; name: string; prompt?: string } | null,
    fileTagsInfo: { displayPath: string; absolutePath: string }[] | null
  ) => {
    const hasAttachments = Array.isArray(attachments) && attachments.length > 0;

    if (hasAttachments) {
      try {
        const payload = JSON.stringify({
          text,
          attachments: (attachments || []).map(a => ({
            fileName: a.fileName,
            mediaType: a.mediaType,
            data: a.data,
          })),
          agent: agentInfo,
          fileTags: fileTagsInfo,
          dshPreset: dshPreset || undefined,
        });
        sendAction(UPSTREAM.SEND_MESSAGE_WITH_ATTACHMENTS, payload);
      } catch (error) {
        console.error('[Frontend] Failed to serialize attachments payload', error);
        const fallbackPayload = JSON.stringify({
          text,
          agent: agentInfo,
          fileTags: fileTagsInfo,
          dshPreset: dshPreset || undefined,
        });
        sendAction(UPSTREAM.SEND_MESSAGE, fallbackPayload);
      }
    } else {
      const payload = JSON.stringify({
        text,
        agent: agentInfo,
        fileTags: fileTagsInfo,
        dshPreset: dshPreset || undefined,
      });
      sendAction(UPSTREAM.SEND_MESSAGE, payload);
    }
  }, [dshPreset]);

  /**
   * Execute message sending (from queue or directly)
   */
  const executeMessage = useCallback((content: string, attachments?: Attachment[]) => {
    // Expand inline quote chips (PUA-delimited tokens) into Markdown blockquotes
    // before sending \u2014 chips are serialized back to tokens by getTextContent.
    const text = expandQuoteTokens(content).replace(/[\u200B-\u200D\uFEFF]/g, '').trim();
    const hasAttachments = Array.isArray(attachments) && attachments.length > 0;

    if (!text && !hasAttachments) return;

    // Build user message content blocks
    const userContentBlocks = buildUserContentBlocks(text, attachments);
    if (userContentBlocks.length === 0) return;

    // Persist non-image attachment metadata
    const nonImageAttachments = Array.isArray(attachments)
      ? attachments.filter(a => !a.mediaType?.startsWith('image/'))
      : [];
    if (nonImageAttachments.length > 0) {
      const MAX_ATTACHMENT_CACHE_SIZE = 100;
      if (sentAttachmentsRef.current.size >= MAX_ATTACHMENT_CACHE_SIZE) {
        const firstKey = sentAttachmentsRef.current.keys().next().value;
        if (firstKey !== undefined) {
          sentAttachmentsRef.current.delete(firstKey);
        }
      }
      sentAttachmentsRef.current.set(text || '', nonImageAttachments.map(a => ({
        fileName: a.fileName,
        mediaType: a.mediaType,
      })));
    }

    // Create and add user message (optimistic update)
    const userMessage: ClaudeMessage = {
      type: 'user',
      content: text || '',
      timestamp: new Date().toISOString(),
      isOptimistic: true,
      raw: { message: { content: userContentBlocks } },
    };
    setMessages((prev) => [...prev, userMessage]);

    // Set loading state
    setLoading(true);
    setLoadingStartTime(Date.now());

    // Scroll to bottom
    userPausedRef.current = false;
    isUserAtBottomRef.current = true;
    requestAnimationFrame(() => {
      if (messagesContainerRef.current) {
        messagesContainerRef.current.scrollTop = messagesContainerRef.current.scrollHeight;
      }
    });

    // Build agent info
    const agentInfo = selectedAgent ? {
      id: selectedAgent.id,
      name: selectedAgent.name,
      prompt: selectedAgent.prompt,
    } : null;

    // Extract file tag info
    const fileTags = chatInputRef.current?.getFileTags() ?? [];
    const fileTagsInfo = fileTags.length > 0 ? fileTags.map(tag => ({
      displayPath: tag.displayPath,
      absolutePath: tag.absolutePath,
    })) : null;

    // Send message to backend
    sendMessageToBackend(text, attachments, agentInfo, fileTagsInfo);
  }, [
    currentProvider,
    selectedAgent,
    buildUserContentBlocks,
    sendMessageToBackend,
    addToast,
    t,
    getProviderDisplayName,
  ]);

  /**
   * Handle message submission (from ChatInputBox)
   */
  const handleSubmit = useCallback((content: string, attachments?: Attachment[]) => {
    const text = content.replace(/[\u200B-\u200D\uFEFF]/g, '').trim();
    const hasAttachments = Array.isArray(attachments) && attachments.length > 0;

    if (!text && !hasAttachments) return;

    // Check for unimplemented commands
    if (checkUnimplementedCommand(text)) return;

    // Execute message
    executeMessage(content, attachments);
  }, [checkUnimplementedCommand, executeMessage]);

  /**
   * Interrupt the current session.
   *
   * Calls the canonical onStreamEnd callback to atomically clean up all
   * streaming state (refs, buffers, turn tracking) and stamps
   * __streamEndProcessedTurnId so the backend's delayed onStreamEnd
   * (from handleInterruptSession) becomes a no-op via the idempotency guard.
   */
  const interruptSession = useCallback(() => {
    if (typeof window.onStreamEnd === 'function') {
      window.onStreamEnd();
    }
    // Safety net: ensure loading/streaming are reset even when onStreamEnd
    // ran in 'skip' mode (no active streaming turn to end).
    setLoading(false);
    setLoadingStartTime(null);
    setStreamingActive(false);
    isStreamingRef.current = false;

    sendAction(UPSTREAM.INTERRUPT_SESSION);
  }, []);

  return {
    handleSubmit,
    executeMessage,
    interruptSession,
  };
}
