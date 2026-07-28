// @ts-check
/**
 * Claude channel command handler – isolates all Claude specific command logic
 * away from the shared channel-manager entry point.
 */
import {
  sendMessage as claudeSendMessage,
  sendMessageWithAttachments as claudeSendMessageWithAttachments,
  rewindFiles as claudeRewindFiles,
  getMcpServerStatus as claudeGetMcpServerStatus,
  getMcpServerTools as claudeGetMcpServerTools
} from '../services/claude/message-service.js';
import {
  resetRuntimePersistent as claudeResetRuntimePersistent
} from '../services/claude/persistent-query-service.js';
import {
  getSessionMessages as claudeGetSessionMessages,
  getLatestUserMessage as claudeGetLatestUserMessage
} from '../services/claude/session-service.js';

/**
 * Execute a Claude specific command.
 * @param {string} command
 * @param {string[]} args
 * @param {Record<string, any> | null} stdinData
 * @returns {Promise<void>}
 */
export async function handleClaudeCommand(command, args, stdinData) {
  switch (command) {
    case 'send': {
      if (stdinData && stdinData.message !== undefined) {
        // Include streaming and disableThinking when destructuring
        const { message, sessionId, cwd, permissionMode, model, actualModel, openedFiles, agentPrompt, streaming, disableThinking, reasoningEffort } = stdinData;
        await claudeSendMessage(
          message,
          sessionId || '',
          cwd || '',
          permissionMode || '',
          model || '',
          actualModel || '',
          openedFiles || null,
          agentPrompt || null,
          streaming,  // Pass streaming parameter
          disableThinking || false,  // Pass disableThinking parameter
          reasoningEffort || null  // Pass reasoning effort level
        );
      } else {
        await claudeSendMessage(args[0], args[1], args[2], args[3], args[4]);
      }
      break;
    }

    case 'sendWithAttachments': {
      if (stdinData && stdinData.message !== undefined) {
        // Include streaming when destructuring
        const { message, sessionId, cwd, permissionMode, model, actualModel, attachments, openedFiles, agentPrompt, streaming, reasoningEffort } = stdinData;
        await claudeSendMessageWithAttachments(
          message,
          sessionId || '',
          cwd || '',
          permissionMode || '',
          model || '',
          attachments ? { attachments, actualModel, openedFiles, agentPrompt, streaming, reasoningEffort } : { actualModel, openedFiles, agentPrompt, streaming, reasoningEffort }
        );
      } else {
        // stdinData 的声明类型为 Record|null,但 sendMessageWithAttachments 的 stdinData
        // 形参经 JSDoc 标注为 object(session-service 未细化),此处显式断言对齐。
        await claudeSendMessageWithAttachments(args[0], args[1], args[2], args[3], args[4], /** @type {any} */ (stdinData));
      }
      break;
    }

    case 'getSession':
      // session-service 的 cwd 形参默认值 = null 致推断为 null|undefined,运行时实为 string,
      // 此处显式断言对齐(行为等价,仅类型化)。
      await claudeGetSessionMessages(args[0], /** @type {any} */ (args[1]));
      break;

    case 'getLatestUserMessage':
      await claudeGetLatestUserMessage(args[0], /** @type {any} */ (args[1]));
      break;

    case 'rewindFiles': {
      const sessionId = stdinData?.sessionId || args[0];
      const userMessageId = stdinData?.userMessageId || args[1];
      const cwd = stdinData?.cwd || args[2] || null;
      if (!sessionId || !userMessageId) {
        console.log(JSON.stringify({
          success: false,
          error: 'Missing required parameters: sessionId and userMessageId'
        }));
        return;
      }
      await claudeRewindFiles(sessionId, userMessageId, cwd);
      break;
    }

    case 'getMcpServerStatus': {
      const cwd = stdinData?.cwd || args[0] || null;
      await claudeGetMcpServerStatus(cwd);
      break;
    }

    case 'getMcpServerTools': {
      const serverId = stdinData?.serverId || args[0] || null;
      const cwd = stdinData?.cwd || args[1] || null;
      await claudeGetMcpServerTools(serverId, cwd);
      break;
    }

    case 'resetRuntime': {
      await claudeResetRuntimePersistent(stdinData || {});
      break;
    }

    case 'getContextUsage': {
      // getContextUsage requires a persistent runtime (daemon mode).
      // In per-process mode, there is no persistent runtime, so return an error.
      console.log(JSON.stringify({
        success: false,
        error: 'getContextUsage requires daemon mode. No persistent runtime available in per-process mode.'
      }));
      break;
    }

    default:
      throw new Error(`Unknown Claude command: ${command}`);
  }
}

/** @returns {string[]} */
export function getClaudeCommandList() {
  return ['send', 'sendWithAttachments', 'getSession', 'getLatestUserMessage', 'rewindFiles', 'getMcpServerStatus', 'getMcpServerTools', 'resetRuntime', 'getContextUsage'];
}

export const claudeChannelDescriptor = {
  provider: 'claude',
  commands: getClaudeCommandList(),
  handle: handleClaudeCommand,
};
