// SDK 调用模式下的 MCP Gateway 绑定。
//
// 把来自 Java McpGatewaySdkBinding 的序列化结果翻译成单个聚合 MCP 服务器
// (melon_gateway),供 Claude/Codex/OpenCode SDK 在 stdio 下生成。与 CLI 模式
// (McpGatewayConfigWriter 写出的 mcp-gateway.json)对称:两者都只暴露一个
// melon_gateway server,由 gateway-stdio-client.js 转发到常驻 Gateway 进程。
//
// SSOT: 服务器 id "melon_gateway" = Java McpGatewayConstants.GATEWAY_SERVER_ID。
const GATEWAY_SERVER_ID = 'melon_gateway';

function isUsable(binding) {
  return Boolean(binding && binding.enabled && binding.ready);
}

/**
 * 根据 SDK binding 构造 mcpServers 记录。binding.command 形如
 * [node, <gateway-stdio-client.js>, --state-file, <path>, --revision, <n>]。
 * 不可用(未启用/未就绪/命令不完整)时返回 null,由调用方回退到本地真实 MCP。
 */
export function buildGatewayMcpServers(binding) {
  if (!isUsable(binding)) return null;
  const command = binding.command;
  if (!Array.isArray(command) || command.length < 2) return null;
  return {
    [GATEWAY_SERVER_ID]: {
      type: 'stdio',
      command: command[0],
      args: command.slice(1),
    },
  };
}

/**
 * 返回用于 runtime 缓存签名的 Gateway 维度材料。当 binding 不可用时返回 null
 * (签名不含 Gateway 维度,与未启用 Gateway 时行为一致)。revision 每轮固定一个,
 * 配置变更时递增,因此 revision 变化必须触发新 runtime 会话。
 */
export function gatewaySignatureMaterial(binding) {
  if (!isUsable(binding)) return null;
  return 'rev:' + binding.revision;
}
