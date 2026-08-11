// @ts-check
/**
 * Codex channel command handler – keeps Codex specific logic separated.
 *
 * send 经 services/codex/message-service.js 调 @openai/codex-sdk,服务于 commit message
 * 生成(CommitMessageAiService);会话交互式发送走 CodexCliSession(CLI),不经此处。
 * clearThreadCache(SDK thread 缓存清理)已随 SDK 会话模式移除——CLI 模式每次 spawn 新进程,
 * 无跨进程 thread 缓存。
 */
import { sendMessage as codexSendMessage, getMcpServerTools as codexGetMcpServerTools } from '../services/codex/message-service.js';

/**
 * Execute a Codex command.
 * @param {string} command
 * @param {string[]} args
 * @param {Record<string, any> | null} stdinData
 * @returns {Promise<void>}
 */
export async function handleCodexCommand(command, args, stdinData) {
  switch (command) {
    case 'send': {
      if (stdinData && stdinData.message !== undefined) {
        const {
          message,
          threadId,
          cwd,
          permissionMode,
          model,
          baseUrl,
          apiKey,
          reasoningEffort,
          serviceTier,
          attachments  // Image attachments (local_image format)
        } = stdinData;
        await codexSendMessage(
          message,
          threadId || '',
          cwd || '',
          permissionMode || '',
          model || '',
          baseUrl || '',
          apiKey || '',
          (reasoningEffort || 'medium'),
          serviceTier || '',
          attachments || []  // Pass attachments to message service
        );
      } else {
        await codexSendMessage(args[0], args[1], args[2], args[3], args[4]);
      }
      break;
    }

    case 'getMcpServerTools': {
      const serverId = stdinData?.serverId || args[0] || null;
      const serverConfig = stdinData?.serverConfig || null;
      await codexGetMcpServerTools(serverId, serverConfig);
      break;
    }

    default:
      throw new Error(`Unknown Codex command: ${command}`);
  }
}

/** @returns {string[]} */
export function getCodexCommandList() {
  return ['send', 'getMcpServerTools'];
}

export const codexChannelDescriptor = {
  provider: 'codex',
  commands: getCodexCommandList(),
  handle: handleCodexCommand,
};
