import type { VersionAction } from '../../../generated/protocol';

// A6:VersionAction SSOT 已下沉后端（VersionAction 枚举 → versionActions map 下发）。
// 此处仅 re-export generated 类型,前端不再手抄 'install'|'update'|'rollback'|'current' 字面量,
// 也不再保留 compareVersions/getVersionAction 决策副本（算法单一源在后端 DependencyManager）。
export type { VersionAction };

interface ResolveVersionActionInput {
  /** SDK 是否已安装（未安装时后端不下发 versionActions） */
  installed: boolean;
  /** 用户在下拉框选择的目标版本（normalized） */
  targetVersion?: string;
  /** 后端预计算的「目标版本 → 动作」映射（仅已安装时随 dependency.versions_loaded 下发） */
  versionActions?: Record<string, VersionAction>;
}

interface BuildVersionOptionsInput {
  availableVersions?: string[];
  fallbackVersions?: string[];
  installedVersion?: string;
}

export const normalizeVersion = (version?: string | null): string | undefined => {
  const trimmed = version?.trim();
  if (!trimmed) {
    return undefined;
  }

  return trimmed.startsWith('v') || trimmed.startsWith('V')
    ? trimmed.slice(1)
    : trimmed;
};

export const getRequestedVersion = (
  selectedVersion?: string,
): string | undefined => {
  return normalizeVersion(selectedVersion);
};


/**
 * 查表取版本动作（A6）:不再前端计算版本比较,而是从后端下发的 versionActions map
 * 查目标版本对应动作。
 * - 未安装 → 'install'（后端不下发 map）
 * - 已安装但 map 缺失、目标版本为空或不在表内 → 保守 'current'（降级保护,避免误判 update/rollback）
 */
export const resolveVersionAction = ({
  installed,
  targetVersion,
  versionActions,
}: ResolveVersionActionInput): VersionAction => {
  if (!installed) {
    return 'install';
  }
  if (!versionActions || !targetVersion) {
    return 'current';
  }
  return versionActions[targetVersion] ?? 'current';
};

export const buildVersionOptions = ({
  availableVersions = [],
  fallbackVersions = [],
  installedVersion,
}: BuildVersionOptionsInput): string[] => {
  const seen = new Set<string>();
  const merged = [...availableVersions, ...fallbackVersions, installedVersion];

  return merged.reduce<string[]>((result, version) => {
    const normalized = normalizeVersion(version);
    if (!normalized || seen.has(normalized)) {
      return result;
    }

    seen.add(normalized);
    result.push(normalized);
    return result;
  }, []);
};
