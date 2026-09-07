import { useEffect } from 'react';
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
  // 档位列表必须与上方 isVisible 同源(每次渲染重算,读当前 registry 快照)。
  // registry 是模块级可变状态,经 MODEL_REGISTRY 异步下发;若用 useMemo 缓存
  // (deps 仅 currentProvider/selectedModel),mount 时缓存下的空 registry 档位列表
  // (3 档)会滞留到 registry 到达之后——isVisible 翻 true 触发 effect 重跑时,
  // 合法的 max/xhigh 被误判并改写成兜底 medium(2026-09 思考强度回退根因)。
  // 列表仅 5 项,重算开销可忽略。
  const availableLevels = getAvailableReasoningLevels(currentProvider, selectedModel);
  const currentLevel = resolveCurrentReasoningLevel(value, availableLevels);

  // claude 档位未知(registry 未加载 / 模型无 registry 条目,getModelSupportedReasoningLevels 返 null)
  // 时,availableLevels 只是 3 档兜底而非权威档位;未知不等于权威,guard 不得据此改写
  // 持久化值——否则降级会话(sessionThinkingAvailable=false 先于 MODEL_REGISTRY 到达)下
  // 合法的 max/xhigh 会被错钳成兜底 medium 并被立即持久化(2026-09 思考强度回退残留窗口)。
  const claudeLevelsUnknown = currentProvider === 'claude'
    && !!selectedModel
    && getModelSupportedReasoningLevels(selectedModel) === null;

  useEffect(() => {
    if (availableLevels.some((level) => level.id === value)) {
      return;
    }
    // A concrete session capability is authoritative. Keep the stored effort
    // on a valid level even when the selector is hidden by a degraded session.
    if (!isVisible && sessionThinkingAvailable !== false) {
      return;
    }
    if (claudeLevelsUnknown) {
      return;
    }
    if (currentLevel) {
      onChange(currentLevel.id);
    }
  }, [availableLevels, claudeLevelsUnknown, currentLevel, isVisible, onChange, sessionThinkingAvailable, value]);

  return { isVisible, availableLevels, currentLevel };
}
