/**
 * §15.7 B2:OpenCode channel command handler。
 *
 * 仅做 dispatch,真正逻辑在 services/opencode/message-service.js(对齐 codex 的
 * services/codex/message-service.js 结构)。channel-manager.js 路由 [opencode, send]
 * 到此处,经 stdin 接收 7+ 字段(见 §15.7 B11),调 message-service 走 SDK:
 * createOpencodeClient → session.create/prompt → event.subscribe(SSE)→ NDJSON。
 */
import { sendMessage, abortSession } from '../services/opencode/message-service.js';
import { listModels } from '../services/opencode/models-service.js';

/**
 * Execute an OpenCode command.
 * @param {string} command
 * @param {string[]} args
 * @param {object|null} stdinData
 */
export async function handleOpenCodeCommand(command, args, stdinData) {
  switch (command) {
    case 'send': {
      if (stdinData && stdinData.message !== undefined) {
        const {
          message,
          threadId,
          cwd,
          permissionMode,
          model,
          reasoningEffort,
          attachments,
          baseUrl
        } = stdinData;
        await sendMessage({
          message,
          threadId: threadId || '',
          cwd: cwd || '',
          permissionMode: permissionMode || '',
          model: model || '',
          reasoningEffort: reasoningEffort || 'medium',
          attachments: attachments || [],
          baseUrl: baseUrl || ''
        });
      } else {
        // 退化:仅位置参(向后兼容/手动调试)
        await sendMessage({
          message: args[0] || '',
          threadId: args[1] || '',
          cwd: args[2] || '',
          model: args[4] || '',
          baseUrl: ''
        });
      }
      break;
    }

    case 'abort': {
      const threadId = stdinData?.threadId || args[0] || null;
      const baseUrl = stdinData?.baseUrl || '';
      if (threadId) {
        await abortSession({ threadId, baseUrl });
      }
      break;
    }

    case 'getMcpServerTools': {
      // §15.9 B22:OpenCode MCP 工具在会话层按需透传(message.part.updated type=tool → tool_use,
      // 见 event-mapper),opencode 无"列工具"命令/SDK API,故不返回工具明细。
      // MCP server 配置由后端 OpenCodeConfigReader.readMcpServers() 读取(对称 readModels SSOT)。
      // 此处返回空工具列表 + 说明,保持三 provider channel 命令对称(前端 UI defer)。
      console.log(JSON.stringify({ success: true, tools: [], note: 'opencode_mcp_passthrough' }));
      break;
    }

    case 'listModels': {
      // §15.8 §11:查询 opencode serve 已配置 provider 的模型(能力层,前端 UI defer)。
      // 调 config.providers() 扁平化 provider/models 树为模型列表,NDJSON 单对象返回。
      // channel-manager 对 opencode provider 已 force-exit,一次性 HTTP 连接由其兜底释放。
      const baseUrl = stdinData?.baseUrl || args[0] || '';
      await listModels({ baseUrl });
      break;
    }

    default:
      throw new Error(`Unknown OpenCode command: ${command}`);
  }
}

export function getOpenCodeCommandList() {
  return ['send', 'abort', 'getMcpServerTools', 'listModels'];
}

export const opencodeChannelDescriptor = {
  provider: 'opencode',
  commands: getOpenCodeCommandList(),
  handle: handleOpenCodeCommand,
};
