import { describe, expect, it } from 'vitest';
import { MESSAGE_BLOCK_TOOL_STATUS } from '../generated/protocol';
import type { ToolResultBlock } from '../types';
import { isToolLifecycleTerminal } from './toolLifecycle';

const result = (toolStatus?: ToolResultBlock['tool_status']): ToolResultBlock => ({
  type: 'tool_result',
  content: 'done',
  tool_status: toolStatus,
});

describe('isToolLifecycleTerminal', () => {
  it('keeps an authoritative pending tool active even when a result mirror exists', () => {
    expect(isToolLifecycleTerminal(MESSAGE_BLOCK_TOOL_STATUS.PENDING, result())).toBe(false);
  });

  it('treats completed and unpaired tool uses as terminal without a result', () => {
    expect(isToolLifecycleTerminal(MESSAGE_BLOCK_TOOL_STATUS.COMPLETED, null)).toBe(true);
    expect(isToolLifecycleTerminal(MESSAGE_BLOCK_TOOL_STATUS.UNPAIRED, null)).toBe(true);
  });

  it('reads the backend lifecycle state from the result mirror when needed', () => {
    expect(isToolLifecycleTerminal(undefined, result(MESSAGE_BLOCK_TOOL_STATUS.PENDING))).toBe(false);
    expect(isToolLifecycleTerminal(undefined, result(MESSAGE_BLOCK_TOOL_STATUS.DUPLICATE))).toBe(true);
  });

  it('falls back to result presence for legacy history without lifecycle fields', () => {
    expect(isToolLifecycleTerminal(undefined, result())).toBe(true);
    expect(isToolLifecycleTerminal(undefined, null)).toBe(false);
  });
});
