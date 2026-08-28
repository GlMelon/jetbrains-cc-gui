// @ts-check
/**
 * Message sending service module — coordinator.
 *
 * Responsible for sending messages through the Claude Code CLI.
 * Re-exports all public API from focused submodules:
 *   - message-utils.js: retry, truncation, error payloads
 *   - permission-mode.js: Unified tool permission policy and PreToolUse hook
 *   - message-session-registry.js: Active session state
 *   - message-sender.js: sendMessage, sendMessageWithAttachments
 */

// Re-export send functions
export { sendMessage, sendMessageWithAttachments } from './message-sender.js';

// Re-export session registry functions
export {
  getActiveSessionIds,
  hasActiveSession,
  removeSession,
  registerActiveQueryResult
} from './message-session-registry.js';

// Re-export error payload builder for external consumers
export { buildConfigErrorPayload } from './message-utils.js';

// MCP dependencies
import {
  getMcpServersStatus,
  getMcpServerTools as getMcpServerToolsImpl,
  loadMcpServersConfig
} from './mcp-status/index.js';

// NOTE: getSlashCommands() was removed — slash commands are now resolved
// locally by Java SlashCommandRegistry (no bridge call needed).

/**
 * Get MCP server connection status.
 * Directly validates the actual connection status of each MCP server (via mcp-status-service module).
 * @param {string|null} [cwd=null] - Working directory (used to detect project-specific MCP configuration)
 * @param {string[]} [skipVerify=[]] - Server names to skip verification (Java-side circuit open);
 *   each gets a synthetic failed result with the [circuit-open] marker instead of a fresh spawn
 * @returns {Promise<void>}
 */
export async function getMcpServerStatus(cwd = null, skipVerify = []) {
  try {
    // Use the mcp-status-service module to get status, passing cwd for project-specific config
    const mcpStatus = await getMcpServersStatus(/** @type {string} */ (cwd), skipVerify);

    // Output with [MCP_SERVER_STATUS] tag for fast identification on the Java side.
    // Also keep a compatible JSON format as fallback.
    console.log('[MCP_SERVER_STATUS]' + JSON.stringify(mcpStatus));
  } catch (error) {
    console.error('[GET_MCP_SERVER_STATUS_ERROR]', error instanceof Error ? error.message : String(error));
    // Use the tag on error too, so the Java side can identify it quickly
    console.log('[MCP_SERVER_STATUS]' + JSON.stringify([]));
  }
}

/**
 * Get the tools list for a specific MCP server.
 * Directly connects to the MCP server and retrieves its available tools (via mcp-status-service module).
 * @param {string} serverId - MCP server ID
 * @param {string|null} [cwd=null] - Working directory (used to detect project-specific MCP configuration)
 * @returns {Promise<void>}
 */
export async function getMcpServerTools(serverId, cwd = null) {
  try {
    console.log('[McpTools] Getting tools for MCP server:', serverId);

    // First load server configuration, passing cwd for project-specific config
    const mcpServers = await loadMcpServersConfig(cwd);
    const targetServer = mcpServers.find(s => s.name === serverId);

    if (!targetServer) {
      console.log(JSON.stringify({
        success: false,
        serverId,
        error: `Server not found: ${serverId}`
      }));
      return;
    }

    // Call mcp-status-service to get the tools list
    /** @type {{ tools?: unknown[]; error?: string; name?: string }} */
    const toolsResult = await getMcpServerToolsImpl(serverId, targetServer.config);

    // Output results with a prefix tag for quick identification by the Java backend
    const tools = toolsResult.tools || [];
    const hasError = !!toolsResult.error;
    // success=true means tools are usable; error may still contain warnings
    // success=false only when no tools AND has error (e.g. timeout, connection failure)
    const resultJson = JSON.stringify({
      success: !hasError || tools.length > 0,
      serverId,
      serverName: toolsResult.name,
      tools,
      error: toolsResult.error
    });
    console.log('[MCP_SERVER_TOOLS]', resultJson);
    console.log(resultJson);

  } catch (error) {
    const errMsg = error instanceof Error ? error.message : String(error);
    console.error('[GET_MCP_SERVER_TOOLS_ERROR]', errMsg);
    console.log(JSON.stringify({
      success: false,
      serverId,
      error: errMsg,
      tools: []
    }));
  }
}
