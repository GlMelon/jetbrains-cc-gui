import { PROVIDER_TYPE } from '../../generated/protocol';

// MCP tab 支持配置管理的 provider 子集;词表引用生成常量 PROVIDER_TYPE(Java ProviderType SSOT)。
export type McpProvider = (typeof PROVIDER_TYPE)[keyof typeof PROVIDER_TYPE];

const MCP_PROVIDERS: readonly string[] = [PROVIDER_TYPE.CLAUDE, PROVIDER_TYPE.CODEX, PROVIDER_TYPE.OPENCODE];

export function resolveInitialMcpProvider(
  currentProvider: string,
  savedProvider: string | null,
): McpProvider {
  if (savedProvider !== null && MCP_PROVIDERS.includes(savedProvider)) {
    return savedProvider as McpProvider;
  }
  if (currentProvider === PROVIDER_TYPE.CODEX) return PROVIDER_TYPE.CODEX;
  if (currentProvider === PROVIDER_TYPE.OPENCODE) return PROVIDER_TYPE.OPENCODE;
  return PROVIDER_TYPE.CLAUDE;
}

export function getMcpMessagePrefix(provider: McpProvider): '' | 'codex_' {
  // 前缀拼接是 UPSTREAM.GET_CODEX_MCP_SERVERS 等动作命名约定的第二真相源(收敛需协议演进,暂保留)。
  return provider === PROVIDER_TYPE.CODEX ? 'codex_' : '';
}
