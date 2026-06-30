const TOOL_PREFIX = 'mcp__';

export function gatewayToolName(sourceProvider, serverId, toolName) {
  return [TOOL_PREFIX.slice(0, -2), sourceProvider, serverId, toolName]
    .map((part) => sanitize(part))
    .join('__');
}

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

export class ToolRouter {
  constructor(supervisors) {
    this.supervisors = supervisors;
  }

  async call(name, args, revision) {
    const route = parseGatewayToolName(name);
    if (!route) {
      throw new Error(`Unknown gateway tool: ${name}`);
    }
    const key = `${route.sourceProvider}:${route.serverId}`;
    const supervisor = this.supervisors.get(key);
    if (!supervisor) {
      throw new Error(`MCP server unavailable: ${key}`);
    }
    return supervisor.callTool(name, args ?? {}, revision);
  }
}

function sanitize(value) {
  return String(value ?? 'unknown').trim().replace(/[^A-Za-z0-9_-]/g, '_') || 'unknown';
}
