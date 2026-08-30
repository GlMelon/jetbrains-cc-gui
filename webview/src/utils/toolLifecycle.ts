import {
  MESSAGE_BLOCK_TOOL_STATUS,
  type MessageBlockToolStatus,
} from '../generated/protocol';
import type { ToolResultBlock } from '../types';

/**
 * Maps the backend-owned tool lifecycle state to the terminal/non-terminal UI
 * distinction. Result presence is only a compatibility fallback for history
 * produced before the lifecycle contract was introduced.
 */
export function isToolLifecycleTerminal(
  status: MessageBlockToolStatus | undefined,
  result: ToolResultBlock | null | undefined,
): boolean {
  const authoritativeStatus = status ?? result?.tool_status;
  if (authoritativeStatus === MESSAGE_BLOCK_TOOL_STATUS.PENDING) {
    return false;
  }
  if (authoritativeStatus !== undefined) {
    return true;
  }
  return result !== undefined && result !== null;
}
