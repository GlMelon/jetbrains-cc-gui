import { sendAction } from '../../bridge/typed';
import { UPSTREAM } from '../../generated/protocol';
import { useCallback, useState } from 'react';
import type { CodexFastMode, PermissionMode, ReasoningEffort } from '../../components/ChatInputBox/types';

/**
 * Codex-specific selectable state. `reasoningEffort` lives here because the
 * value set is a Codex/OpenAI concept (low/medium/high/xhigh/max). The change
 * handler forwards directly to the backend via bridge event.
 */
export function useCodexProvider() {
  const [selectedCodexModel, setSelectedCodexModel] = useState('');
  const [codexPermissionMode, setCodexPermissionMode] = useState<PermissionMode>('default');
  const [reasoningEffort, setReasoningEffort] = useState<ReasoningEffort>('high');
  const [codexFastMode, setCodexFastMode] = useState<CodexFastMode>('normal');

  const handleReasoningChange = useCallback((effort: ReasoningEffort) => {
    setReasoningEffort(effort);
    sendAction(UPSTREAM.SET_REASONING_EFFORT, effort);
  }, []);

  const handleCodexFastModeChange = useCallback((mode: CodexFastMode) => {
    setCodexFastMode(mode);
    sendAction(UPSTREAM.SET_CODEX_FAST_MODE, mode);
  }, []);

  return {
    selectedCodexModel,
    setSelectedCodexModel,
    codexPermissionMode,
    setCodexPermissionMode,
    reasoningEffort,
    setReasoningEffort,
    codexFastMode,
    setCodexFastMode,
    handleReasoningChange,
    handleCodexFastModeChange,
  };
}
