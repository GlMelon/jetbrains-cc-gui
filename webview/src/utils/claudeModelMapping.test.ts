import { describe, expect, it, beforeEach, vi } from 'vitest';
import { STORAGE_KEYS } from '../types/provider';
import { resolveMappedModelName, writeClaudeModelMapping } from './claudeModelMapping';

describe('writeClaudeModelMapping', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('写入映射后应派发同 tab 刷新事件', () => {
    const listener = vi.fn();
    window.addEventListener('localStorageChange', listener as EventListener);

    writeClaudeModelMapping({ sonnet: 'glm-5' });

    expect(localStorage.getItem(STORAGE_KEYS.CLAUDE_MODEL_MAPPING)).toBe(
      JSON.stringify({ sonnet: 'glm-5' }),
    );
    expect(listener).toHaveBeenCalledTimes(1);

    const event = listener.mock.calls[0]?.[0] as CustomEvent<{ key: string }>;
    expect(event.detail.key).toBe(STORAGE_KEYS.CLAUDE_MODEL_MAPPING);

    window.removeEventListener('localStorageChange', listener as EventListener);
  });
});

// D5:resolveMappedModelName 是 ButtonArea / ModelSelect 共用的映射解析单一入口(纯函数)
describe('resolveMappedModelName', () => {
  it('role 命中时返回 mapping[role]', () => {
    expect(resolveMappedModelName('sonnet', { sonnet: 'glm-5', main: 'fallback' })).toBe('glm-5');
  });

  it('role 命中但值为空串时回退到 mapping.main', () => {
    expect(resolveMappedModelName('opus', { opus: '', main: 'glm-5' })).toBe('glm-5');
  });

  it('role 缺失键时回退到 mapping.main', () => {
    expect(resolveMappedModelName('haiku', { main: 'glm-5' })).toBe('glm-5');
  });

  it('role 缺失键且无 main 时返回 undefined', () => {
    expect(resolveMappedModelName('haiku', { sonnet: 'glm-5' })).toBeUndefined();
  });

  it('role 为 undefined(自定义/非内置模型)时仅取 main', () => {
    expect(resolveMappedModelName(undefined, { sonnet: 'glm-5', main: 'glm-5-main' })).toBe('glm-5-main');
    expect(resolveMappedModelName(undefined, { sonnet: 'glm-5' })).toBeUndefined();
  });

  it('对映射值做 trim', () => {
    expect(resolveMappedModelName('sonnet', { sonnet: '  glm-5  ' })).toBe('glm-5');
  });

  it('main 仅含空白时返回 undefined(不返回空串)', () => {
    expect(resolveMappedModelName('sonnet', { sonnet: '   ', main: '  ' })).toBeUndefined();
  });

  it('空映射返回 undefined', () => {
    expect(resolveMappedModelName('sonnet', {})).toBeUndefined();
    expect(resolveMappedModelName(undefined, {})).toBeUndefined();
  });
});
