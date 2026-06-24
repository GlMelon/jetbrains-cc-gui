import { describe, it, expect } from 'vitest';
import { isProtectedEnvVarKey } from '../types/provider';
import { CODEX_PROTECTED_ENV_KEY } from '../generated/protocol';

/**
 * A5:Codex 受保护环境变量键消费层守门。isProtectedEnvVarKey 的数据源现由后端
 * protocol.CodexProtectedEnvKey 枚举经生成链派生(types/provider.ts 消费
 * CODEX_PROTECTED_ENV_KEY,原手抄 18 项 Set 已消除)。此处守门数量、值、大小写
 * 不敏感行为,防止枚举漂移或派生断裂静默改变受保护集合。
 */
describe('isProtectedEnvVarKey — A5 SSOT 派生守门', () => {
  it('CODEX_PROTECTED_ENV_KEY 恰好 18 个基础键', () => {
    expect(Object.keys(CODEX_PROTECTED_ENV_KEY)).toHaveLength(18);
  });

  it('生成的所有键均判为 protected', () => {
    for (const key of Object.values(CODEX_PROTECTED_ENV_KEY)) {
      expect(isProtectedEnvVarKey(key)).toBe(true);
    }
  });

  it('大小写不敏感:小写/混合变体仍判 protected(跨平台一致性)', () => {
    expect(isProtectedEnvVarKey('codex_model')).toBe(true);
    expect(isProtectedEnvVarKey('home')).toBe(true);
    expect(isProtectedEnvVarKey('project_path')).toBe(true);
    expect(isProtectedEnvVarKey('Codex_Home')).toBe(true);
  });

  it('非保护键判 false', () => {
    expect(isProtectedEnvVarKey('MY_CUSTOM_API_KEY')).toBe(false);
    expect(isProtectedEnvVarKey('OPENAI_BASE_URL')).toBe(false);
    expect(isProtectedEnvVarKey('')).toBe(false);
  });
});
