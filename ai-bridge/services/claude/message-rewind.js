// @ts-check
/**
 * File rewind service for Claude sessions.
 * Restores files to a previous checkpoint via the Claude SDK.
 */

import { existsSync } from 'fs';
import { readFile } from 'fs/promises';
import { join } from 'path';
import { setupApiKey, buildCliEnv, buildWebviewControlledSettingsOverride } from '../../config/api-config.js';
import { getClaudeDir, getRealHomeDir, selectWorkingDirectory } from '../../utils/path-utils.js';
import { ensureClaudeSdk, hasClaudeProjectSessionFile, waitForClaudeProjectSessionFile, isNoConversationFoundError } from './message-utils.js';
import { getActiveQueryResult, getActiveSessionIds } from './message-session-registry.js';
import { getClaudeCliPathOverride } from '../../utils/claude-cli-path.js';

/**
 * 通过 Claude SDK 把会话文件回退到指定用户消息对应的检查点。
 * @param {string} sessionId       会话 ID
 * @param {string} userMessageId   目标用户消息 ID
 * @param {string|null} [cwd=null] 工作目录
 * @returns {Promise<void>}
 */
export async function rewindFiles(sessionId, userMessageId, cwd = null) {
  /** @type {any} */
  let result = null;
  try {
    console.log('[REWIND] ========== REWIND OPERATION START ==========');
    console.log('[REWIND] Session ID:', sessionId);
    console.log('[REWIND] Target message ID:', userMessageId);
    console.log('[REWIND] CWD:', cwd);
    console.log('[REWIND] Active sessions in memory:', getActiveSessionIds());

    // Get the stored query result for this session
    result = getActiveQueryResult(sessionId);
    console.log('[REWIND] Result found in memory:', !!result);

    // If result not in memory, try to resume the session to get a fresh query result
    if (!result) {
      console.log('[REWIND] Session not in memory, attempting to resume...');

      try {
        setupApiKey();

        if (!process.env.HOME) {
          process.env.HOME = getRealHomeDir();
        }

        const workingDirectory = selectWorkingDirectory(/** @type {string} */ (cwd));
        try {
          process.chdir(workingDirectory);
        } catch (chdirError) {
          console.error('[WARNING] Failed to change process.cwd():', chdirError instanceof Error ? chdirError.message : String(chdirError));
        }

        if (!hasClaudeProjectSessionFile(sessionId, workingDirectory)) {
          console.log('[RESUME_WAIT] Waiting for session file to appear before resuming...');
          await waitForClaudeProjectSessionFile(sessionId, workingDirectory, 2500, 100);
        }

        const claudeCliOverride = getClaudeCliPathOverride();
        const options = {
          resume: sessionId,
          cwd: workingDirectory,
          permissionMode: 'default',
          enableFileCheckpointing: true,
          maxTurns: 1,
          env: buildCliEnv(),
          // Rewind is a maxTurns:1 file-checkpointing call — no inference output,
          // so 1M context state is irrelevant; only neutralize effort/thinking.
          settings: buildWebviewControlledSettingsOverride(undefined),
          tools: { type: 'preset', preset: 'claude_code' },
          settingSources: ['user', 'project', 'local'],
          additionalDirectories: Array.from(
            new Set(
              [workingDirectory, process.env.IDEA_PROJECT_PATH, process.env.PROJECT_PATH].filter(Boolean)
            )
          ),
          canUseTool: async () => ({
            behavior: 'deny',
            message: 'Rewind operation'
          }),
          stderr: (/** @type {string|Buffer} */ data) => {
            if (data && data.toString().trim()) {
              console.log(`[SDK-STDERR] ${data.toString().trim()}`);
            }
          },
          ...(claudeCliOverride && { pathToClaudeCodeExecutable: claudeCliOverride })
        };

        console.log('[REWIND] Resuming session with options:', JSON.stringify(options));

        // Dynamically load Claude SDK
        const sdk = await ensureClaudeSdk();
        const query = sdk?.query;
        if (typeof query !== 'function') {
          throw new Error('Claude SDK query function not available. Please reinstall dependencies.');
        }

        try {
          result = query({ prompt: '', options });
        } catch (queryError) {
          if (isNoConversationFoundError(queryError)) {
            await waitForClaudeProjectSessionFile(sessionId, workingDirectory, 2500, 100);
            result = query({ prompt: '', options });
          } else {
            throw queryError;
          }
        }

      } catch (resumeError) {
        const resumeErrMsg = resumeError instanceof Error ? resumeError.message : String(resumeError);
        const errorMsg = `Failed to resume session ${sessionId}: ${resumeErrMsg}`;
        console.error('[REWIND_ERROR]', errorMsg);
        console.log(JSON.stringify({
          success: false,
          error: errorMsg
        }));
        return;
      }
    }

    // Check if rewindFiles method exists on the result object
    if (typeof result.rewindFiles !== 'function') {
      const errorMsg = 'rewindFiles method not available. File checkpointing may not be enabled or SDK version too old.';
      console.error('[REWIND_ERROR]', errorMsg);
      console.log(JSON.stringify({
        success: false,
        error: errorMsg
      }));
      return;
    }

    const timeoutMs = 45000;

    /**
     * @param {string} targetUserMessageId
     * @returns {Promise<string>}
     */
    const attemptRewind = async (targetUserMessageId) => {
      console.log('[REWIND] Calling result.rewindFiles()...', JSON.stringify({ targetUserMessageId }));
      await Promise.race([
        result.rewindFiles(targetUserMessageId),
        new Promise((_, reject) => setTimeout(() => reject(new Error(`Rewind timeout (${timeoutMs}ms)`)), timeoutMs))
      ]);
      return targetUserMessageId;
    };

    let usedMessageId = null;
    try {
      usedMessageId = await attemptRewind(userMessageId);
    } catch (primaryError) {
      const msg = (primaryError instanceof Error ? primaryError.message : String(primaryError));
      if (!msg.includes('No file checkpoint found for message')) {
        throw primaryError;
      }

      console.log('[REWIND] No checkpoint for requested message, attempting to resolve alternative user message id...');

      const candidateIds = await resolveRewindCandidateMessageIds(sessionId, cwd, userMessageId);
      console.log('[REWIND] Candidate message ids:', JSON.stringify(candidateIds));

      let lastError = primaryError;
      for (const candidateId of candidateIds) {
        if (!candidateId || candidateId === userMessageId) continue;
        try {
          usedMessageId = await attemptRewind(candidateId);
          lastError = null;
          break;
        } catch (candidateError) {
          lastError = candidateError;
          const candidateMsg = (candidateError instanceof Error ? candidateError.message : String(candidateError));
          if (!candidateMsg.includes('No file checkpoint found for message')) {
            throw candidateError;
          }
        }
      }

      if (!usedMessageId) {
        throw lastError;
      }
    }

    console.log('[REWIND] Files rewound successfully');

    console.log(JSON.stringify({
      success: true,
      message: 'Files restored successfully',
      sessionId,
      targetMessageId: usedMessageId
    }));

  } catch (error) {
    const errMsg = error instanceof Error ? error.message : String(error);
    const errStack = error instanceof Error ? error.stack : undefined;
    console.error('[REWIND_ERROR]', errMsg);
    console.error('[REWIND_ERROR_STACK]', errStack);
    console.log(JSON.stringify({
      success: false,
      error: errMsg
    }));
  } finally {
    try {
      await result?.return?.();
    } catch {
    }
  }
}

/**
 * 解析可作为回退目标的候选用户消息 ID 列表。
 * @param {string} sessionId
 * @param {string|null} cwd
 * @param {string} providedMessageId
 * @returns {Promise<string[]>}
 */
async function resolveRewindCandidateMessageIds(sessionId, cwd, providedMessageId) {
  const messages = await readClaudeProjectSessionMessages(sessionId, cwd);
  if (!Array.isArray(messages) || messages.length === 0) {
    return [];
  }

  const byId = new Map();
  for (const m of messages) {
    if (m && typeof m === 'object' && typeof m.uuid === 'string') {
      byId.set(m.uuid, m);
    }
  }

  const isUserTextMessage = (/** @type {any} */ m) => {
    if (!m || m.type !== 'user') return false;
    const content = m.message?.content;
    if (!content) return false;
    if (typeof content === 'string') {
      return content.trim().length > 0;
    }
    if (Array.isArray(content)) {
      return content.some((b) => b && b.type === 'text' && String(b.text || '').trim().length > 0);
    }
    return false;
  };

  const candidates = [];
  const visited = new Set();

  let current = providedMessageId ? byId.get(providedMessageId) : null;
  while (current && current.uuid && !visited.has(current.uuid)) {
    visited.add(current.uuid);
    if (typeof current.uuid === 'string') {
      candidates.push(current.uuid);
    }
    if (isUserTextMessage(current) && typeof current.uuid === 'string') {
      candidates.push(current.uuid);
      break;
    }
    const parent = current.parentUuid ? byId.get(current.parentUuid) : null;
    current = parent || null;
  }

  const lastUserText = [...messages].reverse().find(isUserTextMessage);
  if (lastUserText?.uuid) {
    candidates.push(lastUserText.uuid);
  }

  const unique = [];
  const seen = new Set();
  for (const id of candidates) {
    if (!id || seen.has(id)) continue;
    seen.add(id);
    unique.push(id);
  }

  const maxCandidates = 8;
  if (unique.length <= maxCandidates) return unique;
  return unique.slice(0, maxCandidates);
}

/**
 * 读取 Claude 项目 session 文件(.jsonl)并解析为消息对象数组。
 * @param {string} sessionId
 * @param {string|null} cwd
 * @returns {Promise<any[]>}
 */
async function readClaudeProjectSessionMessages(sessionId, cwd) {
  try {
    const projectsDir = join(getClaudeDir(), 'projects');
    const sanitizedCwd = (cwd || process.cwd()).replace(/[^a-zA-Z0-9]/g, '-');
    const sessionFile = join(projectsDir, sanitizedCwd, `${sessionId}.jsonl`);
    if (!existsSync(sessionFile)) {
      return [];
    }
    const content = await readFile(sessionFile, 'utf8');
    return content
      .split('\n')
      .filter((line) => line.trim())
      .map((line) => {
        try {
          return JSON.parse(line);
        } catch {
          return null;
        }
      })
      .filter(Boolean);
  } catch {
    return [];
  }
}
