import type { ClaudeMessage } from '../types';
import { isHumanUserMessage } from './messageUtils';

/**
 * 前端驻留消息硬上限(展示层资源管理,非业务规则)。
 *
 * 背景:messages 状态持有整会话(含 base64 图片),仅 tab 关闭 / webview 销毁释放;
 * 后端长会话只推 tail(StreamMessageCoalescer LONG_CONVERSATION_TAIL_SIZE = 180),
 * 但前端此前不裁剪已接收历史,超长会话内存随会话线性增长。
 *
 * 取值远高于后端 tail(180),给本地功能(rewind / 会话搜索 / 文件变更扫描)留足余量。
 * 后端无对应概念的下发机制,故为前端本地常量。
 */
export const MESSAGE_RETENTION_LIMIT = 2000;

/**
 * 触发裁剪时一次裁到的目标长度。留余量是为了避免超限后每追加一条消息就
 * slice 一次(O(n) + 日志刷屏);裁到目标值后,下一次裁剪间隔约 LIMIT - TARGET 条。
 */
const MESSAGE_RETENTION_TRIM_TARGET = 1800;

/**
 * 将消息数组裁到驻留上限内:从最旧端丢弃,并对齐到人类用户消息(turn 起点),
 * 避免把 tool_use / tool_result 链从中间截断。
 *
 * 未超限时原样返回同一引用(不击穿下游 memo)。裁剪只应在**非流式**时调用:
 * 流式期间 streamingMessageIndexRef 等持有数组下标,head-crop 会使其失配。
 */
export function trimMessagesToRetentionLimit(messages: ClaudeMessage[]): ClaudeMessage[] {
  if (messages.length <= MESSAGE_RETENTION_LIMIT) return messages;

  let cut = messages.length - MESSAGE_RETENTION_TRIM_TARGET;
  const alignedStart = cut;
  while (cut < messages.length && !isHumanUserMessage(messages[cut])) {
    cut += 1;
  }
  // 找不到 turn 起点(极端:整条 tail 都是一个 turn)时退回未对齐的裁点。
  if (cut >= messages.length) {
    cut = alignedStart;
  }

  console.warn(
    `[messages] Retention limit (${MESSAGE_RETENTION_LIMIT}) exceeded; ` +
    `dropped ${cut} oldest messages, keeping ${messages.length - cut}.`,
  );
  return messages.slice(cut);
}
