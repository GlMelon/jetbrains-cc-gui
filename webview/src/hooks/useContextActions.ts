import { useEffect } from 'react';
import { sendBridgeEvent } from '../utils/bridge';
import { insertNewlineAtCursor } from './useContextMenu';
import { bridgeHub, registerLegacyAlias } from '../bridge';

/**
 * Registers IDEA shortcut action handler (copy/cut/send/newline from Java-registered Actions).
 *
 * [归一化] execContextAction → context.action(裸字符串 action 参数,透明管道原样传递)。
 * cleanup 时取消 hub 订阅(避免 remount 时重复订阅泄漏)。
 */
export function useContextActions() {
  useEffect(() => {
    // [归一化] execContextAction → context.action
    registerLegacyAlias('execContextAction', 'context.action');
    const unsubscribe = bridgeHub.subscribe('context.action', (raw) => {
      const action = raw as string;
      switch (action) {
        case 'copy': {
          const activeEl = document.activeElement as HTMLInputElement | HTMLTextAreaElement | null;
          let text = '';
          if (activeEl && (activeEl.tagName === 'INPUT' || activeEl.tagName === 'TEXTAREA')) {
            text = activeEl.value.substring(activeEl.selectionStart ?? 0, activeEl.selectionEnd ?? 0);
          } else {
            text = window.getSelection()?.toString() ?? '';
          }
          if (text) {
            sendBridgeEvent('write_clipboard', text);
          }
          break;
        }
        case 'cut': {
          const activeEl = document.activeElement as HTMLInputElement | HTMLTextAreaElement | null;
          let text = '';
          if (activeEl && (activeEl.tagName === 'INPUT' || activeEl.tagName === 'TEXTAREA')) {
            const start = activeEl.selectionStart ?? 0;
            const end = activeEl.selectionEnd ?? 0;
            text = activeEl.value.substring(start, end);
            if (text) {
              activeEl.setRangeText('', start, end, 'end');
              activeEl.dispatchEvent(new Event('input', { bubbles: true }));
            }
          } else {
            text = window.getSelection()?.toString() ?? '';
            if (text) {
              document.execCommand('delete');
            }
          }
          if (text) {
            sendBridgeEvent('write_clipboard', text);
          }
          break;
        }
        case 'send': {
          document.dispatchEvent(new CustomEvent('ideaSend'));
          break;
        }
        case 'newline': {
          const activeEl = document.activeElement;
          if (activeEl && activeEl.getAttribute('contenteditable') === 'true') {
            insertNewlineAtCursor();
          }
          break;
        }
      }
    });

    return () => {
      unsubscribe();
      delete window.execContextAction;
    };
  }, []);
}
