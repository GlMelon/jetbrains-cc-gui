import { useState } from 'react';
import { CLAUDE_ROLE_MODEL_IDS } from '../../components/ChatInputBox/types';
import type { PermissionMode } from '../../components/ChatInputBox/types';

/**
 * Claude-specific selectable state. State only — handlers that span providers
 * (mode/model/provider switching, long-context toggle) live in the orchestrator
 * (useModelProviderState) since they need to read both Claude and Codex state.
 */
export function useClaudeProvider() {
  // A1:初始选中值用协议常量 claude-role-sonnet(后端 role id),不再读已删除的本地表 CLAUDE_MODELS。
  const [selectedClaudeModel, setSelectedClaudeModel] = useState<string>(CLAUDE_ROLE_MODEL_IDS.sonnet);
  const [claudePermissionMode, setClaudePermissionMode] = useState<PermissionMode>('default');
  const [longContextEnabled, setLongContextEnabled] = useState(true);
  const [claudeSettingsAlwaysThinkingEnabled, setClaudeSettingsAlwaysThinkingEnabled] = useState(true);

  return {
    selectedClaudeModel,
    setSelectedClaudeModel,
    claudePermissionMode,
    setClaudePermissionMode,
    longContextEnabled,
    setLongContextEnabled,
    claudeSettingsAlwaysThinkingEnabled,
    setClaudeSettingsAlwaysThinkingEnabled,
  };
}

export type UseClaudeProviderReturn = ReturnType<typeof useClaudeProvider>;
