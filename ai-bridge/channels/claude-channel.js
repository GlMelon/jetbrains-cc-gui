// @ts-check
/**
 * Claude channel command handler – isolates all Claude specific command logic
 * away from the shared channel-manager entry point.
 *
 * send/sendWithAttachments 经 services/claude/message-service.js(→ message-sender.js)
 * 调用 Claude CLI（不再使用 SDK），服务于 commit message 生成(CommitMessageAiService)
 * 等一次性子进程任务;会话交互式发送走 ClaudeCliSession(CLI),不经此处。
 * SDK 已完全移除，所有功能通过 CLI 子进程实现。
 */
import {
  sendMessage as claudeSendMessage,
  sendMessageWithAttachments as claudeSendMessageWithAttachments,
  getMcpServerStatus as claudeGetMcpServerStatus,
  getMcpServerTools as claudeGetMcpServerTools
} from '../services/claude/message-service.js';
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

    case 'getMcpServerStatus': {
      const cwd = stdinData?.cwd || args[0] || null;
      // Java 侧熔断名单:连续失败 ≥3 的 server 跳过验证(合成 [circuit-open] 失败结果,
      // 不再冷启动 spawn),成功 server 照常验证
      const skipVerify = Array.isArray(stdinData?.skipVerify) ? stdinData.skipVerify : [];
      await claudeGetMcpServerStatus(cwd, skipVerify);
      break;
    }

    case 'getMcpServerTools': {
      const serverId = stdinData?.serverId || args[0] || null;
      const cwd = stdinData?.cwd || args[1] || null;
      await claudeGetMcpServerTools(serverId, cwd);
      break;
    }

    default:
      throw new Error(`Unknown Claude command: ${command}`);
  }
}

/** @returns {string[]} */
export function getClaudeCommandList() {
  return ['send', 'sendWithAttachments', 'getSession', 'getLatestUserMessage', 'getMcpServerStatus', 'getMcpServerTools'];
}

export const claudeChannelDescriptor = {
  provider: 'claude',
  commands: getClaudeCommandList(),
  handle: handleClaudeCommand,
};
