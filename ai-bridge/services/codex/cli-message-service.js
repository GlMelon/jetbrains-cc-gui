// @ts-check
/**
 * Codex CLI Message Service - Replaces SDK-based message sending
 *
 * Spawns the local Codex CLI in headless mode and maps streaming-json
 * NDJSON events onto the shared bridge marker protocol:
 *
 *   [MESSAGE_START]
 *   [STREAM_START]
 *   [CONTENT_DELTA] "<json-string>"
 *   [SESSION_ID] <uuid>
 *   [USAGE] { ... }
 *   [STREAM_END]
 *   [MESSAGE_END]
 *   [SEND_ERROR] { "error": "..." }
 *
 * CLI usage:
 *   codex -p "<prompt>" --output-format stream-json --approval-policy never
 *        [-m <model>] [--reasoning-effort low|medium|high]
 *        (-s <new-uuid> | --resume <existing-uuid>)
 */

import { spawn } from 'child_process';
import { randomUUID } from 'crypto';
import { createInterface } from 'readline';
import { buildCodexCliEnvironment } from './codex-utils.js';
import {
  emitJsonStringMarker,
  emitSessionId,
  isNonEmptySessionId,
} from '../../utils/marker-protocol.js';

function logDebug(...args) {
  console.error('[DEBUG][Codex CLI]', ...args);
}

function logWarn(...args) {
  console.error('[WARN][Codex CLI]', ...args);
}

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
      return { kind: 'result', threadId: value.thread_id, success: value.success, error: value.error };
    case 'error':
      return { kind: 'error', message: value.message };
    case 'usage':
      return { kind: 'usage', usage: value.usage };
    default:
      return { kind: 'other', raw: value };
  }
}

/**
 * Build Codex CLI arguments.
 * @param {object} options
 * @param {string} options.message
 * @param {string} [options.threadId]
 * @param {string} [options.model]
 * @param {string} [options.reasoningEffort]
 * @param {string} [options.approvalPolicy]
 * @param {string} [options.sandboxMode]
 * @returns {string[]}
 */
function buildCodexArgs({ message, threadId, model, reasoningEffort, approvalPolicy, sandboxMode }) {
  const args = [
    '--output-format', 'stream-json',
    '-p', message || '',
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

  if (threadId && isNonEmptySessionId(threadId)) {
    args.push('--resume', threadId);
  } else {
    const newId = randomUUID();
    args.push('-s', newId);
  }

  return args;
}

/**
 * Kill child process tree.
 * @param {import('child_process').ChildProcess} child
 */
function killChildTree(child) {
  if (!child || child.killed) return;
  try {
    if (process.platform === 'win32') {
      child.kill();
    } else {
      try {
        process.kill(-child.pid, 'SIGTERM');
      } catch {
        child.kill('SIGTERM');
      }
    }
  } catch (error) {
    logWarn('Failed to kill codex child:', error?.message || error);
  }
}

/**
 * Send a message via Codex CLI and stream markers to stdout.
 *
 * @param {string} message
 * @param {string} threadId  Existing UUID to resume, or empty for a new session
 * @param {string} cwd
 * @param {string} permissionMode
 * @param {string} model
 * @param {string} reasoningEffort
 */
export async function sendMessage(
  message,
  threadId = '',
  cwd = '',
  permissionMode = '',
  model = '',
  reasoningEffort = 'medium'
) {
  let streamStarted = false;
  let streamEnded = false;
  let hadError = false;
  let resolvedThreadId = threadId || null;

  const emitStreamEndOnce = () => {
    if (!streamStarted || streamEnded) return;
    streamEnded = true;
    console.log('[STREAM_END]');
    console.log('[MESSAGE_END]');
  };

  console.log('[MESSAGE_START]');
  console.log('[STREAM_START]');
  streamStarted = true;

  // Resolve Codex CLI path
  const bin = resolveCodexCliPath();
  if (!bin) {
    hadError = true;
    emitSendError('Codex CLI not found. Please install Codex CLI and ensure it is on PATH.');
    emitStreamEndOnce();
    return;
  }

  // Build permission config
  const approvalPolicy = permissionMode === 'bypassPermissions' ? 'never' : 'on-request';
  const sandboxMode = permissionMode === 'bypassPermissions' ? 'danger-full-access' : 'workspace-write';

  const args = buildCodexArgs({
    message,
    threadId,
    model,
    reasoningEffort,
    approvalPolicy,
    sandboxMode,
  });

  // If we pre-assigned a new session id via -s, surface it immediately.
  const sessionFlagIndex = args.indexOf('-s');
  if (sessionFlagIndex >= 0 && args[sessionFlagIndex + 1]) {
    resolvedThreadId = args[sessionFlagIndex + 1];
    console.log(`[SESSION_ID] ${resolvedThreadId}`);
  } else if (resolvedThreadId) {
    console.log(`[SESSION_ID] ${resolvedThreadId}`);
  }

  logDebug('spawn', bin, args.slice(0, 5).join(' '), `promptLen=${String(message || '').length}`);

  const env = {
    ...process.env,
    ...buildCodexCliEnvironment(process.env).cliEnv,
    CODEX_NO_COLOR: '1',
  };

  const workCwd = cwd && cwd !== 'undefined' && cwd !== 'null' ? cwd : process.cwd();

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
      hadError = true;
      emitSendError(`Failed to spawn Codex CLI (${bin}): ${error?.message || error}`);
      emitStreamEndOnce();
      resolve();
      return;
    }

    const onParentSignal = () => killChildTree(child);
    process.once('SIGTERM', onParentSignal);
    process.once('SIGINT', onParentSignal);
    process.once('SIGHUP', onParentSignal);

    const stdoutRl = createInterface({ input: child.stdout });
    let stderrTail = '';

    stdoutRl.on('line', (line) => {
      const event = parseStreamLine(line);
      switch (event.kind) {
        case 'message':
          if (event.content && event.role === 'assistant') {
            emitJsonStringMarker('[CONTENT_DELTA]', event.content);
          }
          break;
        case 'result':
          if (event.threadId) {
            resolvedThreadId = event.threadId;
            console.log(`[SESSION_ID] ${event.threadId}`);
          }
          if (event.usage) {
            console.log(`[USAGE] ${JSON.stringify(event.usage)}`);
          }
          break;
        case 'error':
          hadError = true;
          emitSendError(event.message);
          break;
        case 'usage':
          if (event.usage) {
            console.log(`[USAGE] ${JSON.stringify(event.usage)}`);
          }
          break;
        default:
          // Other lines ignored
          break;
      }
    });

    child.stderr.on('data', (chunk) => {
      const text = chunk.toString();
      stderrTail = (stderrTail + text).slice(-4000);
      process.stderr.write(text);
    });

    child.on('error', (error) => {
      hadError = true;
      const hint = error?.code === 'ENOENT'
        ? 'Codex CLI not found. Install Codex CLI and ensure `codex` is on PATH.'
        : (error?.message || String(error));
      emitSendError(hint);
    });

    child.on('close', (code, signal) => {
      process.off('SIGTERM', onParentSignal);
      process.off('SIGINT', onParentSignal);
      process.off('SIGHUP', onParentSignal);

      if (!hadError && code !== 0 && signal !== 'SIGTERM' && signal !== 'SIGINT') {
        const tail = stderrTail.trim().slice(-800);
        emitSendError(
          `Codex CLI exited with code ${code}${signal ? ` (signal ${signal})` : ''}`
          + (tail ? `\n${tail}` : '')
        );
      }

      emitStreamEndOnce();
      resolve();
    });
  });
}

/**
 * Resolve Codex CLI executable path.
 * @returns {string | null}
 */
function resolveCodexCliPath() {
  // Check for custom path in environment
  const customPath = process.env.CODEX_CODE_PATH;
  if (customPath && customPath.trim()) {
    return customPath.trim();
  }

  // Try to find codex in PATH
  return 'codex';
}

/**
 * Send an error marker to stdout.
 * @param {string} message
 */
function emitSendError(message) {
  console.log(`[SEND_ERROR] ${JSON.stringify({ error: String(message || 'Unknown Codex error') })}`);
}

/**
 * Get sessions for project (stub implementation).
 * @param {string} projectPath
 * @returns {Promise<string>}
 */
export async function getSessionsForProjectAsJson(projectPath) {
  // CLI mode doesn't support session listing via SDK
  // Return empty array as JSON
  return JSON.stringify([]);
}
