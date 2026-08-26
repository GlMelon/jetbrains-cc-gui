import { useEffect, useState } from 'react';
import {
  isProviderCliNotInstalled,
  requestCliEnvironmentCheck,
  subscribeCliEnvironment,
} from '../utils/cliEnvironmentStatus';

/**
 * 订阅 CLI 安装状态快照(仿 ButtonArea 的 modelRegistryVersion 模式)。
 *
 * 挂载即订阅;首次订阅会自动发起一次检测(走后端缓存,见 cliEnvironmentStatus.ts)。
 * 返回的 version 随每次检测结果下发递增,供 useMemo/useCallback 依赖触发重渲染;
 * isNotInstalled 是便捷判定(未知即放行,语义见 isProviderCliNotInstalled)。
 */
export function useCliInstallStatus(): {
  version: number;
  isNotInstalled: (providerId: string) => boolean;
  recheck: () => void;
} {
  const [version, setVersion] = useState(0);

  useEffect(() => subscribeCliEnvironment(() => setVersion((v) => v + 1)), []);

  return {
    version,
    isNotInstalled: isProviderCliNotInstalled,
    recheck: () => requestCliEnvironmentCheck(true),
  };
}
