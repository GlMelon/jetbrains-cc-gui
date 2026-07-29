import { act } from '@testing-library/react';
import type { MutableRefObject } from 'react';
import { bridgeHub } from '../../../../src/bridge';
import { DOWNSTREAM } from '../../../../src/generated/protocol';
import type { UseWindowCallbacksOptions } from '../../../../src/hooks/useWindowCallbacks';
import { registerSessionAndSdkCallbacks } from '../../../../src/hooks/windowCallbacks/registerCallbacks/sessionCallbacks';

const t = ((key: string) => key) as UseWindowCallbacksOptions['t'];

function createOptions(): UseWindowCallbacksOptions {
  return {
    addToast: vi.fn(),
    setCurrentSessionId: vi.fn(),
    setSdkStatus: vi.fn(),
    setSdkStatusLoaded: vi.fn(),
    setIsRewinding: vi.fn(),
    setRewindDialogOpen: vi.fn(),
    setCurrentRewindRequest: vi.fn(),
    customSessionTitleRef: { current: null },
    currentSessionIdRef: { current: null },
    currentProviderRef: { current: 'opencode' },
    updateHistoryTitle: vi.fn(),
    applyHistoryTitleLocal: vi.fn(),
    setCustomSessionTitle: vi.fn(),
    handleHistoryArchiveResult: vi.fn(),
  } as unknown as UseWindowCallbacksOptions;
}

describe('registerSessionAndSdkCallbacks history archive result', () => {
  beforeEach(() => {
    bridgeHub.reset();
    bridgeHub.markReady();
    window.sendToJava = vi.fn();
  });

  it('routes valid results and rejects malformed payloads', () => {
    const options = createOptions();
    const tRef = { current: t } as MutableRefObject<UseWindowCallbacksOptions['t']>;
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    registerSessionAndSdkCallbacks(options, tRef);
    const payload = {
      success: true,
      requestedSessionIds: ['history-1'],
      archivedSessionIds: ['history-1'],
      failedSessionIds: [],
    };

    act(() => {
      bridgeHub.dispatch(DOWNSTREAM.HISTORY_ARCHIVE_RESULT, JSON.stringify(payload));
    });

    expect(options.handleHistoryArchiveResult).toHaveBeenCalledWith(payload);
    expect(options.addToast).not.toHaveBeenCalledWith('history.archiveFailed', 'error');

    act(() => {
      bridgeHub.dispatch(DOWNSTREAM.HISTORY_ARCHIVE_RESULT, JSON.stringify({ success: true }));
    });

    expect(options.handleHistoryArchiveResult).toHaveBeenCalledTimes(1);
    expect(options.addToast).toHaveBeenCalledWith('history.archiveFailed', 'error');
    expect(consoleError).toHaveBeenCalled();
    consoleError.mockRestore();
  });
});
