// @ts-check
/**
 * OpenCode channel command handler。
 *
 * SDK 已完全移除，会话发送走 OpenCodeCliSession(spawn opencode CLI)。
 * commit message 不支持 opencode(仅 claude/codex)。
 * 此处仅保留历史读取(getSession/listSessions/archiveSession,经 history-service 只读本地
 * SQLite)与 MCP 工具查询(对称空列表),供 channel-manager.js dispatch。
 */
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
    case 'getMcpServerTools': {
      // §15.9 B22:OpenCode MCP 工具在会话层按需透传(message.part.updated type=tool → tool_use),
      // opencode 无"列工具"命令/SDK API,故不返回工具明细。
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
      // 枚举 OpenCode 会话;projectPath 为空返回全部。
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
  return ['getSession', 'listSessions', 'archiveSession', 'getMcpServerTools'];
}

export const opencodeChannelDescriptor = {
  provider: 'opencode',
  commands: getOpenCodeCommandList(),
  handle: handleOpenCodeCommand,
};
