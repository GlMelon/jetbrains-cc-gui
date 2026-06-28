import { useState } from 'react';

/**
 * OpenCode 专属可选中状态。
 *
 * OpenCode 无独立 permissionMode(恒 {@code 'default'},见
 * {@link useModelProviderState} 的 {@code handleProviderSelect}:切换到 opencode
 * 时强制 {@code 'default'}),也无 reasoning/fastMode 概念;唯一独立选择状态是
 * model(对应 {@code ~/.config/opencode/opencode.json} 的 {@code provider.*.models}
 * 配置,由后端 ReadOnlyDefaultModels + OpenCodeConfigReader 读取后随 MODEL_REGISTRY
 * 下发,前端只读消费)。
 */
export function useOpenCodeProvider() {
  const [selectedOpenCodeModel, setSelectedOpenCodeModel] = useState('');
  return { selectedOpenCodeModel, setSelectedOpenCodeModel };
}

export type UseOpenCodeProviderReturn = ReturnType<typeof useOpenCodeProvider>;
