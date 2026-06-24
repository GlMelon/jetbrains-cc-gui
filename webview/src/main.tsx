import { sendAction, subscribeEvent } from './bridge/typed';
import { UPSTREAM, DOWNSTREAM } from './generated/protocol';
import ReactDOM from 'react-dom/client';
import App from './App';
import ErrorBoundary from './components/ErrorBoundary';
import { MessagesProvider } from './contexts/MessagesContext';
import { SessionProvider } from './contexts/SessionContext';
import { UIStateProvider } from './contexts/UIStateContext';
import { DialogProvider } from './contexts/DialogContext';
import './codicon.css';
import './styles/app.less';
import './i18n/config';
import { setupSlashCommandsCallback } from './components/ChatInputBox/providers/slashCommandProvider';
import { setupDollarCommandsCallback } from './components/ChatInputBox/providers/dollarCommandProvider';
import { applyLinkifyCapabilitiesPayload } from './utils/linkifyCapabilities';
import { installRuntimeProviderDispatchers } from './utils/runtimeProviderCapabilities';
import { debugLog } from './utils/debug';

// Bootstrap modules
import { startBridgeHeartbeat } from './bootstrap/bridge';
import { initScaleRecovery } from './bootstrap/scaleRecovery';
import { initFonts } from './bootstrap/fonts';
import { initLanguage } from './bootstrap/language';
import { initAppearance } from './bootstrap/appearance';
import { registerPendingSlots } from './bootstrap/pendingSlots';

// 下行总线(Java → 前端)归一化入口。Phase 0:安装空壳(双轨,零行为变化)。
// 必须在一切其它 bootstrap 之前安装,以便(未来)后端早期推送能被缓冲。
// 详见 plan: typed-booping-newt.md。
import { installBridge, bridgeHub, registerLegacyAlias } from './bridge';

// Silence noisy console output in production (including third-party libs).
// console.error is preserved so ErrorBoundary and unhandled exceptions still
// surface in the IDE's webview devtools — silencing it would hide regressions.
if (!import.meta.env.DEV) {
  const noop = () => {};
  console.log = noop;
  console.debug = noop;
  console.info = noop;
  console.warn = noop;
}

// Install the runtime provider dispatcher exactly once so that every
// consumer (Settings, RuntimeProviderSelect, …) receives provider events
// through a deterministic subscriber registry instead of overriding
// `window.update*Provider*` callbacks ad-hoc.
installRuntimeProviderDispatchers();

// 下行总线:安装 window.__bridge 入口(幂等)。必须在 React 挂载与任何后端早期推送之前,
// 以便 dispatch 能缓冲。Phase 0 阶段无调用方,仅就位;握手时由 markReady() 回放缓冲。
installBridge();

// ---------------------------------------------------------------------------
// Bootstrap initialisation (order matters)
// ---------------------------------------------------------------------------

// Font config handlers must be ready before the Java bridge calls them.
initFonts();

// Language config handler must be ready before the Java bridge calls it.
initLanguage();

// Appearance config handler (cold-cache hydration from config.json).
initAppearance();

// Pre-register window callback placeholders so that bridge calls arriving
// before React mounts are not lost.
registerPendingSlots();

// vConsole debugging tool
const enableVConsole =
  import.meta.env.DEV || import.meta.env.VITE_ENABLE_VCONSOLE === 'true';

if (enableVConsole) {
  void import('vconsole').then(({ default: VConsole }) => {
    new VConsole();
    // Move vConsole button to top-left corner to avoid blocking the send button in the bottom-right
    setTimeout(() => {
      const vcSwitch = document.getElementById('__vconsole') as HTMLElement;
      if (vcSwitch) {
        vcSwitch.style.left = '10px';
        vcSwitch.style.right = 'auto';
        vcSwitch.style.top = '10px';
        vcSwitch.style.bottom = 'auto';
      }
    }, 100);
  });
}

// [归一化] updateLinkifyCapabilities → linkify.update(bootstrap 类,不进 React state)
registerLegacyAlias('updateLinkifyCapabilities', DOWNSTREAM.LINKIFY_UPDATE);
subscribeEvent(DOWNSTREAM.LINKIFY_UPDATE, (json) => applyLinkifyCapabilitiesPayload(json as string));

// ---------------------------------------------------------------------------
// React application rendering
// ---------------------------------------------------------------------------

ReactDOM.createRoot(document.getElementById('app') as HTMLElement).render(
  <ErrorBoundary>
    <UIStateProvider>
      <SessionProvider>
        <MessagesProvider>
          <DialogProvider>
            <App />
          </DialogProvider>
        </MessagesProvider>
      </SessionProvider>
    </UIStateProvider>
  </ErrorBoundary>,
);

// ---------------------------------------------------------------------------
// Post-render bootstrap
// ---------------------------------------------------------------------------

// Scale recovery listens for visibility/focus events to fix JCEF zoom glitches.
initScaleRecovery();

/**
 * Wait for the sendToJava bridge function to become available
 */
function waitForBridge(callback: () => void, maxAttempts = 50, interval = 100) {
  let attempts = 0;

  const check = () => {
    attempts++;
    if (window.sendToJava) {
      debugLog('[Main] Bridge available after ' + attempts + ' attempts');
      callback();
    } else if (attempts < maxAttempts) {
      setTimeout(check, interval);
    } else {
      console.error('[Main] Bridge not available after ' + maxAttempts + ' attempts');
    }
  };

  check();
}

// Once the bridge is available, initialize slash commands
waitForBridge(() => {
  debugLog('[Main] Bridge ready, setting up slash commands');
  setupSlashCommandsCallback();
  setupDollarCommandsCallback();
  startBridgeHeartbeat();

  // 下行总线握手:标记前端就绪并回放缓冲队列(替代散落的 window.__pendingXxx)。
  // Phase 0 阶段缓冲为空,此处仅建立契约;后续 Phase 迁移的回调才会真正利用缓冲。
  // 必须在发出 frontend_ready 之前完成,以免后端收到 ready 后立即推送时错过回放窗口。
  bridgeHub.markReady();

  debugLog('[Main] Sending frontend_ready signal');
  sendAction(UPSTREAM.FRONTEND_READY);

  debugLog('[Main] Sending refresh_slash_commands request');
  sendAction(UPSTREAM.REFRESH_SLASH_COMMANDS);

  // Ensure SDK dependency status is fetched on initial load (not only after opening Settings).
  debugLog('[Main] Requesting dependency status');
  sendAction(UPSTREAM.GET_DEPENDENCY_STATUS);

  sendAction(UPSTREAM.GET_LINKIFY_CAPABILITIES);
});
