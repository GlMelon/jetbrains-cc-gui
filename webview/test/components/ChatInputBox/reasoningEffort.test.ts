import { describe, it, expect } from 'vitest';
import { REASONING_EFFORT } from '../../../src/generated/protocol';

/**
 * C2 回归防护:ReasoningEffort 值域 SSOT 对齐。
 *
 * 全集 7 档(none/minimal/low/medium/high/xhigh/max = 各 provider CLI 词表并集);
 * 实际展示为按 provider/model 子集过滤(后端 ReasoningCapabilities / ClaudeRole 派生下发)。
 * generated REASONING_EFFORT 由后端 protocol.ReasoningEffort 枚举构建时生成,
 * 替代前端原手写 REASONING_VALUES(useModelStatePersistence.ts,与 ClaudeRole.java:127 重复
 * 的第二真相源)。本测试锁定构建链(Java 枚举 → manifest → mjs → protocol.ts)无漂移。
 */
describe('ReasoningEffort SSOT (C2)', () => {
  it('REASONING_EFFORT 全集 7 档(各 provider 词表并集)', () => {
    expect(Object.values(REASONING_EFFORT).sort()).toEqual([
      'high', 'low', 'max', 'medium', 'minimal', 'none', 'xhigh',
    ]);
  });

  it('每档值 = 协议 reasoning effort 字面量', () => {
    expect(REASONING_EFFORT.NONE).toBe('none');
    expect(REASONING_EFFORT.MINIMAL).toBe('minimal');
    expect(REASONING_EFFORT.LOW).toBe('low');
    expect(REASONING_EFFORT.MEDIUM).toBe('medium');
    expect(REASONING_EFFORT.HIGH).toBe('high'); // 默认值,对齐 CommonConstants.DEFAULT_REASONING_EFFORT(C3)
    expect(REASONING_EFFORT.XHIGH).toBe('xhigh');
    expect(REASONING_EFFORT.MAX).toBe('max');
  });
});
