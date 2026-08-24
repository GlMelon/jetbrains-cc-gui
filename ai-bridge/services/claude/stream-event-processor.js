// @ts-check
/**
 * Claude 流式事件处理:usage 累积、content/thinking delta 下发、快照尾部填充。
 */

import { emitAccumulatedUsage, mergeUsage } from '../../utils/usage-utils.js';
import { truncateErrorContent, truncateToolResultBlock } from './message-output-filter.js';
import { normalizeStreamDelta, resolveSnapshotDelta, resetTurnBlockState } from './stream-delta-normalizer.js';

/**
 * 单个内容块的流式模式(与 stream-delta-normalizer.js 保持结构一致)。
 * @typedef {'snapshot' | 'incremental'} StreamMode
 */

/**
 * 一次 assistant 请求共享的 turn 状态。内容/模式 Map 在 delta 路径上懒初始化。
 * @typedef {{
 *   streamingEnabled?: boolean,
 *   streamStarted?: boolean,
 *   streamEnded?: boolean,
 *   hasStreamEvents?: boolean,
 *   lastAssistantContent?: string,
 *   lastThinkingContent?: string,
 *   textBlockContentByIndex?: Map<number, string>,
 *   thinkingBlockContentByIndex?: Map<number, string>,
 *   blockStreamModeByKey?: Map<string, StreamMode>,
 *   finalSessionId?: string,
 *   accumulatedUsage?: any,
 * }} TurnState
 */

/**
 * Claude SDK 推送的原始消息(结构宽松,关键字段经 typeof/Array.isArray 守卫后使用)。
 * @typedef {{ type?: string, message?: any, content?: any, event?: any }} StreamMessage
 */

/**
 * @param {StreamMessage} msg
 * @returns {void}
 */
export function emitUsageTag(msg) {
  if (msg.type === 'assistant' && msg.message?.usage) {
    const {
      input_tokens = 0,
      output_tokens = 0,
      cache_creation_input_tokens = 0,
      cache_read_input_tokens = 0
    } = msg.message.usage;
    console.log('[USAGE]', JSON.stringify({
      input_tokens,
      output_tokens,
      cache_creation_input_tokens,
      cache_read_input_tokens
    }));
  }
}

/**
 * @param {{ streamingEnabled?: boolean, requestedSessionId?: string }} requestContext 请求上下文
 * @param {{ sessionId?: string } | null | undefined} runtime         运行时句柄
 * @returns {TurnState}
 */
export function createTurnState(requestContext, runtime) {
  return {
    streamingEnabled: requestContext.streamingEnabled,
    streamStarted: false,
    streamEnded: false,
    hasStreamEvents: false,
    // True once any non-result message has been processed this turn. A closing
    // SUCCESS result arriving with this still false is provably foreign (a real
    // run emits output before its result) and is skipped rather than ending the
    // turn empty — see the foreign-result skip in executeTurn (#1410).
    sawTurnMessage: false,
    lastAssistantContent: '',
    lastThinkingContent: '',
    textBlockContentByIndex: new Map(),
    thinkingBlockContentByIndex: new Map(),
    finalSessionId: requestContext.requestedSessionId || runtime?.sessionId || '',
    accumulatedUsage: null
  };
}

/**
 * 处理一条流式事件:turn 边界重置块状态、累积 usage、下发 content/thinking delta。
 *
 * @param {StreamMessage} msg       携带 event 字段的原始消息
 * @param {TurnState} turnState     共享 turn 状态
 * @returns {void}
 */
export function processStreamEvent(msg, turnState) {
  const event = msg.event;
  if (!event) return;

  if (event.type === 'message_start') {
    // Turn boundary: each assistant message (incl. every tool_use loop iteration)
    // re-numbers its content blocks from index 0. Clear the index-keyed block maps
    // so the prior turn's accumulator / locked stream-mode cannot corrupt or
    // duplicate this turn's index-0 block (see resetTurnBlockState). Usage still
    // accumulates across turns.
    resetTurnBlockState(turnState);
    // Emit BLOCK_RESET signal BEFORE any subsequent deltas to ensure frontend
    // clears its streaming refs (streamingThinkingRef, streamingContentRef).
    // This prevents new turn's thinking/text from merging with previous turn's content.
    // Must emit synchronously here, not in the delta handlers, to guarantee ordering.
    if (turnState.streamingEnabled) {
      process.stdout.write('[BLOCK_RESET]\n');
    }
    if (event.message?.usage) {
      turnState.accumulatedUsage = mergeUsage(turnState.accumulatedUsage, event.message.usage);
    }
  }

  if (event.type === 'message_delta' && event.usage) {
    turnState.accumulatedUsage = mergeUsage(turnState.accumulatedUsage, event.usage);
    emitAccumulatedUsage(turnState.accumulatedUsage);
  }

  if (event.type === 'content_block_delta' && event.delta) {
    if (event.delta.type === 'text_delta' && event.delta.text) {
      const delta = normalizeStreamDelta(turnState, 'text', event.index, event.delta.text);
      if (delta) {
        process.stdout.write(`[CONTENT_DELTA] ${JSON.stringify(delta)}\n`);
        turnState.lastAssistantContent += delta;
      }
    } else if (event.delta.type === 'thinking_delta' && event.delta.thinking) {
      const delta = normalizeStreamDelta(turnState, 'thinking', event.index, event.delta.thinking);
      if (delta) {
        process.stdout.write(`[THINKING_DELTA] ${JSON.stringify(delta)}\n`);
        turnState.lastThinkingContent += delta;
      }
    }
  }
}

/**
 * 处理 assistant 消息的整块快照内容:走与 live delta 相同的 novelty/纠正引擎。
 *
 * @param {StreamMessage} msg   原始消息
 * @param {TurnState} turnState 共享 turn 状态
 * @returns {void}
 */
export function processMessageContent(msg, turnState) {
  if (msg.type !== 'assistant') return;
  const content = msg.message?.content;

  if (Array.isArray(content)) {
    for (let i = 0; i < content.length; i += 1) {
      const block = content[i];
      if (block.type === 'text') {
        emitSnapshotText(block.text || '', turnState, i);
      } else if (block.type === 'thinking') {
        emitSnapshotThinking(block.thinking || block.text || '', turnState, i);
      }
    }
  } else if (typeof content === 'string') {
    emitSnapshotText(content, turnState, 0);
  }
}

/**
 * Emit a text block carried by an assistant snapshot.
 *
 * Routes the full snapshot through resolveSnapshotDelta — the same novelty/
 * correction engine the live delta path uses — so a mid-stream corrective
 * rewrite is absorbed rather than mis-sliced by a naive substring, and the
 * block map / mode bookkeeping stay single-sourced.
 *
 * Emit gate (unchanged from the tail-fill / new-block-suppression fix):
 *   - !hasStreamEvents: pre-stream fallback, emit the whole computed delta
 *   - hasStreamEvents && hadPrevious: genuine tail-fill / snapshot correction
 *   - hasStreamEvents && !hadPrevious: stream will deliver this block, suppress
 *
 * @param {string} currentText  整块快照文本
 * @param {TurnState} turnState 共享 turn 状态
 * @param {number} blockIndex   块索引
 * @returns {void}
 */
function emitSnapshotText(currentText, turnState, blockIndex) {
  if (!turnState.streamingEnabled) {
    console.log('[CONTENT]', truncateErrorContent(currentText));
    return;
  }
  const { delta, hadPrevious } = resolveSnapshotDelta(turnState, 'text', blockIndex, currentText);
  if (delta && (!turnState.hasStreamEvents || hadPrevious)) {
    process.stdout.write(`[CONTENT_DELTA] ${JSON.stringify(delta)}\n`);
  }
  turnState.lastAssistantContent = currentText;
}

/**
 * Thinking-block counterpart to {@link emitSnapshotText}.
 *
 * @param {string} thinkingText 整块思考快照文本
 * @param {TurnState} turnState 共享 turn 状态
 * @param {number} blockIndex   块索引
 * @returns {void}
 */
function emitSnapshotThinking(thinkingText, turnState, blockIndex) {
  if (!turnState.streamingEnabled) {
    console.log('[THINKING]', thinkingText);
    return;
  }
  const { delta, hadPrevious } = resolveSnapshotDelta(turnState, 'thinking', blockIndex, thinkingText);
  if (delta && (!turnState.hasStreamEvents || hadPrevious)) {
    process.stdout.write(`[THINKING_DELTA] ${JSON.stringify(delta)}\n`);
  }
  turnState.lastThinkingContent = thinkingText;
}

/**
 * 输出 user 消息中的 tool_result 块(经截断)。
 *
 * @param {StreamMessage} msg 原始消息
 * @returns {void}
 */
export function processToolResultMessages(msg) {
  if (msg.type !== 'user') return;
  const content = msg.message?.content ?? msg.content;
  if (!Array.isArray(content)) return;
  for (const block of content) {
    if (block.type === 'tool_result') {
      console.log('[TOOL_RESULT]', JSON.stringify(truncateToolResultBlock(block)));
    }
  }
}

/**
 * 判断是否应输出 [MESSAGE]。流式模式下仅当快照含 tool_use 块时输出,
 * 纯文本/思考内容由 [CONTENT_DELTA]/[THINKING_DELTA] 投递,避免去重器二次对账。
 *
 * @param {StreamMessage} msg   原始消息
 * @param {TurnState} turnState 共享 turn 状态
 * @returns {boolean}
 */
export function shouldOutputMessage(msg, turnState) {
  // Always output non-assistant messages
  if (msg.type !== 'assistant') {
    return true;
  }

  // Non-streaming mode: always output
  if (!turnState.streamingEnabled) {
    return true;
  }

  // Streaming mode: only emit [MESSAGE] when the snapshot carries tool_use blocks.
  // Pure text/thinking content is delivered via [CONTENT_DELTA] / [THINKING_DELTA]
  // (processStreamEvent for live deltas, processMessageContent for tail-fill).
  // Mirrors the legacy message-sender.js shouldOutput rule. Emitting redundant
  // [MESSAGE] for text-only assistants forces the Java ReplayDeduplicator to
  // reconcile the same content twice and was the upstream cause of duplicated
  // markdown blocks reported on v0.4.x streaming.
  const content = msg?.message?.content;
  if (!Array.isArray(content)) return false;
  return content.some((/** @type {any} */ block) => block?.type === 'tool_use');
}
