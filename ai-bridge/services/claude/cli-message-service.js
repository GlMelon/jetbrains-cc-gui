// @ts-check
/**
 * Claude CLI Message Service - Replaces SDK-based message sending
 *
 * Spawns the local Claude CLI in headless mode and maps streaming-json
 * NDJSON events onto the shared bridge marker protocol:
 *
 *   [MESSAGE_START]
 *   [STREAM_START]
 *   [CONTENT_DELTA] "<json-string>"
 *   [THINKING_DELTA] "<json-string>"
 *   [SESSION_ID] <uuid>
 *   [USAGE] { ... }
 *   [STREAM_END]
 *   [MESSAGE_END]
 *   [SEND_ERROR] { "error": "..." }
 *
 * CLI usage:
 *   claude -p "<prompt>" --output-format stream-json --always-approve
 *        [-m <model>] [--reasoning-effort low|medium|high]
 *        (-s <new-uuid> | --resume <existing-uuid>)
 *        [--add-dir <path>]
 *        [--mcp-config <path>]
 */

import { spawn } from 'child_process';
import { randomUUID } from 'crypto';
import { createInterface } from 'readline';
import { resolveClaudeCliPath } from '../../utils/claude-cli-path.js';
import { buildCliEnv } from '../../config/api-config.js';
import {
  emitJsonStringMarker,
  emitSessionId,
  isNonEmptySessionId,
} from '../../utils/marker-protocol.js';

function logDebug(...args) {
  console.error('[DEBUG][Claude CLI]', ...args);
}

function logWarn(...args) {
  console.error('[WARN][Claude CLI]', ...args);
}

/**
 * Parse a single stream-json line from Claude CLI.
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
    case 'assistant':
      return { kind: 'assistant', content: value.content };
    case 'result':
      return { kind: 'result', sessionId: value.session_id, success: value.success, error: value.error };
    case 'error':
      return { kind: 'error', message: value.message };
    case 'usage':
      return { kind: 'usage', usage: value.usage };
    default:
      return { kind: 'other', raw: value };
  }
}

/**
 * Build Claude CLI arguments.
 * @param {object} options
 * @param {string} options.message
 * @param {string} [options.sessionId]
 * @param {string} [options.model]
 * @param {string} [options.reasoningEffort]
 * @param {boolean} [options.streaming]
 * @returns {string[]}
 */
function buildClaudeArgs({ message, sessionId, model, reasoningEffort, streaming }) {
  const args = [
    '--output-format', 'stream-json',
    '--always-approve',
    '-p', message || '',
  ];

  if (model) {
    args.push('-m', model);
  }

  if (reasoningEffort) {
    args.push('--reasoning-effort', reasoningEffort);
  }

  if (streaming) {
    args.push('--stream');
  }

  if (sessionId && isNonEmptySessionId(sessionId)) {
    args.push('--resume', sessionId);
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
    logWarn('Failed to kill claude child:', error?.message || error);
  }
}

/**
 * Send a message via Claude CLI and stream markers to stdout.
 *
 * @param {string} message
 * @param {string} sessionId  Existing UUID to resume, or empty for a new session
 * @param {string} cwd
 * @param {string} permissionMode
 * @param {string} model
 * @param {string} actualModel
 * @param {any} [openedFiles]
 * @param {string} [agentPrompt]
 * @param {boolean} [streaming]
 * @param {boolean} [disableThinking]
 * @param {string} [reasoningEffort]
 */
export async function sendMessage(
  message,
  sessionId = '',
  cwd = '',
  permissionMode = '',
  model = '',
  actualModel = '',
  openedFiles = null,
  agentPrompt = null,
  streaming = false,
  disableThinking = false,
  reasoningEffort = null
) {
  let streamStarted = false;
  let streamEnded = false;
  let hadError = false;
  let resolvedSessionId = sessionId || null;

  const emitStreamEndOnce = () => {
    if (!streamStarted || streamEnded) return;
    streamEnded = true;
    console.log('[STREAM_END]');
    console.log('[MESSAGE_END]');
  };

  console.log('[MESSAGE_START]');
  console.log('[STREAM_START]');
  streamStarted = true;

  const bin = resolveClaudeCliPath();
  if (!bin) {
    hadError = true;
    emitSendError('Claude CLI not found. Please install Claude CLI and ensure it is on PATH.');
    emitStreamEndOnce();
    return;
  }

  const args = buildClaudeArgs({
    message,
    sessionId,
    model: actualModel || model,
    reasoningEffort,
    streaming,
  });

  // If we pre-assigned a new session id via -s, surface it immediately.
  const sessionFlagIndex = args.indexOf('-s');
  if (sessionFlagIndex >= 0 && args[sessionFlagIndex + 1]) {
    resolvedSessionId = args[sessionFlagIndex + 1];
    console.log(`[SESSION_ID] ${resolvedSessionId}`);
  } else if (resolvedSessionId) {
    console.log(`[SESSION_ID] ${resolvedSessionId}`);
  }

  logDebug('spawn', bin, args.slice(0, 5).join(' '), `promptLen=${String(message || '').length}`);

  const env = {
    ...process.env,
    ...buildCliEnv(),
    CLAUDE_NO_COLOR: '1',
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
      emitSendError(`Failed to spawn Claude CLI (${bin}): ${error?.message || error}`);
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
        case 'assistant':
          if (event.content) {
            emitJsonStringMarker('[CONTENT_DELTA]', event.content);
          }
          break;
        case 'result':
          if (event.sessionId) {
            resolvedSessionId = event.sessionId;
            console.log(`[SESSION_ID] ${event.sessionId}`);
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
        ? 'Claude CLI not found. Install Claude CLI and ensure `claude` is on PATH (or set CLAUDE_CODE_PATH).'
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
          `Claude CLI exited with code ${code}${signal ? ` (signal ${signal})` : ''}`
          + (tail ? `\n${tail}` : '')
        );
      }

      emitStreamEndOnce();
      resolve();
    });
  });
}

/**
 * Send an error marker to stdout.
 * @param {string} message
 */
function emitSendError(message) {
  console.log(`[SEND_ERROR] ${JSON.stringify({ error: String(message || 'Unknown Claude error') })}`);
}

/**
 * Send a message with attachments via Claude CLI.
 * For CLI mode, attachments are handled by passing file paths in the prompt.
 *
 * @param {string} message
 * @param {string} sessionId
 * @param {string} cwd
 * @param {string} permissionMode
 * @param {string} model
 * @param {any} stdinData
 */
export async function sendMessageWithAttachments(
  message,
  sessionId = '',
  cwd = '',
  permissionMode = '',
  model = '',
  stdinData = null
) {
  // For CLI mode, we pass the message with attachment references
  // The CLI will handle reading files via the Read tool
  return sendMessage(
    message,
    sessionId,
    cwd,
    permissionMode,
    model,
    stdinData?.actualModel || '',
    stdinData?.openedFiles || null,
    stdinData?.agentPrompt || null,
    stdinData?.streaming || false,
    false,
    stdinData?.reasoningEffort || null
  );
}
