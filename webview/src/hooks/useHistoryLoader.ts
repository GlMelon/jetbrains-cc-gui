import { sendAction } from '../bridge/typed';
import { UPSTREAM } from '../generated/protocol';
import { useEffect } from 'react';



export interface UseHistoryLoaderOptions {
  currentView: 'chat' | 'history' | 'settings';
  currentProvider: string;
}

export function useHistoryLoader(options: UseHistoryLoaderOptions): void {
  const { currentView, currentProvider } = options;

  useEffect(() => {
    if (currentView !== 'history') {
      return;
    }

    let historyRetryCount = 0;
    const MAX_HISTORY_RETRIES = 30;
    let currentTimer: ReturnType<typeof setTimeout> | null = null;

    const requestHistoryData = () => {
      if (window.sendToJava) {
        sendAction(UPSTREAM.LOAD_HISTORY_DATA, currentProvider);
      } else {
        historyRetryCount++;
        if (historyRetryCount < MAX_HISTORY_RETRIES) {
          currentTimer = setTimeout(requestHistoryData, 100);
        } else {
          console.warn('[Frontend] Failed to load history data: bridge not available after', MAX_HISTORY_RETRIES, 'retries');
        }
      }
    };

    currentTimer = setTimeout(requestHistoryData, 50);

    return () => {
      if (currentTimer) {
        clearTimeout(currentTimer);
      }
    };
  }, [currentView, currentProvider]);
}
