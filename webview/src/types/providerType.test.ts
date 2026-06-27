import { describe, it, expect } from 'vitest';
import { PROVIDER_TYPE } from '../generated/protocol';

/**
 * C2/C9 回归防护:ProviderType 值域 SSOT 对齐。
 *
 * 全集 3 值(claude/codex/opencode)= 后端 CommonConstants.PROVIDER_CLAUDE/PROVIDER_CODEX/PROVIDER_OPENCODE。
 * generated PROVIDER_TYPE 由后端 protocol.ProviderType(session.runtime 包)枚举构建时生成,
 * 替代前端原手写 PROVIDER_IDS / 'claude'|'codex' 联合(11+ 处消费点的第二真相源)。
 * opencode 随 §15 OpenCode 集成(第三 provider)加入,后端 ProviderType enum 含 OPencode 多参形态。
 * 本测试锁定构建链(Java 枚举 → manifest → mjs → protocol.ts)无漂移。
 */
describe('ProviderType SSOT (C2/C9)', () => {
  it('PROVIDER_TYPE 全集 = [claude, codex, opencode]', () => {
    expect(Object.values(PROVIDER_TYPE).sort()).toEqual(['claude', 'codex', 'opencode']);
  });

  it('每值对齐后端 CommonConstants.PROVIDER_CLAUDE/PROVIDER_CODEX/PROVIDER_OPENCODE', () => {
    expect(PROVIDER_TYPE.CLAUDE).toBe('claude');
    expect(PROVIDER_TYPE.CODEX).toBe('codex');
    expect(PROVIDER_TYPE.OPENCODE).toBe('opencode');
  });
});
