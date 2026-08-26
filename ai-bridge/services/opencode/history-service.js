// @ts-check
/**
 * Read OpenCode session history from the local SQLite database and convert it
 * to the webview's normalized message shape.
 */

import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
// @ts-expect-error — sql.js ships no bundled type declarations; runtime import is correct.
import initSqlJs from 'sql.js';
import { DatabaseSync } from 'node:sqlite';

/**
 * 前端消息块(text/thinking/tool_use 统一形状)。
 * @typedef {{ type: string; text?: string; thinking?: string; id?: string; name?: string; input?: any; [k: string]: any }} MessageBlock
 */

/**
 * 事件源组装的中间消息(供 eventMessageToFrontend 消费)。
 * @typedef {{ id: string; role: string; timestamp?: any; blocks: MessageBlock[]; info?: any }} EventMessage
 */

/**
 * getSessionMessages / getSessionList / archiveSession 的查询参数。
 * @typedef {{
 *   sessionId?: string;
 *   threadId?: string;
 *   dbPath?: string;
 *   maxMessageCount?: number;
 *   maxUtf8Bytes?: number;
 *   projectPath?: string;
 *   cwd?: string;
 * }} SessionQueryParams
 */

/** @typedef {{ maxMessageCount: number; maxUtf8Bytes: number }} ReadBudget */
/** @typedef {{ messages: any[]; totalMessageCount: number }} CollectedResult */

export function defaultDbPath() {
  // opencode 数据目录遵循 XDG 约定:优先 XDG_DATA_HOME,回退 ~/.local/share。
  // 与 OpenCodeConfigReader 读 ~/.config/opencode/opencode.json 同属 opencode 的 XDG 解析。
  const xdgData = process.env.XDG_DATA_HOME;
  const base = xdgData && xdgData.trim()
    ? xdgData
    : join(homedir(), '.local', 'share');
  return join(base, 'opencode', 'opencode.db');
}

// 镜像 Codex CodexHistoryReader.normalizePath:`\`→`/`、去尾斜杠,不大写化(与 Codex 一致)。
/** @param {string | null | undefined} path @returns {string} */
function normalizePath(path) {
  if (!path) return '';
  return String(path).replace(/\\/g, '/').replace(/\/+$/, '');
}

/** @param {any} result @returns {Record<string, any>[]} */
function rows(result) {
  if (!result || result.length === 0) return [];
  const { columns, values } = result[0];
  return values.map((/** @type {any[]} */ valueRow) => Object.fromEntries(
    columns.map((/** @type {string} */ column, /** @type {number} */ index) => [column, valueRow[index]])
  ));
}

/** @param {any} value @param {any} [fallback] @returns {any} */
function safeJson(value, fallback = {}) {
  if (!value || typeof value !== 'string') return fallback;
  try {
    return JSON.parse(value);
  } catch (_) {
    return fallback;
  }
}

/** @param {any} millis @returns {string | undefined} */
function isoTime(millis) {
  if (!Number.isFinite(Number(millis))) return undefined;
  return new Date(Number(millis)).toISOString();
}

/** @param {string} text @returns {string} */
function stripWrappingQuotes(text) {
  if (typeof text !== 'string' || text.length < 2) return text;
  // 问题4:OpenCode 对含换行的 prompt argv(IDE 拼接 ## Project Modules 等上下文)入库时,在
  // message.part.updated.1 事件源头的 part.text 就包了一对双引号(实测 part.text = "\"...\n...\""。
  // 短消息无换行不带引号,assistant 文本从不带引号)。这是 opencode 侧行为(我们只读 db 不可能引入),
  // 不可控;回显时剥掉这对配对的首尾双引号恢复原文。守卫"含 \n"避免误剥用户手输首尾引号的短消息。
  if (text.includes('\n') && text.charCodeAt(0) === 34 && text.charCodeAt(text.length - 1) === 34) {
    return text.slice(1, -1);
  }
  return text;
}

/** @param {string} text @returns {MessageBlock} */
function textBlock(text) {
  return { type: 'text', text: stripWrappingQuotes(text || '') };
}

/** @param {string} text @returns {MessageBlock} */
function thinkingBlock(text) {
  return { type: 'thinking', thinking: text || '', text: text || '' };
}

/** @param {any} part @returns {MessageBlock} */
function normalizeToolBlock(part) {
  const id = part.id || part.toolCallID || part.callID || part.call_id || 'opencode_tool';
  const name = part.tool || part.name || part.command || 'tool';
  return {
    type: 'tool_use',
    id,
    name,
    input: part.input || part.arguments || part.params || {},
  };
}

/** @param {any} part @returns {MessageBlock | null} */
function normalizePart(part) {
  if (!part || typeof part !== 'object') return null;
  switch (part.type) {
    case 'text':
      return part.text ? textBlock(part.text) : null;
    case 'reasoning':
      return part.text ? thinkingBlock(part.text) : null;
    case 'tool':
      return normalizeToolBlock(part);
    default:
      return null;
  }
}

/** @param {MessageBlock[]} blocks @returns {string} */
function contentText(blocks) {
  return blocks
    .filter((block) => block && block.type === 'text' && typeof block.text === 'string')
    .map((block) => block.text)
    .filter(Boolean)
    .join('\n');
}

/** @param {any} messageData @returns {{ input_tokens: number; output_tokens: number; cache_read_input_tokens: number; cache_creation_input_tokens: number } | undefined} */
function usageFromMessage(messageData) {
  const tokens = messageData?.tokens || messageData?.info?.tokens;
  if (!tokens || typeof tokens !== 'object') return undefined;
  return {
    input_tokens: Number(tokens.input || 0),
    output_tokens: Number(tokens.output || 0),
    cache_read_input_tokens: Number(tokens.cache?.read || 0),
    cache_creation_input_tokens: Number(tokens.cache?.write || 0),
  };
}

/**
 * @param {any} messageRow
 * @param {Iterable<any>} partRows
 * @returns {Record<string, any> | null}
 */
function toFrontendMessage(messageRow, partRows) {
  const messageData = safeJson(messageRow.data);
  const role = messageData.role === 'assistant' ? 'assistant' : 'user';
  /** @type {MessageBlock[]} */
  const blocks = [];
  for (const row of partRows) {
    const block = normalizePart(safeJson(row.data));
    if (block) {
      blocks.push(block);
    }
  }

  if (blocks.length === 0) return null;

  /** @type {Record<string, any>} */
  const raw = { role, content: blocks };
  const usage = usageFromMessage(messageData);
  if (usage) raw.usage = usage;

  /** @type {Record<string, any>} */
  const result = {
    type: role,
    content: contentText(blocks),
    raw,
  };
  const timestamp = isoTime(messageRow.time_created);
  if (timestamp) result.timestamp = timestamp;
  return result;
}

/**
 * @param {any} db
 * @param {string} tableName
 * @returns {boolean}
 */
function tableExists(db, tableName) {
  const found = rows(db.exec(
    "select name from sqlite_master where type = 'table' and name = ?",
    [tableName]
  ));
  return found.length > 0;
}

/** @param {SessionQueryParams} [params] @returns {ReadBudget} */
function readBudget(params = {}) {
  const mc = params.maxMessageCount;
  const maxMessageCount = typeof mc === 'number' && Number.isInteger(mc) && mc >= 0
    ? mc
    : Number.POSITIVE_INFINITY;
  const mb = params.maxUtf8Bytes;
  const maxUtf8Bytes = typeof mb === 'number' && Number.isInteger(mb) && mb >= 0
    ? mb
    : Number.POSITIVE_INFINITY;
  return { maxMessageCount, maxUtf8Bytes };
}

/**
 * @param {number} totalMessageCount
 * @param {ReadBudget} budget
 * @returns {{ retentionClosed: boolean; append(message: any): void; result(): CollectedResult }}
 */
function createBoundedMessageCollector(totalMessageCount, budget) {
  /** @type {any[]} */
  const messages = [];
  let retainedUtf8Bytes = 0;
  let retentionClosed = budget.maxMessageCount === 0 || budget.maxUtf8Bytes === 0;

  return {
    get retentionClosed() {
      return retentionClosed;
    },
    append(/** @type {any} */ message) {
      if (retentionClosed || messages.length >= budget.maxMessageCount) {
        retentionClosed = true;
        return;
      }
      const serializedBytes = Buffer.byteLength(JSON.stringify(message), 'utf8');
      const separatorBytes = messages.length === 0 ? 0 : 1;
      if (retainedUtf8Bytes + separatorBytes + serializedBytes > budget.maxUtf8Bytes) {
        retentionClosed = true;
        return;
      }
      messages.push(message);
      retainedUtf8Bytes += separatorBytes + serializedBytes;
      if (messages.length >= budget.maxMessageCount) {
        retentionClosed = true;
      }
    },
    result() {
      return { messages, totalMessageCount };
    },
  };
}

/**
 * @param {any} db
 * @param {string} sessionId
 * @returns {number}
 */
function countMaterializedMessages(db, sessionId) {
  const row = db.prepare(`
    select count(distinct message.id) as count
    from message
    join part on part.message_id = message.id
    where message.session_id = ?
      and part.session_id = ?
      and (
        json_extract(part.data, '$.type') = 'tool'
        or (
          json_extract(part.data, '$.type') in ('text', 'reasoning')
          and coalesce(json_extract(part.data, '$.text'), '') <> ''
        )
      )
  `).get(sessionId, sessionId);
  return Number(row?.count || 0);
}

/**
 * @param {any} db
 * @param {any} messageRow
 * @returns {Record<string, any> | null}
 */
function readMaterializedMessage(db, messageRow) {
  const partRows = db.prepare(
    'select id, message_id, session_id, time_created, data from part where message_id = ? order by time_created, id'
  ).iterate(messageRow.id);
  return toFrontendMessage(messageRow, partRows);
}

/**
 * @param {any} db
 * @param {string} sessionId
 * @param {ReadBudget} budget
 * @returns {CollectedResult}
 */
function readMaterializedMessages(db, sessionId, budget) {
  if (!nodeTableExists(db, 'message') || !nodeTableExists(db, 'part')) {
    return { messages: [], totalMessageCount: 0 };
  }

  const totalMessageCount = countMaterializedMessages(db, sessionId);
  const collector = createBoundedMessageCollector(totalMessageCount, budget);
  if (collector.retentionClosed || totalMessageCount === 0) {
    return collector.result();
  }

  const messageRows = db.prepare(
    'select id, session_id, time_created, data from message where session_id = ? order by time_created, id'
  ).iterate(sessionId);
  for (const messageRow of messageRows) {
    const message = readMaterializedMessage(db, messageRow);
    if (message) {
      collector.append(message);
      if (collector.retentionClosed) {
        break;
      }
    }
  }
  return collector.result();
}

/** @param {any} row @returns {any} */
function eventPayload(row) {
  return safeJson(row.data);
}

/** @param {any} payload @param {any} fallback @returns {any} */
function eventTimestamp(payload, fallback) {
  return payload?.time || payload?.info?.time?.created || fallback;
}

/** @param {any} type @returns {string} */
function eventTypeBase(type) {
  return String(type || '').replace(/\.\d+$/, '');
}

/** @param {any} part @returns {string} */
function toMessageId(part) {
  return part?.messageID || part?.messageId || part?.message_id || '';
}

/**
 * @param {any} info
 * @param {number} rowSeq
 * @returns {EventMessage}
 */
function messageFromInfo(info, rowSeq) {
  const id = info?.id || `opencode_message_${rowSeq}`;
  const role = info?.role === 'assistant' ? 'assistant' : 'user';
  return {
    id,
    role,
    timestamp: info?.time?.created,
    blocks: [],
    info,
  };
}

/**
 * @param {Map<string, EventMessage>} messagesById
 * @param {EventMessage[]} orderedMessages
 * @param {any} info
 * @param {number} rowSeq
 * @returns {EventMessage}
 */
function upsertMessage(messagesById, orderedMessages, info, rowSeq) {
  const id = info?.id || `opencode_message_${rowSeq}`;
  let message = messagesById.get(id);
  if (!message) {
    message = messageFromInfo(info, rowSeq);
    messagesById.set(id, message);
    orderedMessages.push(message);
    return message;
  }
  if (info?.role) {
    message.role = info.role === 'assistant' ? 'assistant' : 'user';
  }
  if (info?.time?.created) {
    message.timestamp = info.time.created;
  }
  message.info = { ...(message.info || {}), ...(info || {}) };
  return message;
}

/** @param {EventMessage} message @returns {Record<string, any> | null} */
function eventMessageToFrontend(message) {
  const blocks = message.blocks.filter(Boolean);
  if (blocks.length === 0) return null;

  /** @type {Record<string, any>} */
  const raw = { role: message.role, content: blocks };
  const usage = usageFromMessage(message.info);
  if (usage) raw.usage = usage;

  /** @type {Record<string, any>} */
  const result = {
    type: message.role,
    content: contentText(blocks),
    raw,
  };
  const timestamp = isoTime(message.timestamp);
  if (timestamp) result.timestamp = timestamp;
  return result;
}

function eventMessageIdSql() {
  return `coalesce(
    json_extract(data, '$.info.id'),
    json_extract(data, '$.part.messageID'),
    json_extract(data, '$.part.messageId'),
    json_extract(data, '$.part.message_id')
  )`;
}

function validEventPartSql() {
  return `(
    json_extract(data, '$.part.type') = 'tool'
    or (
      json_extract(data, '$.part.type') in ('text', 'reasoning')
      and coalesce(json_extract(data, '$.part.text'), '') <> ''
    )
  )`;
}

function eventMessageRowsSql() {
  const messageId = eventMessageIdSql();
  const validPart = validEventPartSql();
  return `
    select ${messageId} as message_id, min(seq) as first_seq
    from event
    where aggregate_id = ?
      and ${messageId} is not null
    group by ${messageId}
    having max(case when ${validPart} then 1 else 0 end) = 1
    order by first_seq
  `;
}

/** @param {any} db @returns {any} */
function eventMessageRows(db) {
  return db.prepare(eventMessageRowsSql());
}

/**
 * @param {any} db
 * @param {string} sessionId
 * @param {string} messageId
 * @returns {Record<string, any> | null}
 */
function readEventMessage(db, sessionId, messageId) {
  const rowMessageId = eventMessageIdSql();
  const rows = db.prepare(`
    select seq, type, data
    from event
    where aggregate_id = ? and ${rowMessageId} = ?
    order by seq
  `).iterate(sessionId, messageId);

  /** @type {EventMessage | null} */
  let message = null;
  for (const row of rows) {
    const payload = eventPayload(row);
    const type = eventTypeBase(row.type);
    if (payload?.sessionID && payload.sessionID !== sessionId) {
      continue;
    }
    if (type === 'message.updated') {
      const info = payload.info || {};
      if (!info.id) {
        continue;
      }
      if (!message) {
        message = messageFromInfo(info, row.seq);
      } else {
        if (info.role) {
          message.role = info.role === 'assistant' ? 'assistant' : 'user';
        }
        if (info.time?.created) {
          message.timestamp = info.time.created;
        }
        message.info = { ...(message.info || {}), ...info };
      }
      continue;
    }
    if (type !== 'message.part.updated') {
      continue;
    }
    const part = payload.part || {};
    if (!message) {
      message = messageFromInfo({
        id: messageId,
        role: part.role,
        time: { created: eventTimestamp(payload, row.seq) },
      }, row.seq);
    }
    const block = normalizePart(part);
    if (block) {
      message.blocks.push(block);
    }
    if (!message.timestamp) {
      message.timestamp = eventTimestamp(payload, row.seq);
    }
  }
  return message ? eventMessageToFrontend(message) : null;
}

/**
 * @param {any} db
 * @param {string} sessionId
 * @param {ReadBudget} budget
 * @returns {CollectedResult}
 */
function readEventMessages(db, sessionId, budget) {
  if (!nodeTableExists(db, 'event')) {
    return { messages: [], totalMessageCount: 0 };
  }

  const messageRows = eventMessageRows(db);
  const countRow = db.prepare(`select count(*) as count from (${eventMessageRowsSql()})`).get(sessionId);
  const totalMessageCount = Number(countRow?.count || 0);
  const collector = createBoundedMessageCollector(totalMessageCount, budget);
  if (collector.retentionClosed || totalMessageCount === 0) {
    return collector.result();
  }

  for (const row of messageRows.iterate(sessionId)) {
    const message = readEventMessage(db, sessionId, row.message_id);
    if (message) {
      collector.append(message);
      if (collector.retentionClosed) {
        break;
      }
    }
  }
  return collector.result();
}

/**
 * @param {SessionQueryParams} [params]
 * @returns {Promise<any>}
 */
export async function getSessionMessages(params = {}) {
  const sessionId = params.sessionId || params.threadId || '';
  const dbPath = params.dbPath || defaultDbPath();
  if (!sessionId) {
    return { success: false, error: 'Missing sessionId' };
  }
  if (!existsSync(dbPath)) {
    return { success: false, error: `OpenCode database not found: ${dbPath}` };
  }

  // §回显空白根因:改用 node:sqlite(readOnly + WAL 感知)替代 sql.js,对称 getSessionList §问题2。
  // sql.js(WASM)从 readFileSync 的主文件 buffer 加载、对 -wal 边车文件视而不见;OpenCode 用 WAL
  // 模式,新会话消息先进 -wal,checkpoint 前主文件没有,sql.js 读不到 → 点击历史记录回显空白
  // (仅 OpenCode 复现,Claude/Codex 是 JSONL 文件无此问题)。node:sqlite 按路径打开,WAL 自动恢复,
  // 确定性读到。readMaterializedMessages/readEventMessages 配套改用 prepare().all()(node:sqlite
  // 返回对象数组,无需 sql.js 的 rows(db.exec()) 转换)。
  /** @type {any} */
  let db;
  try {
    db = new DatabaseSync(dbPath, { readOnly: true });
  } catch (e) {
    return { success: false, error: `Failed to open OpenCode database: ${e instanceof Error ? e.message : String(e)}` };
  }
  try {
    const budget = readBudget(params);
    let result = readMaterializedMessages(db, sessionId, budget);
    if (result.totalMessageCount === 0) {
      result = readEventMessages(db, sessionId, budget);
    }

    return {
      success: true,
      messages: result.messages,
      totalMessageCount: result.totalMessageCount,
    };
  } finally {
    db.close();
  }
}

// 问题1:OpenCode AI 标题生成偶发不触发(实测 3/11 会话 title 从创建起始终是默认时间戳,
// 从未发生 AI 标题更新事件)。生成逻辑在 OpenCode 内部,插件层无法直接修。故 getSessionList
// 在序列化 JSON 给前端时兜底:title 命中默认时间戳模式(或空)→ 从首条 user 消息派生;
// 有真实 AI 标题则忽略。**不动 db**,只在输出 JSON 的 title 字段替换。
const DEFAULT_TITLE_PATTERN = /^New session - .+Z$/;
const FALLBACK_TITLE_MAX = 40;
const FALLBACK_TITLE_EMPTY = '(未命名)';

/**
 * 判断 title 是否需要兜底:命中 OpenCode 默认时间戳模式,或为 null/空白。
 * 纯函数,便于单测。
 */
/** @param {string | null | undefined} title @returns {boolean} */
export function isDefaultSessionTitle(title) {
  if (title == null) return true;
  const trimmed = String(title).trim();
  if (trimmed === '') return true;
  return DEFAULT_TITLE_PATTERN.test(trimmed);
}

/**
 * 从首条 user 消息文本派生兜底标题:剥 IDE 拼接上下文(所有 marker 均以 "\n\n##" 开头,
 * 与问题3 同源)→ 取首行 → 合并空白 → 截断(超长加省略号)。空文本返回 null(调用方回退
 * 到通用 FALLBACK_TITLE_EMPTY)。纯函数,便于单测。
 */
/** @param {string | null | undefined} firstUserText @returns {string | null} */
export function deriveFallbackTitle(firstUserText) {
  if (!firstUserText || typeof firstUserText !== 'string') return null;
  // 剥 IDE 拼接上下文:用户真实正文在前,marker 在后;取首行 + 合并内部空白
  const firstLine = firstUserText
    .split(/\n\n##/)[0]
    .split('\n')[0]
    .replace(/\s+/g, ' ')
    .trim();
  if (!firstLine) return null;
  if (firstLine.length > FALLBACK_TITLE_MAX) {
    return firstLine.slice(0, FALLBACK_TITLE_MAX) + '…';
  }
  return firstLine;
}

// §问题2:getSessionList 改用 node:sqlite(WAL 感知)读取。node:sqlite API 与 sql.js 不同
// (prepare/all 返回对象数组,无 db.exec 返回 {columns,values}),故为 getSessionList 配套的
// table 存在检查与首条 user 文本查询都用 node:sqlite 版 helper。getSessionMessages/archiveSession
// 仍走 sql.js(分别处理消息回放与写回持久化,用户未报问题)。
/**
 * @param {any} db
 * @param {string} tableName
 * @returns {boolean}
 */
function nodeTableExists(db, tableName) {
  const row = db.prepare(
    "select name from sqlite_master where type = 'table' and name = ?"
  ).get(tableName);
  return !!row;
}

/**
 * 查询某会话首条 user 消息的首个 text part 文本(用于兜底标题派生,问题1)。
 * 不依赖 SQLite JSON1(json_extract),在 JS 侧解析 data JSON。无 message/part 表或无 user text → null。
 *
 * @param {any} db
 * @param {string} sessionId
 * @returns {string | null}
 */
function nodeQueryFirstUserText(db, sessionId) {
  if (!nodeTableExists(db, 'message') || !nodeTableExists(db, 'part')) return null;
  const msgs = db.prepare(
    'select id, time_created, data from message where session_id = ? order by time_created, id'
  ).all(sessionId);
  const firstUser = msgs.find((/** @type {any} */ m) => safeJson(m.data).role === 'user');
  if (!firstUser) return null;
  const parts = db.prepare(
    'select data from part where message_id = ? order by time_created, id'
  ).all(firstUser.id);
  for (const p of parts) {
    const partData = safeJson(p.data);
    if (partData.type === 'text' && partData.text) return partData.text;
  }
  return null;
}

/**
 * @param {SessionQueryParams} [params]
 * @returns {Promise<any>}
 */
export async function getSessionList(params = {}) {
  // 枚举 OpenCode 会话,字段对齐 Codex SessionInfo。
  const projectPath = params.projectPath || params.cwd || '';
  const dbPath = params.dbPath || defaultDbPath();
  if (!existsSync(dbPath)) {
    return { success: false, error: `OpenCode database not found: ${dbPath}` };
  }

  // §问题2:改用 node:sqlite(readOnly + WAL 感知)替代 sql.js。sql.js(WASM)从主文件 buffer
  // 加载、对 -wal 边车文件视而不见;OpenCode 用 WAL 模式,新会话先落 -wal,checkpoint 前主文件没有,
  // sql.js 读不到 → "上一个会话过会儿才出现"。node:sqlite 按路径打开,WAL 自动恢复,确定性读到。
  /** @type {any} */
  let db;
  try {
    db = new DatabaseSync(dbPath, { readOnly: true });
  } catch (e) {
    return { success: false, error: `Failed to open OpenCode database: ${e instanceof Error ? e.message : String(e)}` };
  }
  try {
    if (!nodeTableExists(db, 'session')) {
      // 优雅降级:旧 schema 无 session 表
      return { success: true, sessions: [], sessionCount: 0 };
    }
    const countExpr = nodeTableExists(db, 'message')
      ? '(select count(*) from message where message.session_id = session.id)'
      : '0';
    /** @type {any[]} */
    const result = db.prepare(
      `select id, title, directory, time_created, time_updated, ${countExpr} as msg_count
       from session
       where time_archived is null
       order by time_updated desc`
    ).all();
    /** @type {any[]} */
    let sessions = result.map((/** @type {any} */ row) => ({
      sessionId: row.id,
      // 保留原始 title(空保留空),问题1 兜底在下面统一处理(避免 '(未命名)' 提前替换漏判空标题)
      title: row.title || '',
      messageCount: Number(row.msg_count || 0),
      firstTimestamp: Number(row.time_created || 0),
      lastTimestamp: Number(row.time_updated || 0),
      cwd: row.directory || '',
    }));

    // 问题1兜底:默认时间戳标题(AI 标题生成未触发)或空标题 → 从首条 user 消息派生;
    // 派生不出(无消息/无 text part)则回退到通用 (未命名)。不动 db,仅替换输出 JSON 的 title。
    for (const session of sessions) {
      if (!isDefaultSessionTitle(session.title)) continue;
      const derived = deriveFallbackTitle(nodeQueryFirstUserText(db, session.sessionId));
      session.title = derived || FALLBACK_TITLE_EMPTY;
    }

    // 项目过滤(镜像 Codex normalizePath + equals/startsWith,仅在传入 projectPath 时启用)
    if (projectPath) {
      const normalizedProject = normalizePath(projectPath);
      sessions = sessions.filter((session) => {
        const normalizedDir = normalizePath(session.cwd);
        return normalizedDir === normalizedProject
          || normalizedDir.startsWith(normalizedProject + '/');
      });
    }

    return { success: true, sessions, sessionCount: sessions.length };
  } finally {
    db.close();
  }
}

/**
 * 软删除(归档)OpenCode 会话:置 time_archived,getSessionList 已用
 * `where time_archived is null` 过滤,归档后 reload 自动不再出现。
 * 幂等:where 额外加 `time_archived is null`,已归档的会话再归档不计入受影响行数。
 *
 * @returns {Promise<{success: boolean, archived?: number, error?: string}>}
 *          archived = 实际新归档的行数(db.getRowsModified)
 */
/**
 * @param {SessionQueryParams} [params]
 * @returns {Promise<any>}
 */
export async function archiveSession(params = {}) {
  const sessionId = params.sessionId || params.threadId || '';
  const dbPath = params.dbPath || defaultDbPath();
  if (!sessionId) {
    return { success: false, error: 'Missing sessionId' };
  }
  if (!existsSync(dbPath)) {
    return { success: false, error: `OpenCode database not found: ${dbPath}` };
  }

  const SQL = await initSqlJs();
  const db = new SQL.Database(readFileSync(dbPath));
  try {
    if (!tableExists(db, 'session')) {
      // 优雅降级:旧 schema 无 session 表
      return { success: true, archived: 0 };
    }
    db.run(
      'update session set time_archived = ? where id = ? and time_archived is null',
      [Date.now(), sessionId]
    );
    const archived = db.getRowsModified();
    // sql.js 是内存数据库,写操作仅落在内存 db 对象上,必须显式 export 回文件才算持久化
    // (只读的 getSessionList/getSessionMessages 无需此步)。幂等:已归档行 archived=0,export 仍写出(内容无变化)。
    try {
      writeFileSync(dbPath, Buffer.from(db.export()));
    } catch (writeError) {
      // 写入失败时提供详细错误信息，但数据库已正确关闭
      return { 
        success: false, 
        error: `Failed to write database: ${writeError instanceof Error ? writeError.message : String(writeError)}`,
        archived 
      };
    }
    return { success: true, archived };
  } finally {
    db.close();
  }
}
