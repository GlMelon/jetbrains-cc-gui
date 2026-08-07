import { describe, it, expect } from 'vitest';
import { PROVIDER_TYPE } from '../../src/generated/protocol';

/**
 * C2/C9 回归防护:ProviderType 值域 SSOT 对齐。
 *
 * 全集 6 值(claude/codex/opencode/grok/kimi/pi)= 后端 CommonConstants 常量 + ProviderType 枚举。
 * generated PROVIDER_TYPE 由后端 protocol.ProviderType(session.runtime 包)枚举构建时生成,
 * 替代前端原手写 PROVIDER_IDS / 'claude'|'codex' 联合(11+ 处消费点的第二真相源)。
 * opencode 随 §15 OpenCode 集成(第三 provider)加入;grok/kimi/pi 随 CLI 六 provider 对齐加入。
 * 本测试锁定构建链(Java 枚举 → manifest → mjs → protocol.ts)无漂移。
 */
describe('ProviderType SSOT (C2/C9)', () => {
  it('PROVIDER_TYPE 全集 = [claude, codex, opencode, grok, kimi, pi]', () => {
    expect(Object.values(PROVIDER_TYPE).sort()).toEqual(['claude', 'codex', 'grok', 'kimi', 'opencode', 'pi']);
  });

  it('每值对齐后端 CommonConstants / ProviderType 枚举', () => {
    expect(PROVIDER_TYPE.CLAUDE).toBe('claude');
    expect(PROVIDER_TYPE.CODEX).toBe('codex');
    expect(PROVIDER_TYPE.OPENCODE).toBe('opencode');
    expect(PROVIDER_TYPE.GROK).toBe('grok');
    expect(PROVIDER_TYPE.KIMI).toBe('kimi');
    expect(PROVIDER_TYPE.PI).toBe('pi');
  });
});
