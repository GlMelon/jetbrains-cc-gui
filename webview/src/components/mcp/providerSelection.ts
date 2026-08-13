export type McpProvider = 'claude' | 'codex' | 'opencode';

export function resolveInitialMcpProvider(
  currentProvider: string,
  savedProvider: string | null,
): McpProvider {
  if (savedProvider === 'claude' || savedProvider === 'codex' || savedProvider === 'opencode') {
    return savedProvider;
  }
  if (currentProvider === 'codex') return 'codex';
  if (currentProvider === 'opencode') return 'opencode';
  return 'claude';
}

export function getMcpMessagePrefix(provider: McpProvider): '' | 'codex_' {
  return provider === 'codex' ? 'codex_' : '';
}
