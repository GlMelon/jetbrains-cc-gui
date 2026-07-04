/**
 * Read OpenCode session history from the local SQLite database and convert it
 * to the webview's normalized message shape.
 */

import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import initSqlJs from 'sql.js';
import { DatabaseSync } from 'node:sqlite';

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
function normalizePath(path) {
  if (!path) return '';
  return String(path).replace(/\\/g, '/').replace(/\/+$/, '');
}

function rows(result) {
  if (!result || result.length === 0) return [];
  const { columns, values } = result[0];
  return values.map((valueRow) => Object.fromEntries(
    columns.map((column, index) => [column, valueRow[index]])
  ));
}

function safeJson(value, fallback = {}) {
  if (!value || typeof value !== 'string') return fallback;
  try {
    return JSON.parse(value);
  } catch (_) {
    return fallback;
  }
}

function isoTime(millis) {
  if (!Number.isFinite(Number(millis))) return undefined;
  return new Date(Number(millis)).toISOString();
}

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

function textBlock(text) {
  return { type: 'text', text: stripWrappingQuotes(text || '') };
}

function thinkingBlock(text) {
  return { type: 'thinking', thinking: text || '', text: text || '' };
}

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

function contentText(blocks) {
  return blocks
    .filter((block) => block && block.type === 'text' && typeof block.text === 'string')
    .map((block) => block.text)
    .filter(Boolean)
    .join('\n');
}

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

function toFrontendMessage(messageRow, partRows) {
  const messageData = safeJson(messageRow.data);
  const role = messageData.role === 'assistant' ? 'assistant' : 'user';
  const blocks = partRows
    .map((row) => normalizePart(safeJson(row.data)))
    .filter(Boolean);

  if (blocks.length === 0) return null;

  const raw = { role, content: blocks };
  const usage = usageFromMessage(messageData);
  if (usage) raw.usage = usage;

  const result = {
    type: role,
    content: contentText(blocks),
    raw,
  };
  const timestamp = isoTime(messageRow.time_created);
  if (timestamp) result.timestamp = timestamp;
  return result;
}

function tableExists(db, tableName) {
  const found = rows(db.exec(
    "select name from sqlite_master where type = 'table' and name = ?",
    [tableName]
  ));
  return found.length > 0;
}

function readMaterializedMessages(db, sessionId) {
  if (!nodeTableExists(db, 'message') || !nodeTableExists(db, 'part')) {
    return [];
  }
  const messages = db.prepare(
    'select id, session_id, time_created, data from message where session_id = ? order by time_created, id'
  ).all(sessionId);
  const parts = db.prepare(
    'select id, message_id, session_id, time_created, data from part where session_id = ? order by time_created, id'
  ).all(sessionId);
  const partsByMessage = new Map();
  for (const part of parts) {
    const bucket = partsByMessage.get(part.message_id) || [];
    bucket.push(part);
    partsByMessage.set(part.message_id, bucket);
  }

  return messages
    .map((message) => toFrontendMessage(message, partsByMessage.get(message.id) || []))
    .filter(Boolean);
}

function eventPayload(row) {
  return safeJson(row.data);
}

function eventTimestamp(payload, fallback) {
  return payload?.time || payload?.info?.time?.created || fallback;
}

function eventTypeBase(type) {
  return String(type || '').replace(/\.\d+$/, '');
}

function toMessageId(part) {
  return part?.messageID || part?.messageId || part?.message_id || '';
}

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

function eventMessageToFrontend(message) {
  const blocks = message.blocks.filter(Boolean);
  if (blocks.length === 0) return null;

  const raw = { role: message.role, content: blocks };
  const usage = usageFromMessage(message.info);
  if (usage) raw.usage = usage;

  const result = {
    type: message.role,
    content: contentText(blocks),
    raw,
  };
  const timestamp = isoTime(message.timestamp);
  if (timestamp) result.timestamp = timestamp;
  return result;
}

function readEventMessages(db, sessionId) {
  if (!nodeTableExists(db, 'event')) {
    return [];
  }
  const events = db.prepare(
    'select seq, type, data from event where aggregate_id = ? order by seq'
  ).all(sessionId);
  const messagesById = new Map();
  const orderedMessages = [];

  for (const row of events) {
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
      upsertMessage(messagesById, orderedMessages, info, row.seq);
      continue;
    }

    if (type === 'message.part.updated') {
      const part = payload.part || {};
      const messageId = toMessageId(part);
      if (!messageId) {
        continue;
      }
      let message = messagesById.get(messageId);
      if (!message) {
        message = upsertMessage(messagesById, orderedMessages, {
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
  }

  return orderedMessages
    .map(eventMessageToFrontend)
    .filter(Boolean);
}

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
  let db;
  try {
    db = new DatabaseSync(dbPath, { readOnly: true });
  } catch (e) {
    return { success: false, error: `Failed to open OpenCode database: ${e.message}` };
  }
  try {
    let messages = readMaterializedMessages(db, sessionId);
    if (messages.length === 0) {
      messages = readEventMessages(db, sessionId);
    }

    return {
      success: true,
      messages,
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
function nodeTableExists(db, tableName) {
  const row = db.prepare(
    "select name from sqlite_master where type = 'table' and name = ?"
  ).get(tableName);
  return !!row;
}

/**
 * 查询某会话首条 user 消息的首个 text part 文本(用于兜底标题派生,问题1)。
 * 不依赖 SQLite JSON1(json_extract),在 JS 侧解析 data JSON。无 message/part 表或无 user text → null。
 */
function nodeQueryFirstUserText(db, sessionId) {
  if (!nodeTableExists(db, 'message') || !nodeTableExists(db, 'part')) return null;
  const msgs = db.prepare(
    'select id, time_created, data from message where session_id = ? order by time_created, id'
  ).all(sessionId);
  const firstUser = msgs.find((m) => safeJson(m.data).role === 'user');
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

export async function getSessionList(params = {}) {
  // 枚举 OpenCode 会话(对称 Codex getSessionsForProjectAsJson,字段对齐 Codex SessionInfo)。
  const projectPath = params.projectPath || params.cwd || '';
  const dbPath = params.dbPath || defaultDbPath();
  if (!existsSync(dbPath)) {
    return { success: false, error: `OpenCode database not found: ${dbPath}` };
  }

  // §问题2:改用 node:sqlite(readOnly + WAL 感知)替代 sql.js。sql.js(WASM)从主文件 buffer
  // 加载、对 -wal 边车文件视而不见;OpenCode 用 WAL 模式,新会话先落 -wal,checkpoint 前主文件没有,
  // sql.js 读不到 → "上一个会话过会儿才出现"。node:sqlite 按路径打开,WAL 自动恢复,确定性读到。
  let db;
  try {
    db = new DatabaseSync(dbPath, { readOnly: true });
  } catch (e) {
    return { success: false, error: `Failed to open OpenCode database: ${e.message}` };
  }
  try {
    if (!nodeTableExists(db, 'session')) {
      // 优雅降级:旧 schema 无 session 表
      return { success: true, sessions: [], sessionCount: 0 };
    }
    const countExpr = nodeTableExists(db, 'message')
      ? '(select count(*) from message where message.session_id = session.id)'
      : '0';
    const result = db.prepare(
      `select id, title, directory, time_created, time_updated, ${countExpr} as msg_count
       from session
       where time_archived is null
       order by time_updated desc`
    ).all();
    let sessions = result.map((row) => ({
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
    writeFileSync(dbPath, Buffer.from(db.export()));
    return { success: true, archived };
  } finally {
    db.close();
  }
}
