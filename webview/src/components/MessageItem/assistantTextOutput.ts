import type { ClaudeContentBlock, ClaudeMessage } from '../../types';

/**
 * Returns whether an assistant message contains user-visible answer text.
 * Structured messages use text blocks as the authority so thinking and tool
 * blocks do not prematurely switch the UI into the streaming-output state.
 */
export function hasAssistantTextOutput(
  message: ClaudeMessage,
  blocks: ClaudeContentBlock[],
): boolean {
  if (message.type !== 'assistant') return false;

  if (blocks.length > 0) {
    return blocks.some(
      (block) =>
        block.type === 'text' && typeof block.text === 'string' && block.text.trim().length > 0,
    );
  }

  return typeof message.content === 'string' && message.content.trim().length > 0;
}
