import { useCallback, useRef } from 'react';
import type { Attachment } from '../types.js';
import type { Dispatch, SetStateAction } from 'react';

interface CompletionLike {
  close: () => void;
}

/**
 * useSubmitHandler - Submit logic for the chat input box
 *
 * - Validates empty input
 * - Records input history
 * - Clears input/attachments for responsiveness
 * - Defers onSubmit to allow UI update
 */
export function useSubmitHandler({
  getTextContent,
  attachments,
  isLoading,
  currentProvider,
  clearInput,
  cancelPendingInput,
  invalidateCache,
  externalAttachments,
  setInternalAttachments,
  clearAttachmentsDraft,
  fileCompletion,
  commandCompletion,
  agentCompletion,
  promptCompletion,
  dollarCommandCompletion,
  recordInputHistory,
  onSubmit,
  addToast,
  t,
}: {
  getTextContent: () => string;
  attachments: Attachment[];
  isLoading: boolean;
  currentProvider: string;
  clearInput: () => void;
  /** Cancel any pending debounced input callbacks to prevent stale values from refilling the input */
  cancelPendingInput: () => void;
  /** Invalidate text content cache to force fresh DOM read on submit */
  invalidateCache: () => void;
  externalAttachments: Attachment[] | undefined;
  setInternalAttachments: Dispatch<SetStateAction<Attachment[]>>;
  /** Clear attachments draft from localStorage */
  clearAttachmentsDraft?: () => void;
  fileCompletion: CompletionLike;
  commandCompletion: CompletionLike;
  agentCompletion: CompletionLike;
  promptCompletion: CompletionLike;
  dollarCommandCompletion: CompletionLike;
  recordInputHistory: (text: string) => void;
  onSubmit?: (content: string, attachmentsToSend?: Attachment[]) => void;
  addToast?: (message: string, type: 'info' | 'warning' | 'error' | 'success') => void;
  t: (key: string, options?: Record<string, unknown>) => string;
}) {
  // In-flight guard: prevents a rapid second invocation (double-Enter, or
  // Enter + send-button within the deferred-onSubmit window) from creating a
  // duplicate optimistic message / duplicate backend request. The text path is
  // mostly protected by clearInput(), but attachments settle asynchronously and
  // the button path bypasses the input-empty check, so this lock is the single
  // source of truth.
  const isSubmittingRef = useRef(false);

  return useCallback(() => {
    if (isSubmittingRef.current) return;

    // Force fresh DOM read to avoid stale cache (e.g., after paste)
    invalidateCache();
    const content = getTextContent();
    const cleanContent = content.replace(/[\u200B-\u200D\uFEFF]/g, '').trim();

    if (!cleanContent && attachments.length === 0) return;

    // Close completions
    fileCompletion.close();
    commandCompletion.close();
    agentCompletion.close();
    promptCompletion.close();
    dollarCommandCompletion.close();

    // Record input history
    recordInputHistory(content);

    const attachmentsToSend = attachments.length > 0 ? [...attachments] : undefined;

    // Cancel any pending debounced input callbacks before clearing
    // This prevents stale values from refilling the input after submit
    cancelPendingInput();
    clearInput();
    if (externalAttachments === undefined) {
      setInternalAttachments([]);
      // Clear attachments draft from localStorage
      clearAttachmentsDraft?.();
    }

    // We are committing to a submit — hold the lock until the deferred
    // onSubmit fires, so concurrent invocations in this window no-op.
    isSubmittingRef.current = true;

    // Call onSubmit even when loading - let parent handle queueing
    setTimeout(() => {
      onSubmit?.(content, attachmentsToSend);
      isSubmittingRef.current = false;
    }, 10);
  }, [
    getTextContent,
    invalidateCache,
    attachments,
    isLoading,
    currentProvider,
    clearInput,
    cancelPendingInput,
    externalAttachments,
    setInternalAttachments,
    clearAttachmentsDraft,
    fileCompletion,
    commandCompletion,
    agentCompletion,
    promptCompletion,
    dollarCommandCompletion,
    recordInputHistory,
    onSubmit,
    addToast,
    t,
  ]);
}
