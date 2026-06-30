// SDK 调用模式下的 MCP Gateway 绑定（Codex 专属翻译）。
//
// 把来自 Java McpGatewaySdkBinding 的序列化结果翻译成 Codex SDK 的 config override
// (mcp_servers.melon_gateway),由 @openai/codex-sdk 的 flattenConfigOverrides 展平成
// --config mcp_servers.melon_gateway.command="node" args=[...] enabled=true
// startup_timeout_sec=1 等 dotted-path 参数注入底层 Codex CLI。与 Claude 的
// buildGatewayMcpServers(产出 mcpServers option)对称,但格式遵循 Codex config.toml
// 表语义(command + args + enabled + startup_timeout_sec,无 type 字段——Codex 默认推断
// local stdio),与 CLI 模式 McpGatewayConfigWriter.writeCodex 写出的 [mcp_servers.melon_gateway]
// 表字段一致。
//
// 设计权衡:Codex SDK 不支持 per-call mcpServers option(已查 @openai/codex-sdk 类型确认),
// 只能经 codexOptions.config 叠加。--config 是叠加覆盖而非替换,故用户 ~/.codex/config.toml
// 中的真实 MCP servers 会与 melon_gateway 并存(gateway 聚合已包含它们,工具会重复暴露)。
// 这是 Codex 与 Claude(gateway 完全替代真实 MCP)的不对称;由 mcpGateway.sdk.enabled 默认
// 关闭 + 显式启用时接受该权衡(避免临时 CODEX_HOME/复制 config)。
//
// SSOT: 服务器 id "melon_gateway" = Java McpGatewayConstants.GATEWAY_SERVER_ID。

const GATEWAY_SERVER_ID = 'melon_gateway';

function isUsable(binding) {
  return Boolean(binding && binding.enabled && binding.ready);
}

/**
 * 根据 SDK binding 构造 Codex config override(mcp_servers.melon_gateway)。
 * binding.command 形如 [node, <gateway-stdio-client.js>, --state-file, <path>, --revision, <n>]。
 * 不可用(未启用/未就绪/命令不完整)时返回 null,由调用方回退(用户真实 MCP 经 config.toml)。
 */
export function buildCodexGatewayConfig(binding) {
  if (!isUsable(binding)) return null;
  const command = binding.command;
  if (!Array.isArray(command) || command.length < 2) return null;
  return {
    mcp_servers: {
      [GATEWAY_SERVER_ID]: {
        command: command[0],
        args: command.slice(1),
        enabled: true,
        startup_timeout_sec: 1,
      },
    },
  };
}

/**
 * 返回用于 Codex thread cache 签名的 Gateway revision 维度。binding 不可用时返回 null
 * (签名不含 Gateway 维度,与未启用 Gateway 时行为一致)。revision 每轮固定一个、配置变更时
 * 递增,因此 revision 变化必须触发新 codex 实例/thread,避免复用持有过期 gateway 工具集的会话。
 */
export function codexGatewayRevision(binding) {
  if (!isUsable(binding)) return null;
  return binding.revision;
}

/**
 * 把 gateway config override(mcp_servers.melon_gateway)合并进 codexOptions.config,
 * 保留 codexOptions 已有的 config 字段(如 service_tier/features)。binding 不可用时
 * 原样返回 codexOptions(引用不变,调用方无需判空)。由 message-service 在创建 codex
 * 实例前调用;Codex SDK 会把合并后的 config 展平成 --config 注入底层 Codex CLI。
 */
export function applyCodexGateway(codexOptions, binding) {
  const gatewayConfig = buildCodexGatewayConfig(binding);
  if (!gatewayConfig) return codexOptions;
  return {
    ...codexOptions,
    config: { ...(codexOptions && codexOptions.config), ...gatewayConfig },
  };
}
