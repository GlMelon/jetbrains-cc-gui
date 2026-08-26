// @ts-check
/**
 * Codex Message Service — CLI-based implementation
 *
 * Spawns the local Codex CLI (`codex`) in headless streaming-json mode and
 * maps its NDJSON output onto the shared bridge marker protocol.
 *
 * @author Crafted with geek spirit
 */

import { spawn, spawnSync } from 'child_process';
import { randomUUID } from 'crypto';
import { createInterface } from 'readline';
import { CodexPermissionMapper } from '../../utils/permission-mapper.js';
import { getMcpServerTools as getMcpServerToolsImpl } from '../claude/mcp-status/index.js';
import {
  logDebug, logInfo, logWarn,
  normalizeCodexPermissionMode,
  resolveSandboxModeOverride,
  resolveApprovalPolicyOverride,
  buildCodexCliEnvironment,
  buildErrorPayload,
} from './codex-utils.js';
import { collectAgentsInstructions } from './codex-agents-loader.js';
import { killChildTree } from '../../utils/kill-tree.js';

// ========== Constants ==========

const CODEX_THREAD_MAX_IDLE_MS = 30 * 60 * 1000;

// ========== CLI Path Resolution ==========

/**
 * Resolve the Codex CLI executable path.
 * @returns {string}
 */
function resolveCodexCliPath() {
  const envPath = process.env.CODEX_CODE_PATH;
  if (envPath && envPath.trim()) return envPath.trim();
  return 'codex';
}

/**
 * Synchronous path probe — returns true if the CLI binary exists and is executable.
 * @param {string} bin
 */
function probeCliBin(bin) {
  try {
    const r = spawnSync(bin, ['--version'], {
      timeout: 5000,
      stdio: ['pipe', 'pipe', 'pipe'],
      env: { ...process.env, NO_COLOR: '1' },
    });
    return r.status === 0;
  } catch {
    return false;
  }
}

// ========== Process Lifecycle ==========
//
// Process-tree kill is shared: see utils/kill-tree.js.

// ========== Abort Support ==========

/** @type {AbortController | null} */
let activeCodexAbortController = null;
let activeCodexTurnInProgress = false;
let activeCodexAbortRequested = false;
/** @type {Promise<void> | null} */
let activeCodexTurnCompletionPromise = null;

export async function abortCurrentCodexTurn() {
  activeCodexAbortRequested = true;
  const controller = activeCodexAbortController;
  if (!controller) {
    return activeCodexTurnInProgress;
  }
  activeCodexAbortController = null;
  controller.abort();
  return true;
}

/** @returns {Promise<void>} */
export function waitForCodexTurnCompletion() {
  return activeCodexTurnCompletionPromise || Promise.resolve();
}

/** @param {any} error @returns {boolean} */
function isCodexUserAbortError(error) {
  const message = `${error?.name || ''}\n${error?.code || ''}\n${error?.message || ''}`;
  return /AbortError|ABORT_ERR|aborted|abort|cancel|interrupt/i.test(message);
}

/** @returns {Error & { code: string }} */
function createCodexAbortError() {
  const error = /** @type {Error & { code: string }} */ (new Error('Codex turn aborted by user'));
  error.name = 'AbortError';
  error.code = 'ABORT_ERR';
  return error;
}

function throwIfCodexAbortRequested() {
  if (activeCodexAbortRequested) {
    throw createCodexAbortError();
  }
}

// ========== CLI Stream Parser ==========

/**
 * Parse a single stream-json line from Codex CLI.
 * @param {string} line
 * @returns {{ kind: string; [key: string]: any }}
 */
function parseStreamLine(line) {
  if (!line || !line.trim()) return { kind: 'other' };
  let value;
  try {
    value = JSON.parse(line);
  } catch {
    return { kind: 'other' };
  }
  if (!value || typeof value !== 'object') return { kind: 'other' };

  const type = typeof value.type === 'string' ? value.type : '';
  switch (type) {
    case 'message':
      return { kind: 'message', content: value.content, role: value.role };
    case 'result':
      return { kind: 'result', threadId: value.thread_id, success: value.success, error: value.error, usage: value.usage };
    case 'error':
      return { kind: 'error', message: value.message || value.error };
    case 'usage':
      return { kind: 'usage', usage: value.usage };
    default:
      return { kind: 'other', raw: value };
  }
}

// ========== CLI Argument Builder ==========

/**
 * Build Codex CLI arguments.
 * @param {object} params
 * @param {string} params.message
 * @param {string} [params.threadId]
 * @param {string} [params.model]
 * @param {string} [params.reasoningEffort]
 * @param {string} [params.approvalPolicy]
 * @param {string} [params.sandboxMode]
 * @returns {string[]}
 */
function buildCodexArgs({ message, threadId, model, reasoningEffort, approvalPolicy, sandboxMode }) {
  const args = [
    '-p', message || '',
    '--output-format', 'stream-json',
  ];

  if (model) {
    args.push('-m', model);
  }

  if (reasoningEffort) {
    args.push('--reasoning-effort', reasoningEffort);
  }

  if (approvalPolicy) {
    args.push('--approval-policy', approvalPolicy);
  }

  if (sandboxMode) {
    args.push('--sandbox', sandboxMode);
  }

  if (threadId && threadId.trim()) {
    args.push('--resume', threadId);
  } else {
    const newId = randomUUID();
    args.push('-s', newId);
  }

  return args;
}

// ========== Exported API ==========

/** @param {string | null} [threadId] @returns {void} */
export function resetCodexThreadCache(threadId = null) {
  // No-op: thread cache is no longer needed with CLI mode
}

export function getCodexThreadCacheSizeForTest() {
  return 0;
}

/** @param {string} line @returns {boolean} */
export function isIgnorableCodexEventNoiseLine(_line) {
  return false;
}

/**
 * Send message to Codex via CLI.
 *
 * @param {string} message - User message to send
 * @param {string | null} [threadId] - Thread ID to resume (optional)
 * @param {string | null} [cwd] - Working directory (optional)
 * @param {string | null} [permissionMode] - Unified permission mode (optional)
 * @param {string | null} [model] - Model name (optional)
 * @param {string | null} [baseUrl] - API base URL (optional, ignored in CLI mode)
 * @param {string | null} [apiKey] - API key (optional, ignored in CLI mode)
 * @param {string} [reasoningEffort] - Reasoning effort level (optional)
 * @param {string | null} [serviceTier] - Codex service tier (optional, ignored in CLI mode)
 * @param {Array<any>} [attachments] - Image attachments (optional, ignored in CLI mode)
 * @returns {Promise<void>}
 */
export async function sendMessage(
  message,
  threadId = null,
  cwd = null,
  permissionMode = null,
  model = null,
  baseUrl = null,
  apiKey = null,
  reasoningEffort = 'high',
  serviceTier = null,
  attachments = []
) {
  let streamStarted = false;
  let streamEnded = false;
  let turnAbortController = new AbortController();
  let turnCompletionResolve = /** @type {null | (() => void)} */ (null);
  activeCodexTurnCompletionPromise = new Promise(resolve => { turnCompletionResolve = resolve; });
  activeCodexTurnInProgress = true;
  activeCodexAbortRequested = false;
  activeCodexAbortController = turnAbortController;

  let currentThreadId = threadId || null;
  let assistantText = '';

  const emitStreamEndOnce = () => {
    if (!streamStarted || streamEnded) return;
    streamEnded = true;
    console.log('[STREAM_END]');
  };

  const emitMessage = (msg) => {
    console.log('[MESSAGE]', JSON.stringify(msg));
  };

  try {
    const normalizedPermissionMode = normalizeCodexPermissionMode(permissionMode || 'default');
    const permissionConfig = CodexPermissionMapper.toProvider(normalizedPermissionMode);

    // Allow Java side to force sandbox mapping override via env vars
    const sandboxOverride = resolveSandboxModeOverride();
    if (sandboxOverride) {
      permissionConfig.sandbox = sandboxOverride;
    }
    const approvalPolicyOverride = resolveApprovalPolicyOverride();
    if (approvalPolicyOverride) {
      permissionConfig.approvalPolicy = approvalPolicyOverride;
    }

    console.log('[DEBUG] Codex sendMessage (CLI mode) called with params:', {
      threadId,
      cwd,
      permissionMode: normalizedPermissionMode,
      model,
      reasoningEffort,
      serviceTier,
    });

    console.log('[MESSAGE_START]');
    throwIfCodexAbortRequested();

    // Resolve CLI path
    const bin = resolveCodexCliPath();
    if (!probeCliBin(bin)) {
      const errorPayload = buildErrorPayload(new Error('Codex CLI not found. Install Codex CLI and ensure `codex` is on PATH.'));
      console.error('[SEND_ERROR]', JSON.stringify(errorPayload));
      console.log(JSON.stringify(errorPayload));
      return;
    }

    // Build message with AGENTS.md instructions
    let finalMessage = message;
    if (cwd && cwd.trim() !== '') {
      const agentsInstructions = collectAgentsInstructions(cwd);
      if (agentsInstructions) {
        finalMessage = `<agents-instructions>\n${agentsInstructions}\n</agents-instructions>\n\n${message}`;
        logDebug('AGENTS.md', `Prepended ${agentsInstructions.length} chars of instructions to message`);
      }
    }

    // Build CLI args
    const args = buildCodexArgs({
      message: finalMessage,
      threadId: currentThreadId || '',
      model: model || '',
      reasoningEffort: reasoningEffort || '',
      approvalPolicy: permissionConfig.approvalPolicy || '',
      sandboxMode: permissionConfig.sandbox || '',
    });

    // If we pre-assigned a new session id, surface it
    const sessionFlagIndex = args.indexOf('-s');
    if (sessionFlagIndex >= 0 && args[sessionFlagIndex + 1]) {
      currentThreadId = args[sessionFlagIndex + 1];
      console.log(`[SESSION_ID] ${currentThreadId}`);
    } else if (currentThreadId) {
      console.log(`[SESSION_ID] ${currentThreadId}`);
    }

    // Build environment
    const { cliEnv } = buildCodexCliEnvironment(process.env);
    const env = { ...process.env, ...cliEnv, CODEX_NO_COLOR: '1', CODEX_USE_STDIN: '1' };

    const workCwd = cwd && cwd.trim() !== '' ? cwd : process.cwd();

    console.error(`[DEBUG][Codex CLI] spawn ${bin} ${args.slice(0, 4).join(' ')}... promptLen=${finalMessage.length}`);

    console.log('[STREAM_START]');
    streamStarted = true;

    await new Promise((resolve) => {
      let child;
      try {
        child = spawn(bin, args, {
          cwd: workCwd,
          env,
          stdio: ['pipe', 'pipe', 'pipe'],
          detached: process.platform !== 'win32',
        });
      } catch (error) {
        const errorPayload = buildErrorPayload(error instanceof Error ? error : new Error(String(error)));
        console.error('[SEND_ERROR]', JSON.stringify(errorPayload));
        console.log(JSON.stringify(errorPayload));
        emitStreamEndOnce();
        resolve();
        return;
      }

      // Wire abort signal to child process
      turnAbortController.signal.addEventListener('abort', () => {
        killChildTree(child);
      });

      const onParentSignal = () => killChildTree(child);
      process.once('SIGTERM', onParentSignal);
      process.once('SIGINT', onParentSignal);
      process.once('SIGHUP', onParentSignal);

      const stdoutRl = createInterface({ input: child.stdout });
      let stderrTail = '';

      stdoutRl.on('line', (line) => {
        if (activeCodexAbortRequested) return;

        const event = parseStreamLine(line);
        switch (event.kind) {
          case 'message':
            if (event.content && event.role === 'assistant') {
              assistantText = typeof event.content === 'string' ? event.content : JSON.stringify(event.content);
              emitMessage({
                type: 'assistant',
                message: { role: 'assistant', content: event.content }
              });
            }
            break;
          case 'result':
            if (event.threadId) {
              currentThreadId = event.threadId;
              console.log(`[SESSION_ID] ${event.threadId}`);
            }
            if (event.error) {
              const errorPayload = buildErrorPayload(new Error(event.error));
              console.error('[SEND_ERROR]', JSON.stringify(errorPayload));
            }
            break;
          case 'error':
            const errPayload = buildErrorPayload(new Error(event.message));
            console.error('[SEND_ERROR]', JSON.stringify(errPayload));
            break;
          case 'usage':
            if (event.usage) {
              console.log(`[USAGE] ${JSON.stringify(event.usage)}`);
            }
            break;
          default:
            break;
        }
      });

      child.stderr.on('data', (chunk) => {
        const text = chunk.toString();
        stderrTail = (stderrTail + text).slice(-4000);
        process.stderr.write(text);
      });

      child.on('error', (error) => {
        const hint = error?.code === 'ENOENT'
          ? 'Codex CLI not found. Install Codex CLI and ensure `codex` is on PATH.'
          : (error?.message || String(error));
        const errorPayload = buildErrorPayload(new Error(hint));
        console.error('[SEND_ERROR]', JSON.stringify(errorPayload));
        console.log(JSON.stringify(errorPayload));
      });

      child.on('close', (code, signal) => {
        process.off('SIGTERM', onParentSignal);
        process.off('SIGINT', onParentSignal);
        process.off('SIGHUP', onParentSignal);

        if (activeCodexAbortRequested) {
          console.log('[MESSAGE_END]');
          console.log(JSON.stringify({ success: false, error: 'User interrupted', threadId: currentThreadId }));
        } else if (code !== 0 && signal !== 'SIGTERM' && signal !== 'SIGINT') {
          const tail = stderrTail.trim().slice(-800);
          const errorPayload = buildErrorPayload(
            new Error(`Codex CLI exited with code ${code}${signal ? ` (signal ${signal})` : ''}` + (tail ? `\n${tail}` : ''))
          );
          console.error('[SEND_ERROR]', JSON.stringify(errorPayload));
          console.log(JSON.stringify(errorPayload));
        } else {
          console.log('[MESSAGE_END]');
          console.log(JSON.stringify({
            success: true,
            threadId: currentThreadId,
            result: assistantText
          }));
        }

        emitStreamEndOnce();
        resolve();
      });
    });

  } catch (error) {
    emitStreamEndOnce();
    if (activeCodexAbortRequested && isCodexUserAbortError(error)) {
      logInfo('CODEX_ABORT', `Codex turn interrupted: ${error instanceof Error ? error.message : error}`);
      console.log('[MESSAGE_END]');
      console.log(JSON.stringify({ success: false, error: 'User interrupted' }));
      return;
    }
    const errorObj = error instanceof Error ? error : new Error(String(error));
    console.error('[DEBUG] Error:', errorObj.message);
    console.error('[DEBUG] Error stack:', errorObj.stack);
    const errorPayload = buildErrorPayload(errorObj);
    console.error('[SEND_ERROR]', JSON.stringify(errorPayload));
    console.log(JSON.stringify(errorPayload));
  } finally {
    if (activeCodexAbortController === turnAbortController) {
      activeCodexAbortController = null;
    }
    activeCodexTurnInProgress = false;
    activeCodexAbortRequested = false;
    if (activeCodexTurnCompletionPromise) {
      activeCodexTurnCompletionPromise = null;
    }
    if (turnCompletionResolve) {
      turnCompletionResolve();
      turnCompletionResolve = null;
    }
  }
}

// ---------------------------------------------------------------------------
// getMcpServerTools
// ---------------------------------------------------------------------------

/**
 * Gets the tools list for a Codex MCP server.
 *
 * @param {string} serverId
 * @param {object} rawServerConfig
 * @returns {Promise<void>}
 */
export async function getMcpServerTools(serverId, rawServerConfig) {
  try {
    if (!serverId) {
      const invalid = { success: false, serverId: '', error: 'Missing serverId', tools: [] };
      console.log('[MCP_SERVER_TOOLS]' + JSON.stringify(invalid));
      console.log(JSON.stringify(invalid));
      return;
    }

    if (!rawServerConfig || typeof rawServerConfig !== 'object') {
      const invalid = { success: false, serverId, error: 'Missing serverConfig', tools: [] };
      console.log('[MCP_SERVER_TOOLS]' + JSON.stringify(invalid));
      console.log(JSON.stringify(invalid));
      return;
    }

    const serverConfig = normalizeCodexMcpConfig(rawServerConfig);
    /** @type {any} */
    const toolsResult = await getMcpServerToolsImpl(serverId, serverConfig);
    const tools = Array.isArray(toolsResult?.tools) ? toolsResult.tools : [];
    const hasError = !!toolsResult?.error;

    const result = {
      success: !hasError || tools.length > 0,
      serverId,
      serverName: toolsResult?.name || serverId,
      tools,
      error: toolsResult?.error || null
    };

    const resultJson = JSON.stringify(result);
    console.log('[MCP_SERVER_TOOLS]' + resultJson);
    console.log(resultJson);
  } catch (error) {
    const errorResult = {
      success: false,
      serverId: serverId || '',
      error: error instanceof Error ? error.message : String(error),
      tools: []
    };
    const resultJson = JSON.stringify(errorResult);
    console.log('[MCP_SERVER_TOOLS]' + resultJson);
    console.log(resultJson);
  }
}

/**
 * Converts Codex config field names to a format recognized by mcp-status-service.
 * @param {Record<string, any>} raw
 * @returns {Record<string, any>}
 */
function normalizeCodexMcpConfig(raw) {
  /** @type {Record<string, any>} */
  const normalized = { ...raw };
  const type = normalized.type || (normalized.url ? 'http' : 'stdio');
  normalized.type = type;

  if (!normalized.headers && normalized.http_headers && typeof normalized.http_headers === 'object') {
    normalized.headers = { ...normalized.http_headers };
  }

  if (normalized.env_http_headers && typeof normalized.env_http_headers === 'object') {
    /** @type {Record<string, string>} */
    const fromEnv = {};
    for (const [headerName, envName] of Object.entries(/** @type {Record<string, any>} */ (normalized.env_http_headers))) {
      if (typeof envName === 'string') {
        const envValue = process.env[envName];
        if (envValue) {
          fromEnv[headerName] = envValue;
        }
      }
    }
    normalized.headers = { ...(normalized.headers || {}), ...fromEnv };
  }

  if (normalized.bearer_token_env_var && typeof normalized.bearer_token_env_var === 'string') {
    const token = process.env[normalized.bearer_token_env_var];
    if (token && !(normalized.headers && normalized.headers.Authorization)) {
      normalized.headers = { ...(normalized.headers || {}), Authorization: `Bearer ${token}` };
    }
  }

  return normalized;
}
