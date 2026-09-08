import { useEffect } from 'react';
import { resolveReasoningLevels } from '../../utils/modelRegistry';
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
  // 档位全部以后端 registry 为准(总则一,前端不做 per-provider 判定):
  // 已知且无档位(条目在但无 supportedReasoningLevels,如 dsh/minimax / 无 role 的
  // 自定义 claude 模型)→ 隐藏;未知(registry 未加载 / 模型与 provider 均无下发)→
  // 可见,未知不等于无权。
  const levels = resolveReasoningLevels(currentProvider, selectedModel);
  return levels === null || levels.length > 0;
}

export function getAvailableReasoningLevels(
  currentProvider?: string,
  selectedModel?: string,
): ReasoningInfo[] {
  const levels = resolveReasoningLevels(currentProvider, selectedModel);
  // 档位未知时返回全集(未知不等于无权,避免误钳);权威档位按 REASONING_LEVELS 顺序过滤。
  if (levels === null) {
    return REASONING_LEVELS;
  }
  return REASONING_LEVELS.filter((level) => levels.includes(level.id));
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
  // 会滞留到 registry 到达之后——isVisible 翻 true 触发 effect 重跑时,
  // 合法的高档会被误判并改写成兜底档(2026-09 思考强度回退根因)。
  // 列表仅数项,重算开销可忽略。
  const levels = resolveReasoningLevels(currentProvider, selectedModel);
  const availableLevels = levels === null
    ? REASONING_LEVELS
    : REASONING_LEVELS.filter((level) => levels.includes(level.id));
  const currentLevel = resolveCurrentReasoningLevel(value, availableLevels);

  // 档位未知(registry 未加载 / 模型不在 registry 且 provider 无默认档位)时,
  // availableLevels 只是全集兜底而非权威档位;未知不等于权威,guard 不得据此改写
  // 持久化值——否则降级会话(sessionThinkingAvailable=false 先于 MODEL_REGISTRY 到达)下
  // 合法的高档会被错钳成兜底档并被立即持久化(2026-09 思考强度回退残留窗口)。
  // 对任意 provider 生效,不限 claude。
  const levelsUnknown = levels === null;

  useEffect(() => {
    if (availableLevels.some((level) => level.id === value)) {
      return;
    }
    // A concrete session capability is authoritative. Keep the stored effort
    // on a valid level even when the selector is hidden by a degraded session.
    if (!isVisible && sessionThinkingAvailable !== false) {
      return;
    }
    if (levelsUnknown) {
      return;
    }
    if (currentLevel) {
      onChange(currentLevel.id);
    }
  }, [availableLevels, levelsUnknown, currentLevel, isVisible, onChange, sessionThinkingAvailable, value]);

  return { isVisible, availableLevels, currentLevel };
}
