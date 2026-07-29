import { describe, it, expect } from 'vitest';
import { isValidPermissionMode, VALID_PERMISSION_MODE_IDS } from '../../../src/components/ChatInputBox/types';

/**
 * C2 回归防护:PermissionMode 值域 SSOT 对齐。
 *
 * 后端 session(SessionState#VALID_PERMISSION_MODES)接受 5 值含 autoEdit(acceptEdits
 * 历史别名,见 protocol/PermissionMode.java)。前端校验入口 isValidPermissionMode 必须同步
 * 覆盖,否则后端下发 autoEdit 时状态静默丢失——
 * 原 bug:VALID_PERMISSION_MODE_IDS 从展示列表 AVAILABLE_MODES(4 值)派生,漏 autoEdit。
 * 修复:改从 SSOT PERMISSION_MODE(5 值)派生,展示与校验解耦。
 */
describe('PermissionMode SSOT (C2)', () => {
  it('VALID_PERMISSION_MODE_IDS 覆盖 SSOT 全部 5 值(含 autoEdit 别名)', () => {
    expect(VALID_PERMISSION_MODE_IDS.size).toBe(5);
    expect(VALID_PERMISSION_MODE_IDS.has('default')).toBe(true);
    expect(VALID_PERMISSION_MODE_IDS.has('acceptEdits')).toBe(true);
    expect(VALID_PERMISSION_MODE_IDS.has('plan')).toBe(true);
    expect(VALID_PERMISSION_MODE_IDS.has('bypassPermissions')).toBe(true);
    expect(VALID_PERMISSION_MODE_IDS.has('autoEdit')).toBe(true);
  });

  it('isValidPermissionMode 接受 autoEdit(原 bug:被当作非法值拒绝 → 状态丢失)', () => {
    expect(isValidPermissionMode('autoEdit')).toBe(true);
    expect(isValidPermissionMode('acceptEdits')).toBe(true);
    expect(isValidPermissionMode('default')).toBe(true);
    expect(isValidPermissionMode('plan')).toBe(true);
    expect(isValidPermissionMode('bypassPermissions')).toBe(true);
  });

  it('isValidPermissionMode 拒绝非法值与空值', () => {
    expect(isValidPermissionMode('nonexistent_mode')).toBe(false);
    expect(isValidPermissionMode(undefined)).toBe(false);
    expect(isValidPermissionMode(null)).toBe(false);
    expect(isValidPermissionMode('')).toBe(false);
  });
});
