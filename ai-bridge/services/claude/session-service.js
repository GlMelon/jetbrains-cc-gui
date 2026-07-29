// @ts-check
/**
 * Session management service module.
 * Responsible for session persistence and history message management.
 */

import { existsSync, createReadStream, mkdirSync, readFileSync, appendFileSync, statSync } from 'fs';
import { readFile } from 'fs/promises';
import { dirname } from 'path';
import { randomUUID } from 'crypto';
import { createInterface } from 'readline';
import { getClaudeProjectSessionFilePath } from '../../utils/path-utils.js';

/**
 * JSONL 历史消息(结构宽松,关键字段经 typeof/Array.isArray 守卫后使用)。
 * @typedef {{ type?: string, uuid?: string, message?: any }} JsonlMessage
 */

/**
 * Append a message to the JSONL history file.
 * Adds necessary metadata fields to ensure compatibility with the history reader.
 *
 * @param {string} sessionId Session ID
 * @param {string | null} cwd  Current working directory(用于定位 project 历史目录;可为 null 走 process.cwd())
 * @param {Record<string, unknown>} obj 待持久化的消息对象
 * @returns {void}
 */
export function persistJsonlMessage(sessionId, cwd, obj) {
  try {
    const sessionFile = getClaudeProjectSessionFilePath(sessionId, cwd);
    const projectHistoryDir = dirname(sessionFile);
    mkdirSync(projectHistoryDir, { recursive: true });

    // Add necessary metadata fields to ensure compatibility with ClaudeHistoryReader
    const enrichedObj = {
      ...obj,
      uuid: randomUUID(),
      sessionId: sessionId,
      timestamp: new Date().toISOString()
    };

    appendFileSync(sessionFile, JSON.stringify(enrichedObj) + '\n', 'utf8');
    console.log('[PERSIST] Message saved to:', sessionFile);
  } catch (e) {
    console.error('[PERSIST_ERROR]', e instanceof Error ? e.message : String(e));
  }
}

/**
 * Load session history messages (used to maintain context when resuming a session).
 * Returns an array of messages in the Anthropic Messages API format.
 *
 * @param {string} sessionId Session ID
 * @param {string | null} [cwd]  Current working directory
 * @returns {Array<{ role: string, content: unknown }>}
 */
export function loadSessionHistory(sessionId, cwd) {
  try {
    const sessionFile = getClaudeProjectSessionFilePath(sessionId, cwd);

    if (!existsSync(sessionFile)) {
      return [];
    }

    const content = readFileSync(sessionFile, 'utf8');
    const lines = content.split('\n').filter(line => line.trim());
    /** @type {Array<{ role: string, content: unknown }>} */
    const messages = [];

    for (const line of lines) {
      try {
        const msg = JSON.parse(line);
        if (msg.type === 'user' && msg.message && msg.message.content) {
          messages.push({
            role: 'user',
            content: msg.message.content
          });
        } else if (msg.type === 'assistant' && msg.message && msg.message.content) {
          messages.push({
            role: 'assistant',
            content: msg.message.content
          });
        }
      } catch (e) {
        // Skip lines that fail to parse
      }
    }

    // Exclude the last user message (since we already persisted the current user message before calling this function)
    if (messages.length > 0 && messages[messages.length - 1].role === 'user') {
      messages.pop();
    }

    return messages;
  } catch (e) {
    console.error('[LOAD_HISTORY_ERROR]', e instanceof Error ? e.message : String(e));
    return [];
  }
}

/**
 * Get session history messages.
 * Reads from the ~/.claude/projects/ directory.
 *
 * @param {string} sessionId Session ID
 * @param {string | null} [cwd] Current working directory
 * @returns {Promise<void>}
 */
export async function getSessionMessages(sessionId, cwd = null) {
  try {
    const sessionFile = resolveSessionFile(sessionId, cwd);

    if (!existsSync(sessionFile)) {
      console.log(JSON.stringify({
        success: true,
        messages: []
      }));
      return;
    }

    // Read the JSONL file
    const content = await readFile(sessionFile, 'utf8');
    const messages = content
      .split('\n')
      .filter(line => line.trim())
      .map(line => {
        try {
          return JSON.parse(line);
        } catch {
          return null;
        }
      })
      .filter(msg => msg !== null);

    console.log(JSON.stringify({
      success: true,
      messages
    }));

  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error('[GET_SESSION_ERROR]', message);
    console.log(JSON.stringify({
      success: false,
      error: message
    }));
  }
}

/**
 * @param {string} sessionId Session ID
 * @param {string | null} [cwd] Current working directory
 * @returns {Promise<void>}
 */
export async function getLatestUserMessage(sessionId, cwd = null) {
  try {
    const sessionFile = resolveSessionFile(sessionId, cwd);

    if (!existsSync(sessionFile)) {
      console.log(JSON.stringify({
        success: true,
        message: null
      }));
      return;
    }

    // Read only the tail of the file for performance on large sessions
    const TAIL_BYTES = 32 * 1024;
    const stat = statSync(sessionFile);
    const startByte = Math.max(0, stat.size - TAIL_BYTES);

    let latestUserMessage = null;
    const rl = createInterface({
      input: createReadStream(sessionFile, { encoding: 'utf8', start: startByte }),
      crlfDelay: Infinity
    });

    let firstLine = startByte > 0;
    for await (const line of rl) {
      // Skip potentially partial first line when reading from mid-file
      if (firstLine) { firstLine = false; continue; }
      if (!line.trim()) continue;
      try {
        const message = JSON.parse(line);
        if (isUserTextMessage(message)) {
          latestUserMessage = message;
        }
      } catch {
        // Ignore malformed JSONL entries
      }
    }

    console.log(JSON.stringify({
      success: true,
      message: latestUserMessage
    }));
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error('[GET_LATEST_USER_ERROR]', message);
    console.log(JSON.stringify({
      success: false,
      error: message
    }));
  }
}

/**
 * @param {JsonlMessage} message
 * @returns {boolean}
 */
function isUserTextMessage(message) {
  return Boolean(
    message &&
    message.type === 'user' &&
    typeof message.uuid === 'string' &&
    extractTextContent(message)?.trim()
  );
}

/**
 * @param {JsonlMessage} message
 * @returns {string}
 */
function extractTextContent(message) {
  const content = message?.message?.content;
  if (!content) {
    return '';
  }

  if (typeof content === 'string') {
    return content;
  }

  if (!Array.isArray(content)) {
    return '';
  }

  return content
    .filter((block) => block && block.type === 'text' && typeof block.text === 'string')
    .map((block) => block.text)
    .join('\n');
}

/**
 * @param {string} sessionId Session ID
 * @param {string | null} [cwd] Current working directory
 * @returns {string}
 */
function resolveSessionFile(sessionId, cwd = null) {
  if (!sessionId || /[/\\]/.test(sessionId)) {
    throw new Error('Invalid session ID');
  }
  return getClaudeProjectSessionFilePath(sessionId, cwd);
}
