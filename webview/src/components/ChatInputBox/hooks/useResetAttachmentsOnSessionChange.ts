import { useEffect, useRef } from 'react';
import { forceWebviewRepaint } from '../../../utils/forceWebviewRepaint.js';

/**
 * Resets draft attachments when the active session changes so they don't drift
 * into a new conversation, then forces a webview repaint to clear any JCEF
 * native-rendering ghosting the removed thumbnails leave behind on macOS
 * (see forceWebviewRepaint).
 *
 * Skips the initial mount (no spurious clear/repaint on first render). In
 * controlled mode the parent owns the attachment list, so only the repaint runs.
 */
export function useResetAttachmentsOnSessionChange({
  currentSessionId,
  isControlled,
  clearInternalAttachments,
}: {
  /** Active session id from SessionContext; null when there is no session/provider. */
  currentSessionId: string | null;
  /** Whether attachments are externally controlled (the parent owns clearing). */
  isControlled: boolean;
  /** Clears the internal attachment list and its persisted draft. */
  clearInternalAttachments: () => void;
}): void {
  const prevSessionIdRef = useRef(currentSessionId);
  useEffect(() => {
    if (prevSessionIdRef.current === currentSessionId) return;
    prevSessionIdRef.current = currentSessionId;
    if (!isControlled) {
      clearInternalAttachments();
    }
    forceWebviewRepaint('session-change-attachments');
  }, [currentSessionId, isControlled, clearInternalAttachments]);
}
