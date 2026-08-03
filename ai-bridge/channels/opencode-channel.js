// @ts-check
/**
 * §15.7 B2:OpenCode channel command handler。
 *
 * 仅做 dispatch,真正逻辑在 services/opencode/message-service.js(对齐 codex 的
 * services/codex/message-service.js 结构)。channel-manager.js 路由 [opencode, send]
 * 到此处,经 stdin 接收 7+ 字段(见 §15.7 B11),调 message-service 走 SDK:
 * createOpencodeClient → session.create/prompt → event.subscribe(SSE)→ NDJSON。
 * getSession 通过 services/opencode/history-service.js 只读本地 SQLite 历史。
 */
import { sendMessage, abortSession } from '../services/opencode/message-service.js';
import { getSessionMessages, getSessionList, archiveSession } from '../services/opencode/history-service.js';

/**
 * Execute an OpenCode command.
 * @param {string} command
 * @param {string[]} args
 * @param {Record<string, any> | null} stdinData
 * @returns {Promise<void>}
 */
export async function handleOpenCodeCommand(command, args, stdinData) {
  switch (command) {
    case 'send': {
      if (stdinData && stdinData.message !== undefined) {
        const {
          message,
          threadId,
          cwd,
          model,
          reasoningEffort,
          attachments,
          baseUrl
        } = stdinData;
        await sendMessage({
          message,
          threadId: threadId || '',
          cwd: cwd || '',
          model: model || '',
          reasoningEffort: reasoningEffort || 'high',
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

    case 'getSession': {
      const sessionId = stdinData?.sessionId || stdinData?.threadId || args[0] || '';
      const result = await getSessionMessages({
        sessionId,
        dbPath: stdinData?.dbPath,
        maxMessageCount: stdinData?.maxMessageCount,
        maxUtf8Bytes: stdinData?.maxUtf8Bytes,
      });
      console.log(JSON.stringify(result));
      break;
    }

    case 'listSessions': {
      // 枚举 OpenCode 会话(对称 Codex getSessionsForProjectAsJson);projectPath 为空返回全部。
      const projectPath = stdinData?.projectPath || stdinData?.cwd || '';
      const result = await getSessionList({ projectPath, dbPath: stdinData?.dbPath });
      console.log(JSON.stringify(result));
      break;
    }

    case 'archiveSession': {
      // 归档 OpenCode 会话;history-service.archiveSession 置 time_archived,
      // getSessionList 已过滤归档项,故前端 reload 后不再显示。
      const sessionId = stdinData?.sessionId || stdinData?.threadId || args[0] || '';
      const result = await archiveSession({ sessionId, dbPath: stdinData?.dbPath });
      console.log(JSON.stringify(result));
      break;
    }

    default:
      throw new Error(`Unknown OpenCode command: ${command}`);
  }
}

/** @returns {string[]} */
export function getOpenCodeCommandList() {
  return ['send', 'abort', 'getSession', 'listSessions', 'archiveSession', 'getMcpServerTools'];
}

export const opencodeChannelDescriptor = {
  provider: 'opencode',
  commands: getOpenCodeCommandList(),
  handle: handleOpenCodeCommand,
};
