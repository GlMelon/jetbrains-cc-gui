import { describe, expect, it, vi } from 'vitest';
import type { ClaudeMessage } from '../../src/types';
import {
  MESSAGE_RETENTION_LIMIT,
  trimMessagesToRetentionLimit,
} from '../../src/utils/messageRetention';

function userMessage(content: string): ClaudeMessage {
  return { type: 'user', content, timestamp: '2026-01-01T00:00:00.000Z' } as ClaudeMessage;
}

function assistantMessage(content: string): ClaudeMessage {
  return { type: 'assistant', content, timestamp: '2026-01-01T00:00:00.000Z' } as ClaudeMessage;
}

describe('trimMessagesToRetentionLimit', () => {
  it('returns the same reference when under the limit', () => {
    const messages = [userMessage('a'), assistantMessage('b')];
    expect(trimMessagesToRetentionLimit(messages)).toBe(messages);
  });

  it('trims from the oldest end, aligned to a human user turn start', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    try {
      // 交替 user/assistant,共 LIMIT + 100 条 → 超限 100 条
      const messages: ClaudeMessage[] = [];
      for (let i = 0; i < MESSAGE_RETENTION_LIMIT + 100; i++) {
        messages.push(i % 2 === 0 ? userMessage(`u${i}`) : assistantMessage(`a${i}`));
      }

      const trimmed = trimMessagesToRetentionLimit(messages);

      expect(trimmed.length).toBeLessThanOrEqual(MESSAGE_RETENTION_LIMIT);
      // 裁点对齐到 turn 起点:保留区的第一条必须是人类用户消息
      expect(trimmed[0].type).toBe('user');
      // 最旧端被丢弃,最新消息全部保留
      expect(trimmed[trimmed.length - 1]).toBe(messages[messages.length - 1]);
      // 保留区是原数组的连续尾段
      expect(messages.slice(messages.length - trimmed.length)).toEqual(trimmed);
      expect(warnSpy).toHaveBeenCalledTimes(1);
    } finally {
      warnSpy.mockRestore();
    }
  });

  it('falls back to an unaligned cut when no turn start exists in the tail', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    try {
      // 整条 tail 都是 tool_result 载体(非人类用户消息)
      const messages: ClaudeMessage[] = [];
      for (let i = 0; i < MESSAGE_RETENTION_LIMIT + 10; i++) {
        messages.push(userMessage('[tool_result]'));
      }

      const trimmed = trimMessagesToRetentionLimit(messages);

      // 回退到未对齐裁点:保留最后 1800 条(TRIM_TARGET,不导出,按行为断言)
      expect(trimmed.length).toBe(1800);
      expect(trimmed[trimmed.length - 1]).toBe(messages[messages.length - 1]);
    } finally {
      warnSpy.mockRestore();
    }
  });
});
