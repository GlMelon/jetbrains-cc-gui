// @ts-check
/**
 * Gateway 工具名路由:把 MCP server 原始工具名映射成全局唯一的 gateway 工具名,
 * 并能反向解析出 (sourceProvider, serverId, toolName)。
 */

/** @type {string} */
const TOOL_PREFIX = 'mcp__';

/**
 * 解析后的 gateway 工具名三元组。
 * @typedef {{ sourceProvider: string; serverId: string; toolName: string }} GatewayToolRoute
 */

/**
 * 构造 gateway 全局工具名,格式 `mcp__<sourceProvider>__<serverId>__<toolName>`,
 * 各段经 sanitize 仅保留 `[A-Za-z0-9_-]`。
 *
 * @param {string} sourceProvider 来源 provider(claude/codex/opencode)
 * @param {string} serverId       MCP server 标识
 * @param {string} toolName       MCP server 原始工具名
 * @returns {string} gateway 工具名
 */
export function gatewayToolName(sourceProvider, serverId, toolName) {
  return [TOOL_PREFIX.slice(0, -2), sourceProvider, serverId, toolName]
    .map((part) => sanitize(part))
    .join('__');
}

/**
 * 解析 gateway 工具名为 `{ sourceProvider, serverId, toolName }`;非合法名返回 null。
 *
 * @param {unknown} name 待解析值(非字符串或不匹配前缀时返回 null)
 * @returns {GatewayToolRoute | null}
 */
export function parseGatewayToolName(name) {
  if (typeof name !== 'string' || !name.startsWith(TOOL_PREFIX)) {
    return null;
  }
  const parts = name.split('__');
  if (parts.length < 4) return null;
  return {
    sourceProvider: parts[1],
    serverId: parts[2],
    toolName: parts.slice(3).join('__'),
  };
}

/**
 * ToolRouter 依赖的 supervisor 最小接口(只用到 callTool 转发)。
 * @typedef {{ callTool: (name: string, args: unknown, signal?: AbortSignal) => Promise<unknown> }} SupervisorLike
 */

/**
 * 按 gateway 工具名把调用路由到对应的 MCP server supervisor。
 */
export class ToolRouter {
  /**
   * @param {Map<string, SupervisorLike>} supervisors key = `${sourceProvider}:${serverId}`
   */
  constructor(supervisors) {
    /** @type {Map<string, SupervisorLike>} */
    this.supervisors = supervisors;
  }

  /**
   * @param {string} name gateway 工具名
   * @param {unknown} args 工具入参
   * @param {unknown} revision 版本号(当前仅作簿记透传,不再下发给 supervisor)
   * @param {AbortSignal} [signal] 取消信号(客户端中途断开时由 ipc-server abort)
   * @returns {Promise<unknown>}
   */
  async call(name, args, revision, signal) {
    const route = parseGatewayToolName(name);
    if (!route) {
      throw new Error(`Unknown gateway tool: ${name}`);
    }
    const key = `${route.sourceProvider}:${route.serverId}`;
    const supervisor = this.supervisors.get(key);
    if (!supervisor) {
      throw new Error(`MCP server unavailable: ${key}`);
    }
    return supervisor.callTool(name, args ?? {}, signal);
  }
}

/**
 * 把任意值转为安全的工具名段:转字符串、裁剪后把非法字符替换为 `_`,空串兜底 `unknown`。
 *
 * @param {unknown} value
 * @returns {string}
 */
function sanitize(value) {
  return String(value ?? 'unknown').trim().replace(/[^A-Za-z0-9_-]/g, '_') || 'unknown';
}
