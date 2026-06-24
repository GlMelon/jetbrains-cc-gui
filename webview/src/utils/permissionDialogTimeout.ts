// C5 SSOT:权限对话框超时默认值由后端 PermissionDialogTimeoutSettings 经生成链产出
// (generated/protocol.ts),此处 import + re-export 消除手抄(原 300/30/3600 与后端
// 逐字重复)。本地 clampPermissionDialogTimeoutSeconds 直接复用导入绑定。
import {
  DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS,
  MIN_PERMISSION_DIALOG_TIMEOUT_SECONDS,
  MAX_PERMISSION_DIALOG_TIMEOUT_SECONDS,
} from '../generated/protocol';
export {
  DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS,
  MIN_PERMISSION_DIALOG_TIMEOUT_SECONDS,
  MAX_PERMISSION_DIALOG_TIMEOUT_SECONDS,
};

/**
 * Normalizes a permission dialog timeout value to a supported whole-second range.
 * Invalid values fall back to the default timeout.
 */
export function clampPermissionDialogTimeoutSeconds(value: unknown): number {
  const parsed = typeof value === 'number'
    ? value
    : typeof value === 'string' && value.trim() !== ''
      ? Number(value)
      : Number.NaN;

  if (!Number.isFinite(parsed)) {
    return DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS;
  }

  return Math.max(
    MIN_PERMISSION_DIALOG_TIMEOUT_SECONDS,
    Math.min(MAX_PERMISSION_DIALOG_TIMEOUT_SECONDS, Math.trunc(parsed)),
  );
}
