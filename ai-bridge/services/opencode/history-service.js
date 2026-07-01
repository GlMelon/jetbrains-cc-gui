/**
 * Read OpenCode session history from the local SQLite database and convert it
 * to the webview's normalized message shape.
 */

import { existsSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import initSqlJs from 'sql.js';

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

function textBlock(text) {
  return { type: 'text', text: text || '' };
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
  if (!tableExists(db, 'message') || !tableExists(db, 'part')) {
    return [];
  }
  const messages = rows(db.exec(
    'select id, session_id, time_created, data from message where session_id = ? order by time_created, id',
    [sessionId]
  ));
  const parts = rows(db.exec(
    'select id, message_id, session_id, time_created, data from part where session_id = ? order by time_created, id',
    [sessionId]
  ));
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
  if (!tableExists(db, 'event')) {
    return [];
  }
  const events = rows(db.exec(
    'select seq, type, data from event where aggregate_id = ? order by seq',
    [sessionId]
  ));
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

  const SQL = await initSqlJs();
  const db = new SQL.Database(readFileSync(dbPath));
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

export async function getSessionList(params = {}) {
  // 枚举 OpenCode 会话(对称 Codex getSessionsForProjectAsJson,字段对齐 Codex SessionInfo)。
  const projectPath = params.projectPath || params.cwd || '';
  const dbPath = params.dbPath || defaultDbPath();
  if (!existsSync(dbPath)) {
    return { success: false, error: `OpenCode database not found: ${dbPath}` };
  }

  const SQL = await initSqlJs();
  const db = new SQL.Database(readFileSync(dbPath));
  try {
    if (!tableExists(db, 'session')) {
      // 优雅降级:旧 schema 无 session 表
      return { success: true, sessions: [], sessionCount: 0 };
    }
    const countExpr = tableExists(db, 'message')
      ? '(select count(*) from message where message.session_id = session.id)'
      : '0';
    const result = rows(db.exec(
      `select id, title, directory, time_created, time_updated, ${countExpr} as msg_count
       from session
       where time_archived is null
       order by time_updated desc`
    ));
    let sessions = result.map((row) => ({
      sessionId: row.id,
      title: row.title || '(未命名)',
      messageCount: Number(row.msg_count || 0),
      firstTimestamp: Number(row.time_created || 0),
      lastTimestamp: Number(row.time_updated || 0),
      cwd: row.directory || '',
    }));

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
