// @ts-check
/**
 * Session title generation service.
 * Generates AI titles for sessions using Claude CLI spawning.
 * Follows CLI's generate_session_title flow but runs independently in ai-bridge.
 */

import { appendFile, mkdir, access, open as fsOpen, stat as fsStat, readFile } from 'fs/promises';
import { join, basename } from 'path';
import { spawn } from 'child_process';
import { setupApiKey, loadClaudeSettings, getCliUserAgent } from '../config/api-config.js';
import { resolveModelFromSettings } from '../utils/model-utils.js';
import { getClaudeDir, getCodemossDir } from '../utils/path-utils.js';
import { getClaudeCliPathOverride } from '../utils/claude-cli-path.js';
import { pathToFileURL } from 'node:url';

const DEFAULT_HAIKU_MODEL = 'claude-haiku-4-5-20251001';
const MAX_CONVERSATION_TEXT = 1000;
const MAX_SANITIZED_LENGTH = 200;
// Aligns with Java HistoryDeleteService.SESSION_ID_PATTERN — alphanumeric,
// dot, dash, underscore. Defeats path-traversal payloads in upstream payloads.
const SESSION_ID_PATTERN = /^[A-Za-z0-9._-]+$/;
// Safety net for Haiku calls — avoids hung requests holding daemon resources.
const HAIKU_API_TIMEOUT_MS = 15000;

/**
 * @param {string | undefined} sessionId
 * @returns {boolean}
 */
function isValidSessionId(sessionId) {
  return typeof sessionId === 'string' && SESSION_ID_PATTERN.test(sessionId);
}

const SESSION_TITLE_PROMPT = `Generate a concise title (3-7 words) for this coding session. The title must be in the SAME LANGUAGE as the user's message.

Return JSON: {"title": "..."}

English examples:
{"title": "Fix login button on mobile"}
{"title": "Refactor API error handling"}

Chinese examples:
{"title": "修复登录按钮移动端问题"}
{"title": "重构API错误处理逻辑"}

Bad: {"title": "Code changes"} (too vague)
Bad: {"title": "修复登录按钮在移动设备上不响应的问题"} (too long)`;

// --- Logging ---
// Title generation runs fire-and-forget after the daemon request completes,
// so activeRequestId is null. Output structured JSON via process.stdout.write
// which the daemon passes through for lines starting with '{'.
// DaemonBridge.java handles "title_log" events with appropriate log levels.

/**
 * @param {string} level
 * @param {string} message
 * @returns {void}
 */
function logTitleEvent(level, message) {
  const line = JSON.stringify({
    type: 'daemon',
    event: 'title_log',
    level,
    message
  }) + '\n';
  process.stdout.write(line);
}

/**
 * Emit a title_generated daemon event so the Java layer can forward the
 * AI title to the frontend for immediate display in the chat header.
 */
/**
 * @param {string} sessionId
 * @param {string} title
 * @returns {void}
 */
function emitTitleGenerated(sessionId, title) {
  const line = JSON.stringify({
    type: 'daemon',
    event: 'title_generated',
    sessionId,
    title
  }) + '\n';
  process.stdout.write(line);
}

/**
 * Check whether the JSONL session file already contains an ai-title entry.
 * Reads the tail of the file to avoid loading the entire session history.
 * @param {string} sessionFile - Path to the JSONL session file
 * @returns {Promise<boolean>} True if an ai-title entry already exists
 */
async function hasExistingAiTitle(sessionFile) {
  try {
    await access(sessionFile);
    const stat = await fsStat(sessionFile);
    if (stat.size === 0) {
      return false;
    }
    const tailSize = Math.min(stat.size, 4096);
    const fd = await fsOpen(sessionFile, 'r');
    try {
      const buf = Buffer.alloc(tailSize);
      await fd.read(buf, 0, tailSize, stat.size - tailSize);
      const text = buf.toString('utf8');
      // Drop the first segment because it may be a partial line if the read
      // window started mid-line (only safe to parse complete JSONL records).
      const lines = text.split('\n');
      if (stat.size > tailSize) {
        lines.shift();
      }
      for (const line of lines) {
        if (!line) continue;
        try {
          if (JSON.parse(line).type === 'ai-title') {
            return true;
          }
        } catch {
          // Skip non-JSON / partial lines.
        }
      }
      return false;
    } finally {
      await fd.close();
    }
  } catch {
    return false;
  }
}

/**
 * Read the AI title generation toggle from ~/.codemoss/config.json.
 * Defaults to true (enabled) when the config is missing, malformed, or the
 * field is not set, matching the Java CodemossSettingsService default.
 * @returns {Promise<boolean>}
 */
async function isTitleGenerationEnabled() {
  try {
    const configPath = join(getCodemossDir(), 'config.json');
    const text = await readFile(configPath, 'utf8');
    const config = JSON.parse(text);
    if (config && typeof config === 'object' && 'aiTitleGenerationEnabled' in config) {
      return config.aiTitleGenerationEnabled !== false;
    }
    return true;
  } catch {
    return true;
  }
}

// --- Path utilities (matching CLI's sanitizePath logic) ---

/**
 * @param {string} str
 * @returns {number}
 */
function djb2Hash(str) {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) - hash + str.charCodeAt(i)) | 0;
  }
  return hash;
}

/**
 * @param {string} name
 * @returns {string}
 */
function sanitizePath(name) {
  const sanitized = name.replace(/[^a-zA-Z0-9]/g, '-');
  if (sanitized.length <= MAX_SANITIZED_LENGTH) {
    return sanitized;
  }
  const hash = Math.abs(djb2Hash(name)).toString(36);
  return `${sanitized.slice(0, MAX_SANITIZED_LENGTH)}-${hash}`;
}

/**
 * @param {string} sessionId
 * @param {string | null} [cwd]
 * @returns {string}
 */
function getSessionFilePath(sessionId, cwd) {
  const projectsDir = join(getClaudeDir(), 'projects');
  const sanitizedCwd = sanitizePath(cwd || process.cwd());
  // Use basename as a defensive second layer in case validation is bypassed
  // by future refactors — basename strips any path separators that slip through.
  return join(projectsDir, sanitizedCwd, `${basename(sessionId)}.jsonl`);
}

// --- API ---

/**
 * Resolve the Haiku model ID from settings.json, matching CLI behavior.
 * Uses resolveModelFromSettings which reads settings.env directly,
 * checking ANTHROPIC_DEFAULT_HAIKU_MODEL before falling back to the default.
 * @returns {string} Model ID
 */
function resolveHaikuModel() {
  const settings = loadClaudeSettings();
  return resolveModelFromSettings(DEFAULT_HAIKU_MODEL, settings?.env) || DEFAULT_HAIKU_MODEL;
}

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
 * Call Claude CLI to generate a title.
 * @param {string} userMessage - The user's first message text
 * @returns {Promise<string|null>} Generated title or null
 */
async function callHaikuApi(userMessage) {
  const config = /** @type {{ authType: string; apiKey?: string; baseUrl?: string }} */ (setupApiKey());

  // CLI login uses SDK-native OAuth which the direct Anthropic SDK doesn't support.
  if (config.authType === 'cli_login') {
    logTitleEvent('info', 'Skipping title generation: CLI login mode not supported for direct API calls');
    return null;
  }

  if (!config.apiKey && config.authType !== 'api_key_helper') {
    logTitleEvent('info', 'Skipping title generation: no API key available (authType: ' + config.authType + ')');
    return null;
  }

  const cliPath = resolveClaudeCliPath();
  const model = resolveHaikuModel();
  logTitleEvent('info', 'Calling Claude CLI for title generation, model: ' + model);

  // Build the prompt for title generation
  const prompt = `Generate a concise title (3-7 words) for this coding session. The title must be in the SAME LANGUAGE as the user's message.

User message:
${userMessage.substring(0, 2000)}

Return ONLY the title text, no JSON formatting, no quotes.`;

  return new Promise((resolve) => {
    const timeoutMs = HAIKU_API_TIMEOUT_MS;
    let timeoutId = null;
    let resolved = false;

    const child = spawn(cliPath, [
      '-p', prompt,
      '--output-format', 'text',
      '--model', model,
      '--max-turns', '1',
      '--dangerously-skip-permissions',
    ], {
      stdio: ['pipe', 'pipe', 'pipe'],
      env: {
        ...process.env,
        NO_COLOR: '1',
      },
    });

    let stdout = '';
    let stderr = '';

    child.stdout.on('data', (chunk) => {
      stdout += chunk.toString();
    });

    child.stderr.on('data', (chunk) => {
      stderr += chunk.toString();
    });

    child.on('error', (error) => {
      if (!resolved) {
        resolved = true;
        if (timeoutId) clearTimeout(timeoutId);
        logTitleEvent('error', 'Claude CLI spawn error: ' + (error?.message || String(error)));
        resolve(null);
      }
    });

    child.on('close', (code) => {
      if (!resolved) {
        resolved = true;
        if (timeoutId) clearTimeout(timeoutId);

        if (code !== 0) {
          logTitleEvent('warn', 'Claude CLI exited with code ' + code + ', stderr: ' + stderr.substring(0, 200));
          resolve(null);
          return;
        }

        const text = stdout.trim();
        if (!text) {
          logTitleEvent('warn', 'Claude CLI returned empty response');
          resolve(null);
          return;
        }

        // Try to parse as JSON first
        try {
          const parsed = JSON.parse(text);
          if (parsed.title && typeof parsed.title === 'string') {
            resolve(parsed.title.trim() || null);
            return;
          }
        } catch {
          // Not JSON, use as plain text
        }

        // Try to extract JSON from text
        const jsonMatch = text.match(/\{[^}]*"title"\s*:\s*"[^"]*"[^}]*\}/);
        if (jsonMatch) {
          try {
            const parsed = JSON.parse(jsonMatch[0]);
            if (parsed.title && typeof parsed.title === 'string') {
              resolve(parsed.title.trim() || null);
              return;
            }
          } catch {
            // fall through
          }
        }

        // Use the text directly as title (strip quotes if present)
        let title = text;
        if (title.startsWith('"') && title.endsWith('"')) {
          title = title.slice(1, -1);
        }
        if (title.startsWith("'") && title.endsWith("'")) {
          title = title.slice(1, -1);
        }

        resolve(title.trim() || null);
      }
    });

    timeoutId = setTimeout(() => {
      if (!resolved) {
        resolved = true;
        child.kill('SIGTERM');
        logTitleEvent('warn', 'Claude CLI call aborted after ' + timeoutMs + 'ms timeout');
        resolve(null);
      }
    }, timeoutMs);
  });
}

/**
 * Save AI title to the JSONL session file.
 * @param {string} sessionId
 * @param {string} title
 * @param {string|null} cwd
 */
async function saveAiTitle(sessionId, title, cwd) {
  try {
    const sessionFile = getSessionFilePath(sessionId, cwd);
    const dir = join(sessionFile, '..');

    await mkdir(dir, { recursive: true });

    const entry = {
      type: 'ai-title',
      aiTitle: title,
      sessionId
    };

    await appendFile(sessionFile, JSON.stringify(entry) + '\n', 'utf8');
    logTitleEvent('info', 'Saved AI title: "' + title + '" for session ' + sessionId);

    // Notify the Java layer so it can forward to the frontend for display
    emitTitleGenerated(sessionId, title);
    return true;
  } catch (e) {
    logTitleEvent('error', 'Failed to save AI title: ' + (e instanceof Error ? e.message : String(e)));
    return false;
  }
}

/**
 * Generate and save an AI title for the session.
 * Called when a session turn ends successfully.
 * Fire-and-forget: errors are logged to IDEA via structured daemon events.
 *
 * @param {string} userMessage - The user's first message text (already extracted)
 * @param {string} sessionId - Session ID
 * @param {string|null} cwd - Working directory
 */
export async function generateSessionTitle(userMessage, sessionId, cwd) {
  if (!userMessage || !userMessage.trim() || !sessionId) {
    logTitleEvent('info', 'Skipping title generation: missing userMessage or sessionId');
    // Treat invalid input as "do not retry" — return true so callers don't
    // un-flag titleGenerationAttempted and re-trigger on the next turn.
    return true;
  }

  if (!isValidSessionId(sessionId)) {
    logTitleEvent('warn', 'Skipping title generation: invalid sessionId rejected');
    return true;
  }

  if (!(await isTitleGenerationEnabled())) {
    logTitleEvent('info', 'Skipping title generation: disabled in user settings');
    return true;
  }

  // Defensive: skip if the session already has an AI title (prevents overwrite).
  // The caller guards (!resumeSessionId / !requestedSessionId) normally prevent
  // duplicate calls, but this check protects against edge cases where the guard
  // fails or the session file already has a title from another source.
  try {
    const sessionFile = getSessionFilePath(sessionId, cwd);
    if (await hasExistingAiTitle(sessionFile)) {
      logTitleEvent('info', 'Skipping title generation: session already has an AI title');
      return true;
    }
  } catch (e) {
    logTitleEvent('warn', 'Failed to check existing AI title, proceeding: ' + (e instanceof Error ? e.message : String(e)));
  }

  try {
    // Iterate by Unicode code point so we never split a surrogate pair
    // (e.g. CJK extension characters or emoji) when truncating.
    let input = userMessage;
    if (userMessage.length > MAX_CONVERSATION_TEXT) {
      const codePoints = Array.from(userMessage);
      if (codePoints.length > MAX_CONVERSATION_TEXT) {
        input = codePoints.slice(-MAX_CONVERSATION_TEXT).join('');
      }
    }

    const title = await callHaikuApi(input);
    if (title) {
      // saveAiTitle returning false signals an FS error; don't retry — disk
      // problems are usually persistent and a retry storm helps no one.
      await saveAiTitle(sessionId, title, cwd);
      return true;
    }
    // callHaikuApi returns null for permanent skips (cli_login, missing key,
    // unparseable response). Treat as "already attempted" so the caller does
    // not reset its guard and re-call on the next turn.
    logTitleEvent('info', 'Title generation returned no result for session ' + sessionId);
    return true;
  } catch (e) {
    // Thrown errors come from the SDK / network layer and are typically
    // transient — return false so callers may reset their guard and retry.
    logTitleEvent('error', 'Title generation failed: ' + (e instanceof Error ? e.message : String(e)));
    return false;
  }
}

/**
 * 读取整个 stdin 流为 UTF-8 字符串(与 prompt-enhancer.js 同一模式)。
 * @returns {Promise<string>}
 */
async function readStdin() {
  return new Promise((resolve, reject) => {
    let data = '';
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', (/** @type {string} */ chunk) => {
      data += chunk;
    });
    process.stdin.on('end', () => {
      resolve(data);
    });
    process.stdin.on('error', reject);
  });
}

/**
 * 独立可执行入口:供 CLI 模式由 Java 侧以一次性子进程调用(双重角色,仿 prompt-enhancer.js)。
 *
 * daemon 路径(message-sender.js / persistent-query-service.js)通过 import 直接调
 * generateSessionTitle,不会触发此 main —— 由 import.meta.url 守卫隔离。
 *
 * stdin: { userMessage, sessionId, cwd }
 * stdout: generateSessionTitle 内部经 emitTitleGenerated 写出 title_generated daemon 事件行,
 *         Java 侧 PromptEnhancerProcessRunner 的 lineHandler 解析该行并下发 SESSION_TITLE。
 *
 * @returns {Promise<void>}
 */
async function main() {
  try {
    const input = await readStdin();
    const data = JSON.parse(input);
    const { userMessage, sessionId, cwd } = data;
    await generateSessionTitle(userMessage, sessionId, cwd);
    process.exit(0);
  } catch (error) {
    logTitleEvent('error', 'CLI title generation main failed: '
      + (error instanceof Error ? error.message : String(error)));
    process.exit(1);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
