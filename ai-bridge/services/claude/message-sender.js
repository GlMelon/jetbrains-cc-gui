// @ts-check
/**
 * Message sending functions — CLI-based implementation.
 *
 * Spawns the local Claude CLI (`claude`) in headless streaming-json mode and
 * maps its NDJSON output onto the shared bridge marker protocol consumed by
 * `MarkerCliBridge` (Java) and `CommitMessageAiService`.
 *
 * Marker contract consumed by callers:
 *   [CONTENT]      <text>                  — final accumulated assistant text
 *   [SESSION_ID]   <uuid>                  — session id for resume
 *   [USAGE]        { ... }                 — token usage
 *   [SEND_ERROR]   { "error": "..." }      — unrecoverable error
 *   [CONTENT_DELTA] <json-string>          — streaming delta (when streaming)
 *   [STREAM_START] / [STREAM_END]          — streaming bookends
 *   [MESSAGE_START] / [MESSAGE_END]        — outer bookends
 */

import { spawn, spawnSync } from 'child_process';
import { randomUUID } from 'crypto';
import { createInterface } from 'readline';
import {
  loadClaudeSettings,
  setupApiKey,
  buildCliEnv,
  buildWebviewControlledSettingsOverride,
} from '../../config/api-config.js';
import { selectWorkingDirectory } from '../../utils/path-utils.js';
import { resolveModelFromSettings, setModelEnvironmentVariables } from '../../utils/model-utils.js';
import { buildContentBlocks, loadAttachments } from './attachment-service.js';
import { buildIDEContextPrompt } from '../system-prompts.js';
import { buildQuickFixPrompt } from '../quickfix-prompts.js';
import { extractResultError } from './message-utils.js';
import { createPreToolUseHook } from './permission-mode.js';
import { loadMcpServersConfigAsRecord } from './mcp-status/config-loader.js';
import { generateSessionTitle } from '../session-title-service.js';
import { getClaudeCliPathOverride } from '../../utils/claude-cli-path.js';
import { killChildTree } from '../../utils/kill-tree.js';

// ========== Constants ==========

const AUTO_RETRY_CONFIG = {
  maxRetries: 3,
  retryableStatusCodes: new Set([429, 500, 502, 503, 529]),
  baseDelayMs: 2000,
  maxDelayMs: 30000,
};

const SUPPORTED_EFFORT_LEVELS = new Set(['low', 'medium', 'high', 'xhigh', 'max']);

// ========== CLI Path Resolution ==========

/**
 * Resolve the Claude CLI executable path.
 * @returns {string}
 */
function resolveClaudeCliPath() {
  const override = getClaudeCliPathOverride();
  if (override) return override;

  const envPath = process.env.CLAUDE_CODE_PATH;
  if (envPath && envPath.trim()) return envPath.trim();

  return 'claude';
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

// ========== Argument Builder ==========

/**
 * Build Claude CLI arguments.
 * @param {object} params
 * @param {string} params.message
 * @param {string} [params.sessionId]
 * @param {string} [params.model]
 * @param {string} [params.reasoningEffort]
 * @param {string} [params.permissionMode]
 * @param {number} [params.maxTurns]
 * @returns {string[]}
 */
function buildCliArgs({ message, sessionId, model, reasoningEffort, permissionMode, maxTurns }) {
  const args = [
    '-p', message || '',
    '--output-format', 'stream-json',
  ];

  if (sessionId) {
    args.push('--resume', sessionId);
  }

  if (model) {
    args.push('--model', model);
  }

  if (reasoningEffort) {
    args.push('--reasoning-effort', reasoningEffort);
  }

  if (permissionMode === 'bypassPermissions') {
    args.push('--dangerously-skip-permissions');
  }

  if (maxTurns && maxTurns > 0) {
    args.push('--max-turns', String(maxTurns));
  }

  return args;
}

// ========== CLI Stream Parser ==========

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
      return { kind: 'assistant', message: value.message, content: value.content };
    case 'user':
      return { kind: 'user', message: value.message, content: value.content };
    case 'system':
      return { kind: 'system', session_id: value.session_id, sessionUrl: value.session_url };
    case 'result':
      return { kind: 'result', session_id: value.session_id, is_error: value.is_error, result: value.result, cost_usd: value.cost_usd, duration_api_ms: value.duration_api_ms, duration_ms: value.duration_ms };
    case 'stream_event':
      return { kind: 'stream_event', event: value.event };
    case 'error':
      return { kind: 'error', message: value.message || value.error };
    default:
      return { kind: 'other', raw: value };
  }
}

// ========== Process Lifecycle ==========
//
// Process-tree kill is shared: see utils/kill-tree.js (Windows taskkill /F /T
// kills the cmd.exe wrapper's whole tree, Unix process-group SIGTERM).

// ========== Retry Logic ==========

/**
 * Determine if an error is retryable.
 * @param {any} error
 * @returns {boolean}
 */
function isRetryableError(error) {
  if (!error) return false;
  const msg = String(error.message || error);
  if (/\b429\b|rate.limit|too.many.requests/i.test(msg)) return true;
  if (/\b5[0-9]{2}\b|ETIMEDOUT|ECONNRESET|socket hang up|fetch failed/i.test(msg)) return true;
  if (/overloaded|capacity|temporarily.unavailable|API.*error/i.test(msg)) return true;
  return false;
}

/**
 * @param {number} attempt
 * @returns {number}
 */
function getRetryDelayMs(attempt) {
  const base = AUTO_RETRY_CONFIG.baseDelayMs * Math.pow(2, attempt);
  return Math.min(base + Math.random() * 1000, AUTO_RETRY_CONFIG.maxDelayMs);
}

// ========== Stream State ==========

/**
 * @typedef {{
 *   streamStarted: boolean,
 *   streamEnded: boolean,
 *   currentSessionId: string|null,
 *   lastAssistantContent: string,
 *   accumulatedUsage: any,
 *   streamingEnabled: boolean
 * }} StreamState
 */

// ========== Core: Spawn CLI and Stream ==========

/**
 * Spawn Claude CLI and process its streaming-json output, emitting bridge markers.
 *
 * @param {object} params
 * @param {string} params.message        User prompt
 * @param {string} [params.sessionId]    Resume session id (optional)
 * @param {string} [params.cwd]          Working directory
 * @param {string} [params.permissionMode] 'default' | 'bypassPermissions'
 * @param {string} [params.model]        Model name/id
 * @param {string} [params.reasoningEffort] 'low' | 'medium' | 'high'
 * @param {boolean} [params.streaming]   Enable streaming deltas
 * @param {any} [params.mcpServers]      MCP servers config
 * @param {string} [params.systemPromptAppend] Extra system prompt
 * @returns {Promise<void>}
 */
async function spawnCliAndStream({
  message,
  sessionId = '',
  cwd = '',
  permissionMode = '',
  model = '',
  reasoningEffort = '',
  streaming = false,
  mcpServers = null,
  systemPromptAppend = '',
}) {
  let streamStarted = false;
  let streamEnded = false;
  let resolvedSessionId = sessionId || null;
  let lastAssistantContent = '';
  let accumulatedUsage = null;

  const emitStreamEndOnce = () => {
    if (!streamStarted || streamEnded) return;
    streamEnded = true;
    process.stdout.write('[STREAM_END]\n');
    console.log('[MESSAGE_END]');
  };

  console.log('[MESSAGE_START]');
  console.log('[STREAM_START]');
  streamStarted = true;

  // Resolve CLI path
  const bin = resolveClaudeCliPath();
  if (!probeCliBin(bin)) {
    emitSendError('Claude CLI not found. Install Claude Code and ensure `claude` is on PATH, or set CLAUDE_CODE_PATH.');
    emitStreamEndOnce();
    return;
  }

  // Build args
  const args = buildCliArgs({ message, sessionId, model, reasoningEffort, permissionMode, maxTurns: 1000 });

  // If we pre-assigned a new session id, surface it immediately
  if (!sessionId || !sessionId.trim()) {
    const newId = randomUUID();
    resolvedSessionId = newId;
    args.push('-s', newId);
    console.log(`[SESSION_ID] ${newId}`);
  } else {
    console.log(`[SESSION_ID] ${sessionId}`);
  }

  // Build environment
  const cliEnv = buildCliEnv();
  const env = { ...process.env, ...cliEnv, CLAUDE_NO_COLOR: '1', CLAUDE_USE_STDIN: '1' };

  // Resolve working directory
  const workCwd = cwd && cwd !== 'undefined' && cwd !== 'null' ? cwd : process.cwd();

  // Append system prompt
  const fullMessage = systemPromptAppend
    ? `${systemPromptAppend}\n\n${message}`
    : message;

  // Rebuild args with full message
  const finalArgs = buildCliArgs({ message: fullMessage, sessionId, model, reasoningEffort, permissionMode, maxTurns: 1000 });
  if (!sessionId || !sessionId.trim()) {
    finalArgs.push('-s', resolvedSessionId);
  }

  console.error(`[DEBUG][Claude CLI] spawn ${bin} ${finalArgs.slice(0, 4).join(' ')}... promptLen=${fullMessage.length}`);

  await new Promise((resolve) => {
    let child;
    try {
      child = spawn(bin, finalArgs, {
        cwd: workCwd,
        env,
        stdio: ['pipe', 'pipe', 'pipe'],
        detached: process.platform !== 'win32',
      });
    } catch (error) {
      const hint = error?.code === 'ENOENT'
        ? 'Claude CLI not found. Install Claude Code and ensure `claude` is on PATH.'
        : (error?.message || String(error));
      emitSendError(`Failed to spawn Claude CLI: ${hint}`);
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
        case 'assistant': {
          const content = event.content || event.message?.content;
          if (typeof content === 'string') {
            lastAssistantContent = content;
            if (streaming) {
              process.stdout.write(`[CONTENT_DELTA] ${JSON.stringify(content)}\n`);
            }
          } else if (Array.isArray(content)) {
            for (const block of content) {
              if (block.type === 'text' && block.text) {
                lastAssistantContent += block.text;
                if (streaming) {
                  process.stdout.write(`[CONTENT_DELTA] ${JSON.stringify(block.text)}\n`);
                }
              }
            }
          }
          // Emit full message for backward compat
          console.log('[MESSAGE]', JSON.stringify(event.message || event.raw || event));
          break;
        }
        case 'system':
          if (event.session_id) {
            resolvedSessionId = event.session_id;
            console.log(`[SESSION_ID] ${event.session_id}`);
          }
          break;
        case 'result':
          if (event.session_id) {
            resolvedSessionId = event.session_id;
            console.log(`[SESSION_ID] ${event.session_id}`);
          }
          if (event.is_error) {
            // The result event carries the real failure text in its errors array
            // (subtype != "success"); extractResultError reads it so the actual
            // error surfaces instead of a generic fallback.
            emitSendError(extractResultError(event));
          }
          // Accumulate usage from result
          if (event.cost_usd !== undefined || event.duration_api_ms !== undefined) {
            accumulatedUsage = {
              ...(accumulatedUsage || {}),
              ...(event.cost_usd !== undefined && { cost_usd: event.cost_usd }),
              ...(event.duration_api_ms !== undefined && { duration_api_ms: event.duration_api_ms }),
            };
          }
          break;
        case 'stream_event': {
          const evt = event.event;
          if (evt?.type === 'message_start' && evt.message?.usage) {
            accumulatedUsage = mergeUsage(accumulatedUsage, evt.message.usage);
          }
          if (evt?.type === 'message_delta' && evt.usage) {
            accumulatedUsage = mergeUsage(accumulatedUsage, evt.usage);
          }
          if (evt?.type === 'content_block_delta' && evt.delta?.type === 'text_delta' && evt.delta.text) {
            lastAssistantContent += evt.delta.text;
            if (streaming) {
              process.stdout.write(`[CONTENT_DELTA] ${JSON.stringify(evt.delta.text)}\n`);
            }
          }
          break;
        }
        case 'error':
          emitSendError(event.message);
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
        ? 'Claude CLI not found. Install Claude Code and ensure `claude` is on PATH.'
        : (error?.message || String(error));
      emitSendError(hint);
    });

    child.on('close', (code, signal) => {
      process.off('SIGTERM', onParentSignal);
      process.off('SIGINT', onParentSignal);
      process.off('SIGHUP', onParentSignal);

      // Emit final content
      if (lastAssistantContent) {
        console.log('[CONTENT]', truncateErrorContent(lastAssistantContent));
      }

      // Emit usage
      if (accumulatedUsage) {
        console.log('[USAGE]', JSON.stringify(accumulatedUsage));
      }

      if (code !== 0 && signal !== 'SIGTERM' && signal !== 'SIGINT') {
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

// ========== Helper Functions ==========

/**
 * @param {any} usage
 * @param {any} incoming
 * @returns {any}
 */
function mergeUsage(usage, incoming) {
  if (!incoming) return usage;
  if (!usage) return incoming;
  return {
    input_tokens: (usage.input_tokens || 0) + (incoming.input_tokens || 0),
    output_tokens: (usage.output_tokens || 0) + (incoming.output_tokens || 0),
    cache_creation_input_tokens: (usage.cache_creation_input_tokens || 0) + (incoming.cache_creation_input_tokens || 0),
    cache_read_input_tokens: (usage.cache_read_input_tokens || 0) + (incoming.cache_read_input_tokens || 0),
  };
}

/**
 * Truncate very long error content strings for log readability.
 * @param {string} text
 * @returns {string}
 */
function truncateErrorContent(text) {
  if (!text || text.length <= 20000) return text || '';
  return text.slice(0, 20000) + '\n...[truncated]';
}

/**
 * Send an error marker to stdout.
 * @param {string} message
 */
function emitSendError(message) {
  console.log(`[SEND_ERROR] ${JSON.stringify({ error: String(message || 'Unknown Claude error') })}`);
}

// ========== Exported API ==========

/**
 * Send a plain text message to Claude via CLI.
 * @param {string} message - The message text
 * @param {string|null} [resumeSessionId=null] - Session ID to resume (optional)
 * @param {string|null} [cwd=null] - Working directory (optional)
 * @param {string|null} [permissionMode=null] - Permission mode (optional)
 * @param {string|null} [model=null] - Model name (optional)
 * @param {string|null} [actualModel=null] - actualModel from Model Registry (optional)
 * @param {any} [openedFiles=null] - List of opened files (optional)
 * @param {string|null} [agentPrompt=null] - Agent prompt (optional)
 * @param {boolean|null} [streaming=null] - Whether to enable streaming (optional, defaults to config value)
 * @param {boolean} [disableThinking=false] - Disable extended thinking
 * @param {string|null} [reasoningEffort=null] - Reasoning effort level
 * @returns {Promise<void>}
 */
export async function sendMessage(message, resumeSessionId = null, cwd = null, permissionMode = null, model = null, actualModel = null, openedFiles = null, agentPrompt = null, streaming = null, disableThinking = false, reasoningEffort = null) {
  console.log('[DIAG] ========== sendMessage() START ==========');

  try {
    const { baseUrl } = setupApiKey();
    const workingDirectory = selectWorkingDirectory(/** @type {string} */ (cwd));
    try { process.chdir(workingDirectory); } catch (/** @type {any} */ e) { console.error('[WARNING] chdir failed:', e.message); }

    const settings = loadClaudeSettings();
    const resolvedModel = resolveModelFromSettings(/** @type {string} */ (model), settings?.env, actualModel ?? undefined);
    setModelEnvironmentVariables(resolvedModel, model ?? undefined);

    const effectivePermissionMode = (!permissionMode || permissionMode === '') ? 'default' : permissionMode;
    const normalizedReasoningEffort = normalizeReasoningEffort(reasoningEffort);
    const streamingEnabled = streaming != null ? streaming : (settings?.streamingEnabled ?? false);

    // Build system prompt append
    let systemPromptAppend = '';
    if (openedFiles && openedFiles.isQuickFix) {
      systemPromptAppend = buildQuickFixPrompt(openedFiles, message);
    } else {
      systemPromptAppend = buildIDEContextPrompt(openedFiles, agentPrompt ?? undefined) || '';
    }

    // Load MCP servers config
    let mcpServers = null;
    try {
      mcpServers = await loadMcpServersConfigAsRecord(workingDirectory);
    } catch (_) { /* ignore */ }

    await spawnCliAndStream({
      message,
      sessionId: resumeSessionId || '',
      cwd: workingDirectory,
      permissionMode: effectivePermissionMode,
      model: resolvedModel || model || '',
      reasoningEffort: normalizedReasoningEffort || '',
      streaming: streamingEnabled,
      mcpServers,
      systemPromptAppend,
    });
  } catch (error) {
    emitSendError(error?.message || String(error));
  }
}

/**
 * Send message with attachments via CLI (multimodal).
 * @param {string} message - The message text
 * @param {string|null} [resumeSessionId=null] - Session ID to resume (optional)
 * @param {string|null} [cwd=null] - Working directory (optional)
 * @param {string|null} [permissionMode=null] - Permission mode (optional)
 * @param {string|null} [model=null] - Model name (optional)
 * @param {any} [stdinData=null] - Stdin data containing attachments (optional)
 * @returns {Promise<void>}
 */
export async function sendMessageWithAttachments(message, resumeSessionId = null, cwd = null, permissionMode = null, model = null, stdinData = null) {
  try {
    setupApiKey();
    console.log('[MESSAGE_START]');

    const workingDirectory = selectWorkingDirectory(/** @type {string} */ (cwd));
    try { process.chdir(workingDirectory); } catch (/** @type {any} */ e) { console.error('[WARNING] chdir failed:', e.message); }

    const settings = loadClaudeSettings();
    const resolvedModel = resolveModelFromSettings(/** @type {string} */ (model), settings?.env);
    setModelEnvironmentVariables(resolvedModel, model ?? undefined);

    const effectivePermissionMode = (!permissionMode || permissionMode === '') ? 'default' : permissionMode;
    const normalizedReasoningEffort = normalizeReasoningEffort(null);

    // Process attachments into message content
    let finalMessage = message;
    if (stdinData) {
      try {
        const attachments = await loadAttachments(stdinData);
        if (attachments && attachments.length > 0) {
          const contentBlocks = buildContentBlocks(message, attachments);
          // For CLI, we just include file paths/descriptions in the message
          finalMessage = message;
          for (const att of attachments) {
            if (att.filePath) {
              finalMessage += `\n\n[File: ${att.filePath}]`;
            }
          }
        }
      } catch (attError) {
        console.error('[WARNING] Failed to process attachments:', attError);
      }
    }

    await spawnCliAndStream({
      message: finalMessage,
      sessionId: resumeSessionId || '',
      cwd: workingDirectory,
      permissionMode: effectivePermissionMode,
      model: resolvedModel || model || '',
      reasoningEffort: normalizedReasoningEffort || '',
      streaming: settings?.streamingEnabled ?? false,
      mcpServers: null,
      systemPromptAppend: '',
    });
  } catch (error) {
    emitSendError(error?.message || String(error));
  }
}

// ========== Internal Helpers ==========

/**
 * Normalize reasoning effort level.
 * @param {string|null} effort
 * @returns {string}
 */
function normalizeReasoningEffort(effort) {
  if (!effort || typeof effort !== 'string') return '';
  const normalized = effort.trim().toLowerCase();
  if (!SUPPORTED_EFFORT_LEVELS.has(normalized)) return '';
  // Map xhigh/max to high for CLI compatibility
  if (normalized === 'xhigh' || normalized === 'max') return 'high';
  return normalized;
}
