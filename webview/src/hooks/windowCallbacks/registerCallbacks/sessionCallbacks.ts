/**
 * sessionCallbacks.ts
 *
 * Registers window bridge callbacks for session management, SDK dependency status,
 * and rewind result: setSessionId, addToast, onExportSessionData,
 * updateDependencyStatus, onRewindResult.
 */

import { sendAction, subscribeEvent } from '../../../bridge/typed';
import { UPSTREAM, DOWNSTREAM } from '../../../generated/protocol';
import type { MutableRefObject } from 'react';
import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import { downloadJSON } from '../../../utils/exportSessionJson';
import { releaseSessionTransition } from '../sessionTransition';
import { drainAndRequestDependencyStatus } from '../settingsBootstrap';
import { registerLegacyAlias } from '../../../bridge';

// Matches session-titles-service.cjs#updateTitle, which rejects longer titles.
const CUSTOM_TITLE_MAX_LENGTH = 50;

export function registerSessionAndSdkCallbacks(
  options: UseWindowCallbacksOptions,
  tRef: MutableRefObject<UseWindowCallbacksOptions['t']>,
): void {
  const {
    addToast,
    setCurrentSessionId,
    setSdkStatus,
    setSdkStatusLoaded,
    setIsRewinding,
    setRewindDialogOpen,
    setCurrentRewindRequest,
    customSessionTitleRef,
    currentSessionIdRef,
    updateHistoryTitle,
    applyHistoryTitleLocal,
    setCustomSessionTitle,
  } = options;

  // [归一化] updateSessionTitle → session.title
  registerLegacyAlias('updateSessionTitle', DOWNSTREAM.SESSION_TITLE);
  subscribeEvent(DOWNSTREAM.SESSION_TITLE, (json) => {
    try {
      const data = JSON.parse(json as string);
      const { sessionId, title } = data;
      if (!title || !title.trim() || !sessionId) return;
      if (currentSessionIdRef.current !== sessionId) return;
      setCustomSessionTitle(title.trim());
      applyHistoryTitleLocal(sessionId, title.trim());
    } catch (error) {
      console.error('[Frontend] Failed to parse session title:', error);
    }
  });

  window.setSessionId = (sessionId: string) => {
    const oldId = currentSessionIdRef.current;
    releaseSessionTransition();
    setCurrentSessionId(sessionId);

    // B-011 + B-014: Persist custom title under the real SDK session ID.
    // NOTE: We intentionally do NOT delete the old ID's title to prevent
    // data loss when Codex creates new threads for continued conversations.
    // Orphaned title entries are harmless and cleaned up on session deletion.
    const title = customSessionTitleRef.current;
    if (title && oldId !== sessionId) {
      // AI-generated titles can exceed the backend limit. Fall back to
      // local-only update so the UI keeps the title visible without a
      // silent backend write failure.
      if (title.length <= CUSTOM_TITLE_MAX_LENGTH) {
        updateHistoryTitle(sessionId, title);
      } else {
        applyHistoryTitleLocal(sessionId, title);
      }
    }
  };

  window.addToast = (message, type) => {
    addToast(message, type as 'info' | 'success' | 'warning' | 'error' | undefined);
  };

  window.onExportSessionData = (json) => {
    try {
      const data = JSON.parse(json);
      if (data.sessionId && data.messages) {
        const exportContent = JSON.stringify(data, null, 2);
        const sanitizedTitle = (data.title || 'session')
          .replace(/[<>:"/\\|?*]/g, '_')
          .replace(/\s+/g, '_')
          .substring(0, 50);
        const filename = `${sanitizedTitle}_${data.sessionId.substring(0, 8)}.json`;
        downloadJSON(exportContent, filename);
      } else if (data.error) {
        addToast(data.error, 'error');
      } else {
        addToast(tRef.current('history.exportFailed'), 'error');
      }
    } catch (error) {
      console.error('[Frontend] Failed to process export data:', error);
      addToast(tRef.current('history.exportFailed'), 'error');
    }
  };

  // =========================================================================
  // SDK Status Callbacks
  // =========================================================================

  // [归一化] updateDependencyStatus → dependency.status。
  // bridgeHub 广播:DependencySection 也订阅同 type(本地 state),两者皆被调用,无需手动链式转发。
  registerLegacyAlias('updateDependencyStatus', DOWNSTREAM.DEPENDENCY_STATUS);
  subscribeEvent(DOWNSTREAM.DEPENDENCY_STATUS, (jsonStr) => {
    try {
      const data = JSON.parse(jsonStr as string);
      setSdkStatus(data);
      setSdkStatusLoaded(true);
    } catch (error) {
      console.error('[Frontend] Failed to parse dependency status:', error);
      setSdkStatusLoaded(true);
    }
  });

  drainAndRequestDependencyStatus();

  // =========================================================================
  // Rewind Result Callback
  // =========================================================================

  window.onRewindResult = (json: string) => {
    try {
      const result = JSON.parse(json);
      setIsRewinding(false);
      if (result.success) {
        setRewindDialogOpen(false);
        setCurrentRewindRequest(null);
        window.addToast?.(tRef.current('rewind.success'), 'success');
      } else {
        window.addToast?.(result.message || tRef.current('rewind.failed'), 'error');
      }
    } catch (error) {
      console.error('[Frontend] Failed to parse rewind result:', error);
      setIsRewinding(false);
      setRewindDialogOpen(false);
      setCurrentRewindRequest(null);
      window.addToast?.(tRef.current('rewind.parseError'), 'error');
    }
  };

  // =========================================================================
  // SDK-to-CLI Session Conversion Result Callback
  // =========================================================================

  window.onConversionResult = (json: string) => {
    // The optimistic update already flipped the entrypoint to 'cli'; reloading
    // restores the real on-disk state (confirming success or rolling back failure).
    const reloadHistory = () => {
      const provider = options.currentProviderRef.current;
      if (provider) {
        sendAction(UPSTREAM.DEEP_SEARCH_HISTORY, provider);
      } else {
        console.warn('[Frontend] Provider unavailable for conversion state reload');
      }
    };

    try {
      const result = JSON.parse(json);
      if (result.success) {
        if (result.infoCode === 'ALREADY_CLI_SESSION') {
          // Already a CLI session - the optimistic update was correct, no reload needed
          addToast(tRef.current('history.conversionErrors.ALREADY_CLI_SESSION'), 'info');
        } else {
          // Actually converted - show success and reload to get updated index
          addToast(tRef.current('history.convertSuccess'), 'success');
          reloadHistory();
        }
        return;
      }

      // Failure path: translate error code and show error message
      const errorCode = result.errorCode;
      let errorMessage = tRef.current('history.convertFailed');

      if (errorCode && tRef.current(`history.conversionErrors.${errorCode}`)) {
        errorMessage = tRef.current(`history.conversionErrors.${errorCode}`);
      }

      addToast(errorMessage, 'error');

      // Always reload on failure: the optimistic update made the convert button
      // disappear, so without a rollback the user could neither retry (FILE_LOCKED)
      // nor see the session's true entrypoint.
      reloadHistory();
    } catch (error) {
      console.error('[Frontend] Failed to parse conversion result:', error);
      addToast(tRef.current('history.convertFailed'), 'error');
      reloadHistory();
    }
  };
}
