import { sendAction } from '../../../bridge/typed';
import { subscribeEvent } from '../../../bridge/typed';
import { UPSTREAM, DOWNSTREAM } from '../../../generated/protocol';
import type { CommandItem, DropdownItemData } from '../types';
import i18n from '../../../i18n/config';
import { debugError, debugLog, debugWarn } from '../../../utils/debug.js';

/**
 * Local command list (commands to be filtered out of the dropdown).
 * /new, /reset and /continue are hidden aliases: the backend includes them in the
 * command list (with localAction) so typed input resolves, but the dropdown only
 * shows the canonical /clear and /resume entries.
 */
const HIDDEN_COMMANDS = new Set([
  '/cost',
  '/pr-comments',
  '/release-notes',
  '/security-review',
  '/todo',
  '/doctor',
  '/new',
  '/reset',
  '/continue',
]);

// ============================================================================
// State Management
// ============================================================================

type LoadingState = 'idle' | 'loading' | 'success' | 'failed';

let cachedSdkCommands: CommandItem[] = [];
let loadingState: LoadingState = 'idle';
let lastRefreshTime = 0;
let callbackRegistered = false;
let retryCount = 0;
let pendingWaiters: Array<{ resolve: () => void; reject: (error: unknown) => void }> = [];
const MIN_REFRESH_INTERVAL = 2000;
const LOADING_TIMEOUT = 30000; // Increased to 30s to handle slow initial load for some Windows users
const MAX_RETRY_COUNT = 3;
/** Shorter timeout for send-time local command lookup — must not block message sending. */
const LOCAL_LOOKUP_TIMEOUT = 5000;

const WHITESPACE_REGEX = /\s+/;

// ============================================================================
// Core Functions
// ============================================================================

export function resetSlashCommandsState() {
  cachedSdkCommands = [];
  loadingState = 'idle';
  lastRefreshTime = 0;
  retryCount = 0;
  pendingWaiters.forEach(w => w.reject(new Error('Slash commands state reset')));
  pendingWaiters = [];
  debugLog('[SlashCommand] State reset');
}

interface SDKSlashCommand {
  name: string;
  description?: string;
  source?: string;
  /** Backend-annotated local action (LocalSlashAction value); absent = forward to CLI. */
  localAction?: string;
}

export function setupSlashCommandsCallback() {
  if (typeof window === 'undefined') return;
  if (callbackRegistered) return;

  const handler = (json: string) => {
    debugLog('[SlashCommand] Received data from backend, length=' + json.length);

    try {
      const parsed = JSON.parse(json);
      let commands: CommandItem[] = [];

      if (Array.isArray(parsed)) {
        if (parsed.length > 0) {
          if (typeof parsed[0] === 'object' && parsed[0] !== null && 'name' in parsed[0]) {
            const sdkCommands: SDKSlashCommand[] = parsed;
            commands = sdkCommands.map(cmd => ({
              id: cmd.name.replace(/^\//, ''),
              label: cmd.name.startsWith('/') ? cmd.name : `/${cmd.name}`,
              description: formatCommandDescription(cmd.description || '', cmd.source),
              category: getCategoryFromCommand(cmd.name),
              localAction: typeof cmd.localAction === 'string' && cmd.localAction.length > 0
                ? cmd.localAction
                : undefined,
            }));
          } else if (typeof parsed[0] === 'string') {
            const commandNames: string[] = parsed;
            commands = commandNames.map(name => ({
              id: name.replace(/^\//, ''),
              label: name.startsWith('/') ? name : `/${name}`,
              description: '',
              category: getCategoryFromCommand(name),
            }));
          }
        }

        cachedSdkCommands = commands;
        loadingState = 'success';
        retryCount = 0;
        pendingWaiters.forEach(w => w.resolve());
        pendingWaiters = [];
        debugLog('[SlashCommand] Successfully loaded ' + commands.length + ' commands');
      } else {
        loadingState = 'failed';
        const error = new Error('Slash commands payload is not an array');
        pendingWaiters.forEach(w => w.reject(error));
        pendingWaiters = [];
        debugWarn('[SlashCommand] Invalid commands payload');
      }
    } catch (error) {
      loadingState = 'failed';
      pendingWaiters.forEach(w => w.reject(error));
      pendingWaiters = [];
      debugError('[SlashCommand] Failed to parse commands:', error);
    }
  };

  // [归一化] 经 bridgeHub 订阅 slash.commands 事件,替代旧 window.updateSlashCommands 回调。
  subscribeEvent(DOWNSTREAM.SLASH_COMMANDS, (json) => handler(json as string));
  callbackRegistered = true;
  debugLog('[SlashCommand] Callback registered');
}

function waitForSlashCommands(signal: AbortSignal, timeoutMs: number): Promise<void> {
  if (loadingState === 'success') return Promise.resolve();

  return new Promise<void>((resolve, reject) => {
    if (signal.aborted) {
      reject(new DOMException('Aborted', 'AbortError'));
      return;
    }

    const waiter = { resolve: () => {}, reject: (_error: unknown) => {} } as {
      resolve: () => void;
      reject: (error: unknown) => void;
    };

    const cleanup = () => {
      pendingWaiters = pendingWaiters.filter(w => w !== waiter);
      clearTimeout(timeoutId);
      signal.removeEventListener('abort', onAbort);
    };

    const onAbort = () => {
      cleanup();
      reject(new DOMException('Aborted', 'AbortError'));
    };

    const timeoutId = window.setTimeout(() => {
      cleanup();
      reject(new Error('Slash commands loading timeout'));
    }, timeoutMs);

    signal.addEventListener('abort', onAbort, { once: true });

    waiter.resolve = () => {
      cleanup();
      resolve();
    };
    waiter.reject = (error: unknown) => {
      cleanup();
      reject(error);
    };

    pendingWaiters.push(waiter);
    if (loadingState === 'success') {
      waiter.resolve();
    } else if (loadingState === 'failed') {
      waiter.reject(new Error('Slash commands loading failed'));
    }
  });
}

function requestRefresh(): boolean {
  const now = Date.now();

  if (now - lastRefreshTime < MIN_REFRESH_INTERVAL) {
    debugLog('[SlashCommand] Skipping refresh (too soon)');
    return false;
  }

  if (retryCount >= MAX_RETRY_COUNT) {
    debugWarn('[SlashCommand] Max retry count reached');
    loadingState = 'failed';
    return false;
  }

  const attempt = retryCount + 1;
  const sent = sendAction(UPSTREAM.REFRESH_SLASH_COMMANDS);
  if (!sent) {
    debugLog('[SlashCommand] Bridge not available yet, refresh not sent');
    return false;
  }

  lastRefreshTime = now;
  loadingState = 'loading';
  retryCount = attempt;

  debugLog('[SlashCommand] Requesting refresh from backend (attempt ' + retryCount + '/' + MAX_RETRY_COUNT + ')');
  return true;
}

function isHiddenCommand(name: string): boolean {
  const normalized = name.startsWith('/') ? name : `/${name}`;
  if (HIDDEN_COMMANDS.has(normalized)) return true;
  const baseName = normalized.split(' ')[0];
  return HIDDEN_COMMANDS.has(baseName);
}

function getCategoryFromCommand(name: string): string {
  const lowerName = name.toLowerCase();
  if (lowerName.includes('workflow')) return 'workflow';
  if (lowerName.includes('memory') || lowerName.includes('skill')) return 'memory';
  if (lowerName.includes('task')) return 'task';
  if (lowerName.includes('speckit')) return 'speckit';
  if (lowerName.includes('cli')) return 'cli';
  return 'user';
}

function formatCommandDescription(description: string, source?: string): string {
  if (!source) return description;
  const suffix = `[${source}]`;
  if (!description) return suffix;
  return `${description} ${suffix}`;
}

function filterCommands(commands: CommandItem[], query: string): CommandItem[] {
  const visibleCommands = commands.filter(cmd => !isHiddenCommand(cmd.label));

  if (!query) return visibleCommands;

  const lowerQuery = query.toLowerCase();
  return visibleCommands.filter(cmd =>
    cmd.label.toLowerCase().includes(lowerQuery) ||
    cmd.description?.toLowerCase().includes(lowerQuery) ||
    cmd.id.toLowerCase().includes(lowerQuery)
  );
}

export async function slashCommandProvider(
  query: string,
  signal: AbortSignal
): Promise<CommandItem[]> {
  if (signal.aborted) {
    throw new DOMException('Aborted', 'AbortError');
  }

  setupSlashCommandsCallback();

  const now = Date.now();

  if (loadingState === 'idle' || loadingState === 'failed') {
    requestRefresh();
  } else if (loadingState === 'loading' && now - lastRefreshTime > LOADING_TIMEOUT) {
    debugWarn('[SlashCommand] Loading timeout');
    loadingState = 'failed';
    requestRefresh();
  }

  if (loadingState !== 'success') {
    await waitForSlashCommands(signal, LOADING_TIMEOUT).catch(() => {});
  }

  if (loadingState === 'success') {
    return filterCommands(cachedSdkCommands, query);
  }

  if (retryCount >= MAX_RETRY_COUNT) {
    return [{
      id: '__error__',
      label: i18n.t('chat.loadingFailed'),
      description: i18n.t('chat.pleaseCloseAndReopen'),
      category: 'system',
    }];
  }

  return [{
    id: '__loading__',
    label: i18n.t('chat.loadingSlashCommands'),
    description: retryCount > 0 ? i18n.t('chat.retrying', { count: retryCount, max: MAX_RETRY_COUNT }) : i18n.t('chat.pleaseWait'),
    category: 'system',
  }];
}

/**
 * Resolve a typed slash command text to a backend-annotated local command.
 *
 * The backend (SlashCommandRegistry) is the SSOT for which commands are handled
 * locally: it annotates the command payload with `localAction`. This lookup only
 * reads that metadata — no frontend-side command table. Returns null when the
 * command is unknown or has no local action (i.e. forward to the CLI as plain text),
 * or when the command list cannot be loaded within LOCAL_LOOKUP_TIMEOUT.
 */
export async function resolveLocalSlashCommand(text: string): Promise<CommandItem | null> {
  if (!text.startsWith('/')) return null;
  const commandLabel = text.split(WHITESPACE_REGEX)[0].toLowerCase();

  setupSlashCommandsCallback();

  if (loadingState === 'idle' || loadingState === 'failed') {
    requestRefresh();
  }

  if (loadingState !== 'success') {
    const controller = new AbortController();
    await waitForSlashCommands(controller.signal, LOCAL_LOOKUP_TIMEOUT).catch(() => {});
  }

  if (loadingState !== 'success') return null;

  const match = cachedSdkCommands.find(cmd => cmd.label.toLowerCase() === commandLabel);
  return match && match.localAction ? match : null;
}

/**
 * List the visible (non-hidden) slash commands for display purposes (e.g. /help).
 * Ensures the cache is loaded; returns an empty list on failure.
 */
export async function listVisibleSlashCommands(): Promise<CommandItem[]> {
  setupSlashCommandsCallback();

  if (loadingState === 'idle' || loadingState === 'failed') {
    requestRefresh();
  }

  if (loadingState !== 'success') {
    const controller = new AbortController();
    await waitForSlashCommands(controller.signal, LOCAL_LOOKUP_TIMEOUT).catch(() => {});
  }

  if (loadingState !== 'success') return [];
  return filterCommands(cachedSdkCommands, '');
}

export function commandToDropdownItem(command: CommandItem): DropdownItemData {
  return {
    id: command.id,
    label: command.label,
    description: command.description,
    icon: 'codicon-terminal',
    type: 'command',
    data: { command },
  };
}



/**
 * Preload slash commands during app initialization
 * Load command data before user types "/" to improve perceived performance
 *
 * Safety guarantees:
 * - Skips if already loading or loaded (checks loadingState)
 * - requestRefresh() has MIN_REFRESH_INTERVAL deduplication protection
 * - Shares state with slashCommandProvider, subsequent calls hit cache directly
 */
export function preloadSlashCommands(): void {
  // Only preload in idle state, don't interfere with in-progress or completed loads
  if (loadingState !== 'idle') {
    debugLog('[SlashCommand] Preload skipped (state=' + loadingState + ')');
    return;
  }

  debugLog('[SlashCommand] Preloading commands on app init');

  // Ensure callback is registered before requesting refresh
  setupSlashCommandsCallback();

  // Request refresh -- built-in deduplication protection
  requestRefresh();
}
