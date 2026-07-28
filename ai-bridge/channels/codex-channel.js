// @ts-check
/**
 * Codex channel command handler – keeps Codex specific logic separated.
 */
import { sendMessage as codexSendMessage } from '../services/codex/message-service.js';
import { getMcpServerTools as codexGetMcpServerTools } from '../services/codex/message-service.js';
import { resetCodexThreadCache } from '../services/codex/message-service.js';

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
          attachments,  // Image attachments (local_image format)
          mcpGatewayBinding  // MCP Gateway SDK 绑定(来自 Java stdin 注入)
        } = stdinData;
        await codexSendMessage(
          message,
          threadId || '',
          cwd || '',
          permissionMode || '',
          model || '',
          baseUrl || '',
          apiKey || '',
          (reasoningEffort === 'max' ? 'xhigh' : (reasoningEffort || 'medium')),
          serviceTier || '',
          attachments || [],  // Pass attachments to message service
          mcpGatewayBinding || null  // MCP Gateway SDK 绑定(无则回退真实 MCP)
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

    case 'clearThreadCache': {
      const threadId = stdinData?.threadId || args[0] || null;
      resetCodexThreadCache(threadId);
      break;
    }

    default:
      throw new Error(`Unknown Codex command: ${command}`);
  }
}

/** @returns {string[]} */
export function getCodexCommandList() {
  return ['send', 'getMcpServerTools', 'clearThreadCache'];
}

export const codexChannelDescriptor = {
  provider: 'codex',
  commands: getCodexCommandList(),
  handle: handleCodexCommand,
};
