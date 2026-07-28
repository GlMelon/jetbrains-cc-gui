// @ts-check
/**
 * 流式增量归一化:逐内容块区分"累积快照"与"增量 delta",吸收中途纠正重写。
 */

/**
 * 单个内容块的流式模式。一旦某块产出过确认的快照 delta 即锁定为 'snapshot';
 * 否则按增量 delta 累积拼接(Anthropic 标准)。
 * @typedef {'snapshot' | 'incremental'} StreamMode
 */

/**
 * 一次请求内随 delta 携带的逐块流式簿记(挂在共享 turnState 上)。
 * 内容 Map 以块索引为键;模式 Map 以 `${kind}:${blockIndex}` 为键。
 * @typedef {{
 *   textBlockContentByIndex?: Map<number, string>,
 *   thinkingBlockContentByIndex?: Map<number, string>,
 *   blockStreamModeByKey?: Map<string, StreamMode>,
 * }} BlockStateMaps
 */

/** @typedef {'textBlockContentByIndex' | 'thinkingBlockContentByIndex'} BlockMapKey */

/**
 * 取出(必要时懒初始化)指定 key 对应的块内容 Map。
 *
 * @param {BlockStateMaps} turnState 共享 turn 状态
 * @param {BlockMapKey} key         块 Map 字段名
 * @returns {Map<number, string>} 块内容 Map
 */
export function getBlockMap(turnState, key) {
  const existing = turnState[key];
  if (existing instanceof Map) {
    return existing;
  }
  const fresh = new Map();
  turnState[key] = fresh;
  return fresh;
}

/**
 * @param {number | string} index 原始块索引
 * @returns {number} 规范化后的非负整数索引(非法回退 0)
 */
function getBlockIndex(index) {
  const numericIndex = typeof index === 'string' ? Number(index) : index;
  return Number.isInteger(numericIndex) && numericIndex >= 0 ? numericIndex : 0;
}

/**
 * @param {BlockStateMaps} turnState 共享 turn 状态
 * @returns {Map<string, StreamMode>} 块流式模式 Map
 */
function getModeMap(turnState) {
  const existing = turnState.blockStreamModeByKey;
  if (existing instanceof Map) {
    return existing;
  }
  const fresh = new Map();
  turnState.blockStreamModeByKey = fresh;
  return fresh;
}

/**
 * @param {string} kind       块类型('text' / 'thinking')
 * @param {number} blockIndex 块索引
 * @returns {string} 模式 Map 的复合键
 */
function modeKey(kind, blockIndex) {
  return `${kind}:${blockIndex}`;
}

/**
 * 计算本次 delta 相对已累积内容的"新内容"。
 *
 * @param {string} previous 已累积内容
 * @param {string} incoming 本次到达内容
 * @param {StreamMode | undefined} mode 当前块已锁定的流式模式
 * @returns {{ novel: string, next: string, mode: StreamMode | undefined }}
 */
function computeNovelDelta(previous, incoming, mode) {
  if (!incoming) {
    return { novel: '', next: previous, mode };
  }
  if (!previous) {
    return { novel: incoming, next: incoming, mode };
  }

  // Cumulative-snapshot path: incoming is previous + new content. Confirms the
  // block is in snapshot mode for any subsequent corrective rewrites.
  if (incoming.startsWith(previous)) {
    return { novel: incoming.slice(previous.length), next: incoming, mode: 'snapshot' };
  }

  // Stale replay: incoming is fully contained at the start or end of previous.
  // Only active in cumulative-snapshot mode.  In incremental mode every delta is
  // by definition novel, and a coincidental suffix match (e.g. "0" arriving after
  // "150") would falsely absorb legitimate characters — producing the exact
  // character-shift bug seen with 1500 → 150.
  if (mode === 'snapshot' && (previous.startsWith(incoming) || previous.endsWith(incoming))) {
    return { novel: '', next: previous, mode };
  }

  // Fall-through: incoming neither extends nor is a stale replay of previous.
  //
  // For Anthropic-standard providers this is the regular incremental path —
  // each delta is just the next chunk and previous is the cumulative content.
  //
  // For Claude-compatible providers in cumulative-snapshot mode (mimo-v2.5-pro,
  // GLM, MiniMax, etc.) this branch fires when the model emits a "rewritten"
  // snapshot mid-stream: a typo correction, a token re-translation, or a
  // paraphrase.  The two strings share a long common prefix but diverge in the
  // middle, so neither startsWith nor endsWith matches.  Naively appending the
  // rewritten snapshot would visibly double every character before the
  // divergence point — the bug captured in image 1 of issue tracker
  // streaming-duplication-fix-2026-04-28.md.
  //
  // Mode tracking distinguishes the two: once a block has produced at least one
  // confirmed snapshot delta, we know the provider speaks cumulative-snapshot
  // for that block, and any later divergent payload must be a correction, not
  // an incremental fragment.  Absorb it silently and update bookkeeping so the
  // next genuine extension can still be diffed correctly.
  if (mode === 'snapshot') {
    return { novel: '', next: incoming, mode };
  }

  // Default: Anthropic-standard incremental delta.
  return { novel: incoming, next: previous + incoming, mode: 'incremental' };
}

/**
 * 归一化一条流式 delta,返回应下发给前端的新增内容。
 *
 * @param {BlockStateMaps} turnState   共享 turn 状态
 * @param {'text' | 'thinking'} kind   块类型
 * @param {number | string} index      块索引
 * @param {unknown} incoming           原始 delta 内容(非字符串视为空串)
 * @returns {string} 新增内容(可能为空串)
 */
export function normalizeStreamDelta(turnState, kind, index, incoming) {
  const text = typeof incoming === 'string' ? incoming : '';
  const key = kind === 'thinking' ? 'thinkingBlockContentByIndex' : 'textBlockContentByIndex';
  const blockMap = getBlockMap(turnState, key);
  const blockIndex = getBlockIndex(index);
  const previous = blockMap.get(blockIndex) || '';

  const modeMap = getModeMap(turnState);
  const mKey = modeKey(kind, blockIndex);
  const mode = modeMap.get(mKey);

  const result = computeNovelDelta(previous, text, mode);
  blockMap.set(blockIndex, result.next);
  if (result.mode && result.mode !== mode) {
    modeMap.set(mKey, result.mode);
  }
  return result.novel;
}

/**
 * Snapshot-path counterpart to {@link normalizeStreamDelta}.
 *
 * The final (or interim) assistant message carries the FULL text of each block.
 * Route that whole snapshot through the SAME novelty/correction engine the live
 * delta path uses, instead of a naive `snapshot.substring(previous.length)`.
 * A bare substring assumes the snapshot is always a prefix-extension of the
 * accumulated content; when a Claude-compatible provider emits a mid-stream
 * corrective rewrite (same prefix, divergent middle, equal-or-shorter length)
 * the substring either mis-slices or silently drops the change. computeNovelDelta
 * absorbs that case in snapshot mode and keeps the block map single-sourced.
 *
 * Returns the novel delta to emit plus `hadPrevious` (whether the block already
 * held streamed content before this snapshot) — the gate the tail-fill fix
 * depends on. IO and emit-gating stay with the caller so this stays pure.
 *
 * @param {BlockStateMaps} turnState   共享 turn 状态
 * @param {'text' | 'thinking'} kind   块类型
 * @param {number | string} index      块索引
 * @param {string} snapshot            整块快照文本
 * @returns {{ delta: string, hadPrevious: boolean }}
 */
export function resolveSnapshotDelta(turnState, kind, index, snapshot) {
  const key = kind === 'thinking' ? 'thinkingBlockContentByIndex' : 'textBlockContentByIndex';
  const blockMap = getBlockMap(turnState, key);
  const blockIndex = getBlockIndex(index);
  const hadPrevious = (blockMap.get(blockIndex) || '').length > 0;
  const delta = normalizeStreamDelta(turnState, kind, index, snapshot);
  return { delta, hadPrevious };
}

/**
 * Reset all per-block streaming bookkeeping at an assistant-turn boundary.
 *
 * The content maps and the mode map are keyed by block INDEX, but every
 * assistant turn — including each tool_use loop iteration — is its own message
 * whose content blocks re-number from index 0. The whole request shares one
 * turnState, so without clearing these at message_start the previous turn's
 * index-0 accumulator and its locked 'snapshot' mode leak into the next turn:
 * computeNovelDelta's snapshot-mode branch then absorbs the new turn's genuine
 * deltas (novel='' → fragmented / vanished output, e.g. the "constifprev"
 * garbled thinking) and a stale accumulator makes the tail-fill snapshot
 * re-emit a whole block (duplication).
 *
 * Token usage is intentionally NOT reset here — it accumulates across turns and
 * is owned by the caller's usage bookkeeping.
 *
 * @param {BlockStateMaps} turnState 共享 turn 状态
 * @returns {void}
 */
export function resetTurnBlockState(turnState) {
  turnState.textBlockContentByIndex = new Map();
  turnState.thinkingBlockContentByIndex = new Map();
  turnState.blockStreamModeByKey = new Map();
}
