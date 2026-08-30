import { useEffect, useMemo } from 'react';
import { getModelSupportedReasoningLevels } from '../../utils/modelRegistry';
import {
  REASONING_LEVELS,
  type ReasoningEffort,
  type ReasoningInfo,
} from './types';

export function isReasoningVisible(
  currentProvider?: string,
  selectedModel?: string,
  sessionThinkingAvailable?: boolean,
): boolean {
  if (sessionThinkingAvailable === false) return false;
  // A2(2026-06-23):claude 的 reasoning 能力以后端 registry 派生为准(空档位=不可见);
  // 其余 provider 默认可见(档位由下方通用规则给出)。
  if (currentProvider === 'claude' && selectedModel) {
    const levels = getModelSupportedReasoningLevels(selectedModel);
    return levels !== null && levels.length > 0;
  }
  return true;
}

export function getAvailableReasoningLevels(
  currentProvider?: string,
  selectedModel?: string,
): ReasoningInfo[] {
  return REASONING_LEVELS.filter((level) => {
    if (currentProvider === 'grok') {
      return level.id === 'low' || level.id === 'medium' || level.id === 'high';
    }
    if (currentProvider === 'codex') {
      // codexModelSupportsMaxEffort 已随 A2 下沉;codex 不展示 max 档。
      return level.id !== 'max';
    }
    if (currentProvider !== 'claude') {
      return level.id !== 'max';
    }
    if (!selectedModel) {
      return true;
    }
    // A2:claude 档位由后端 registry 派生(role→supportedReasoningLevels)。
    const levels = getModelSupportedReasoningLevels(selectedModel);
    if (levels === null) {
      return level.id !== 'xhigh' && level.id !== 'max';
    }
    return levels.includes(level.id);
  });
}

export function resolveCurrentReasoningLevel(
  value: ReasoningEffort,
  availableLevels: ReasoningInfo[],
): ReasoningInfo | undefined {
  return availableLevels.find((level) => level.id === value)
    || availableLevels[availableLevels.length - 2]
    || availableLevels[0];
}

export function useReasoningEffortGuard(
  value: ReasoningEffort,
  onChange: (effort: ReasoningEffort) => void,
  selectedModel?: string,
  currentProvider?: string,
  sessionThinkingAvailable?: boolean,
): {
  isVisible: boolean;
  availableLevels: ReasoningInfo[];
  currentLevel: ReasoningInfo | undefined;
} {
  const isVisible = isReasoningVisible(currentProvider, selectedModel, sessionThinkingAvailable);
  const availableLevels = useMemo(
    () => getAvailableReasoningLevels(currentProvider, selectedModel),
    [currentProvider, selectedModel],
  );
  const currentLevel = resolveCurrentReasoningLevel(value, availableLevels);

  useEffect(() => {
    if (availableLevels.some((level) => level.id === value)) {
      return;
    }
    // A concrete session capability is authoritative. Keep the stored effort
    // on a valid level even when the selector is hidden by a degraded session.
    if (!isVisible && sessionThinkingAvailable !== false) {
      return;
    }
    if (currentLevel) {
      onChange(currentLevel.id);
    }
  }, [availableLevels, currentLevel, isVisible, onChange, sessionThinkingAvailable, value]);

  return { isVisible, availableLevels, currentLevel };
}
