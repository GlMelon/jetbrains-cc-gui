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

/**
 * Returns whether an assistant message has produced any renderable output
 * (thinking, tool_use/MCP, or text). The streaming footer follows this so the
 * "responding" indicator appears with the FIRST block of a turn — a thinking
 * block or a tool call — instead of waiting for answer text to land.
 */
export function hasAssistantVisibleOutput(
  message: ClaudeMessage,
  blocks: ClaudeContentBlock[],
): boolean {
  if (message.type !== 'assistant') return false;
  if (blocks.length > 0) return true;
  return hasAssistantTextOutput(message, blocks);
}
