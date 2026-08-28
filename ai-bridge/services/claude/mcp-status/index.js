// @ts-check
/**
 * MCP server status detection service
 * Verifies MCP server connectivity and retrieves tool listings
 *
 * Module structure:
 * - config.js: Configuration constants and security whitelists
 * - logger.js: Logging system
 * - mcp-protocol.js: MCP protocol utility functions
 * - command-validator.js: Command whitelist validation
 * - server-info-parser.js: Server info parsing
 * - process-manager.js: Process management
 * - http-verifier.js: HTTP/Streamable HTTP server verification
 * - sse-verifier.js: SSE transport server verification
 * - stdio-verifier.js: STDIO server verification
 * - config-loader.js: Configuration loading
 * - http-tools-getter.js: HTTP tools retrieval
 * - sse-tools-getter.js: SSE tools retrieval
 * - stdio-tools-getter.js: STDIO tools retrieval
 */

import { log } from './logger.js';
import { loadMcpServersConfig, loadAllMcpServersInfo } from './config-loader.js';
import { verifyHttpServerStatus } from './http-verifier.js';
import { verifySseServerStatus } from './sse-verifier.js';
import { verifyStdioServerStatus } from './stdio-verifier.js';
import { getHttpServerTools } from './http-tools-getter.js';
import { getSseServerTools } from './sse-tools-getter.js';
import { getStdioServerTools } from './stdio-tools-getter.js';

// Re-export config loading functions
export { loadMcpServersConfig, loadAllMcpServersInfo } from './config-loader.js';

/**
 * Verify the connection status of a single MCP server
 * @param {string} serverName - Server name
 * @param {Record<string, any>} serverConfig - Server configuration
 * @returns {Promise<Record<string, any>>} Server status info { name, status, serverInfo, error? }
 */
export async function verifyMcpServerStatus(serverName, serverConfig) {
  const serverType = serverConfig.type || 'stdio';

  // SSE transport uses a different handshake (GET stream → endpoint discovery → POST)
  if (serverType === 'sse') {
    return verifySseServerStatus(serverName, serverConfig);
  }

  // Streamable HTTP / generic HTTP use direct POST
  if (serverType === 'http' || serverType === 'streamable-http') {
    return verifyHttpServerStatus(serverName, serverConfig);
  }

  // STDIO transport server
  return verifyStdioServerStatus(serverName, serverConfig);
}

/**
 * Verify enabled MCP servers without allowing one rejected probe to discard
 * the successful results from the remaining servers.
 * @param {Array<{name: string, config: Record<string, any>}>} enabledServers
 * @param {(serverName: string, serverConfig: Record<string, any>) => Promise<Record<string, any>>} [verify]
 * @returns {Promise<Array<Record<string, any>>>}
 */
export async function verifyEnabledMcpServers(enabledServers, verify = verifyMcpServerStatus) {
  const settledResults = await Promise.allSettled(
    enabledServers.map(({ name, config }) => verify(name, config))
  );

  return settledResults.map((result, index) => {
    if (result.status === 'fulfilled') {
      return result.value;
    }

    const reason = result.reason;
    return {
      name: enabledServers[index].name,
      status: 'failed',
      error: reason instanceof Error ? reason.message : String(reason),
    };
  });
}

/**
 * Circuit-open marker carried in the error field of synthetic skipped results.
 * The Java circuit breaker matches on this to exclude skipped servers from its
 * failure counting (they are already open — re-counting would be meaningless).
 */
export const CIRCUIT_SKIP_MARKER = '[circuit-open]';

/**
 * Partition enabled servers into (toVerify, syntheticResults) by the skip set.
 * Skipped servers are NOT spawned/verified — they get a synthetic failed result
 * with {@link CIRCUIT_SKIP_MARKER} so the UI still shows them as failed while the
 * healthy servers continue to be verified normally.
 *
 * @param {Array<{name: string, config: Record<string, any>}>} enabled - Enabled servers
 * @param {string[] | null | undefined} skipVerify - Names whose verification is skipped
 * @returns {{ toVerify: Array<{name: string, config: Record<string, any>}>, skippedResults: Array<Record<string, any>> }}
 */
export function partitionCircuitSkipped(enabled, skipVerify) {
  const skipSet = Array.isArray(skipVerify) && skipVerify.length > 0
    ? new Set(skipVerify)
    : null;
  if (!skipSet) {
    return { toVerify: enabled, skippedResults: [] };
  }
  const toVerify = enabled.filter(({ name }) => !skipSet.has(name));
  const skippedResults = enabled
    .filter(({ name }) => skipSet.has(name))
    .map(({ name }) => ({
      name,
      status: 'failed',
      error: `${CIRCUIT_SKIP_MARKER} Verification skipped after repeated failures; retried after cooldown`,
    }));
  return { toVerify, skippedResults };
}

/**
 * Get the connection status of all MCP servers
 * Includes enabled, disabled, and invalid servers so the frontend gets a complete picture
 * @param {string | null} [cwd] - Current working directory (used to detect project config)
 * @param {string[] | null} [skipVerify] - Server names to skip verification (circuit open on the
 *   Java side after repeated failures). Skipped servers get a synthetic failed result carrying
 *   the {@link CIRCUIT_SKIP_MARKER} instead of a fresh spawn — a broken server must not keep
 *   being cold-started (npx spawn per query), and must not affect verification of healthy ones.
 * @returns {Promise<Array<Record<string, any>>>} List of MCP server statuses
 */
export async function getMcpServersStatus(cwd = null, skipVerify = null) {
  try {
    const allServers = await loadAllMcpServersInfo(cwd);

    log('info', 'Found', allServers.enabled.length, 'enabled,',
      allServers.disabled.length, 'disabled,',
      allServers.invalid.length, 'invalid MCP servers');

    const { toVerify, skippedResults } = partitionCircuitSkipped(allServers.enabled, skipVerify);

    // Verify the remaining enabled servers in parallel (one server's rejection never
    // discards the others — allSettled in verifyEnabledMcpServers)
    const enabledResults = toVerify.length > 0
      ? await verifyEnabledMcpServers(toVerify)
      : [];

    // Generate failed status for disabled servers (with reason)
    const disabledResults = allServers.disabled.map(name => ({
      name,
      status: 'failed',
      error: 'Server is disabled',
    }));

    // Generate failed status for servers with invalid config (with reason)
    const invalidResults = allServers.invalid.map(({ name, reason }) => ({
      name,
      status: 'failed',
      error: `Invalid config: ${reason}`,
    }));

    const results = [...enabledResults, ...skippedResults, ...disabledResults, ...invalidResults];

    log('info', '[MCP Status] Completed: total', results.length, 'servers (',
      enabledResults.length, 'verified,',
      disabledResults.length, 'disabled,',
      invalidResults.length, 'invalid)');

    return results;
  } catch (error) {
    log('error', 'Failed to get MCP servers status:', error instanceof Error ? error.message : String(error));
    return [];
  }
}

/**
 * Send a tools/list request to a connected MCP server
 * @param {string} serverName - Server name
 * @param {Record<string, any>} serverConfig - Server configuration
 * @returns {Promise<Record<string, any>>} Tools list response
 */
export async function getMcpServerTools(serverName, serverConfig) {
  const serverType = serverConfig.type || 'stdio';

  // SSE transport uses endpoint discovery before sending requests
  if (serverType === 'sse') {
    return getSseServerTools(serverName, serverConfig);
  }

  // Streamable HTTP / generic HTTP use direct POST
  if (serverType === 'http' || serverType === 'streamable-http') {
    return getHttpServerTools(serverName, serverConfig);
  }

  return getStdioServerTools(serverName, serverConfig);
}
