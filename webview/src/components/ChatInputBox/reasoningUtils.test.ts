import { describe, expect, it } from 'vitest';
import {
  getAvailableReasoningLevels,
  isReasoningVisible,
  resolveCurrentReasoningLevel,
} from './reasoningUtils';

describe('session-aware reasoning visibility', () => {
  it('hides reasoning when the current session explicitly lacks thinking', () => {
    expect(isReasoningVisible('grok', 'grok-4.6', false)).toBe(false);
  });

  it('preserves provider/model visibility while session capability is unknown', () => {
    expect(isReasoningVisible('grok', 'grok-4.6', undefined)).toBe(true);
    expect(isReasoningVisible('claude', undefined, undefined)).toBe(true);
  });

  it('corrects an effort that is not supported by the selected provider', () => {
    const available = getAvailableReasoningLevels('codex', 'gpt-5.6-sol');

    expect(available.some((level) => level.id === 'max')).toBe(false);
    expect(resolveCurrentReasoningLevel('max', available)?.id).not.toBe('max');
    expect(resolveCurrentReasoningLevel('max', available)?.id).toBe('high');
  });
});
