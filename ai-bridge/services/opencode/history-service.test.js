import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join } from 'node:path';
import initSqlJs from 'sql.js';

import { getSessionMessages, getSessionList, defaultDbPath, archiveSession, isDefaultSessionTitle, deriveFallbackTitle } from './history-service.js';

async function createHistoryDb({ sessionId }) {
  const dir = mkdtempSync(join(tmpdir(), 'opencode-history-'));
  const dbPath = join(dir, 'opencode.db');
  const SQL = await initSqlJs();
  const db = new SQL.Database();

  db.run('create table message (id text, session_id text, time_created integer, data text)');
  db.run('create table part (id text, message_id text, session_id text, time_created integer, data text)');

  db.run(
    'insert into message values (?, ?, ?, ?)',
    ['msg_user', sessionId, 1710000000000, JSON.stringify({ role: 'user' })]
  );
  db.run(
    'insert into part values (?, ?, ?, ?, ?)',
    ['part_user_text', 'msg_user', sessionId, 1710000000001, JSON.stringify({
      type: 'text',
      text: '请用中文介绍 Python 语言',
    })]
  );
  db.run(
    'insert into message values (?, ?, ?, ?)',
    ['msg_assistant', sessionId, 1710000002000, JSON.stringify({
      role: 'assistant',
      tokens: { input: 11, output: 22, cache: { read: 3, write: 4 } },
    })]
  );
  db.run(
    'insert into part values (?, ?, ?, ?, ?)',
    ['part_reasoning', 'msg_assistant', sessionId, 1710000002001, JSON.stringify({
      type: 'reasoning',
      text: '先总结关键特点',
    })]
  );
  db.run(
    'insert into part values (?, ?, ?, ?, ?)',
    ['part_assistant_text', 'msg_assistant', sessionId, 1710000002002, JSON.stringify({
      type: 'text',
      text: 'Python 语法简洁，生态丰富。',
    })]
  );

  writeFileSync(dbPath, Buffer.from(db.export()));
  db.close();
  return { dir, dbPath };
}

async function createEventHistoryDb({ sessionId }) {
  const dir = mkdtempSync(join(tmpdir(), 'opencode-event-history-'));
  const dbPath = join(dir, 'opencode.db');
  const SQL = await initSqlJs();
  const db = new SQL.Database();

  db.run('create table message (id text, session_id text, time_created integer, data text)');
  db.run('create table part (id text, message_id text, session_id text, time_created integer, data text)');
  db.run('create table event (id text, aggregate_id text, seq integer, type text, data text)');

  const insertEvent = (seq, type, data) => db.run(
    'insert into event values (?, ?, ?, ?, ?)',
    [`evt_${seq}`, sessionId, seq, type, JSON.stringify(data)]
  );

  insertEvent(1, 'message.updated.1', {
    sessionID: sessionId,
    info: { id: 'msg_user', role: 'user', time: { created: 1710000000000 } },
  });
  insertEvent(2, 'message.part.updated.1', {
    sessionID: sessionId,
    part: {
      id: 'part_user_text',
      messageID: 'msg_user',
      type: 'text',
      text: '你好',
    },
    time: 1710000000001,
  });
  insertEvent(3, 'message.updated.1', {
    sessionID: sessionId,
    info: { id: 'msg_assistant', role: 'assistant', time: { created: 1710000001000 } },
  });
  insertEvent(4, 'message.part.updated.1', {
    sessionID: sessionId,
    part: {
      id: 'part_reasoning',
      messageID: 'msg_assistant',
      type: 'reasoning',
      text: '分析用户问候',
    },
    time: 1710000001001,
  });
  insertEvent(5, 'message.part.updated.1', {
    sessionID: sessionId,
    part: {
      id: 'part_assistant_text',
      messageID: 'msg_assistant',
      type: 'text',
      text: '你好！有什么我可以帮你的吗？',
    },
    time: 1710000001002,
  });
  insertEvent(6, 'message.updated.1', {
    sessionID: sessionId,
    info: {
      id: 'msg_assistant',
      role: 'assistant',
      time: { created: 1710000001000 },
      tokens: { input: 5, output: 7, cache: { read: 2, write: 1 } },
    },
  });

  writeFileSync(dbPath, Buffer.from(db.export()));
  db.close();
  return { dir, dbPath };
}

test('getSessionMessages reads OpenCode SQLite history as normalized messages', async () => {
  const sessionId = 'ses_history_1';
  const { dir, dbPath } = await createHistoryDb({ sessionId });
  try {
    const result = await getSessionMessages({ sessionId, dbPath });

    assert.equal(result.success, true);
    assert.equal(result.messages.length, 2);
    assert.equal(result.totalMessageCount, 2);

    assert.equal(result.messages[0].type, 'user');
    assert.equal(result.messages[0].content, '请用中文介绍 Python 语言');
    assert.deepEqual(result.messages[0].raw.content, [
      { type: 'text', text: '请用中文介绍 Python 语言' },
    ]);

    assert.equal(result.messages[1].type, 'assistant');
    assert.equal(result.messages[1].content, 'Python 语法简洁，生态丰富。');
    assert.deepEqual(result.messages[1].raw.content, [
      { type: 'thinking', thinking: '先总结关键特点', text: '先总结关键特点' },
      { type: 'text', text: 'Python 语法简洁，生态丰富。' },
    ]);
    assert.deepEqual(result.messages[1].raw.usage, {
      input_tokens: 11,
      output_tokens: 22,
      cache_read_input_tokens: 3,
      cache_creation_input_tokens: 4,
    });
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('getSessionMessages replays OpenCode event log when materialized tables are empty', async () => {
  const sessionId = 'ses_event_history_1';
  const { dir, dbPath } = await createEventHistoryDb({ sessionId });
  try {
    const result = await getSessionMessages({ sessionId, dbPath });

    assert.equal(result.success, true);
    assert.equal(result.messages.length, 2);
    assert.equal(result.totalMessageCount, 2);
    assert.equal(result.messages[0].type, 'user');
    assert.equal(result.messages[0].content, '你好');

    assert.equal(result.messages[1].type, 'assistant');
    assert.equal(result.messages[1].content, '你好！有什么我可以帮你的吗？');
    assert.deepEqual(result.messages[1].raw.content, [
      { type: 'thinking', thinking: '分析用户问候', text: '分析用户问候' },
      { type: 'text', text: '你好！有什么我可以帮你的吗？' },
    ]);
    assert.deepEqual(result.messages[1].raw.usage, {
      input_tokens: 5,
      output_tokens: 7,
      cache_read_input_tokens: 2,
      cache_creation_input_tokens: 1,
    });
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});


test('getSessionMessages bounds materialized rows while preserving total count', async () => {
  const sessionId = 'ses_history_bounded';
  const { dir, dbPath } = await createHistoryDb({ sessionId });
  try {
    const result = await getSessionMessages({
      sessionId,
      dbPath,
      maxMessageCount: 1,
      maxUtf8Bytes: 1024 * 1024,
    });

    assert.equal(result.success, true);
    assert.equal(result.messages.length, 1);
    assert.equal(result.messages[0].type, 'user');
    assert.equal(result.totalMessageCount, 2);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('getSessionMessages omits an oversized leading message without losing total count', async () => {
  const sessionId = 'ses_history_oversized';
  const { dir, dbPath } = await createHistoryDb({ sessionId });
  try {
    const result = await getSessionMessages({
      sessionId,
      dbPath,
      maxMessageCount: 10,
      maxUtf8Bytes: 64,
    });

    assert.equal(result.success, true);
    assert.deepEqual(result.messages, []);
    assert.equal(result.totalMessageCount, 2);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('getSessionMessages bounds event replay while preserving total count', async () => {
  const sessionId = 'ses_event_history_bounded';
  const { dir, dbPath } = await createEventHistoryDb({ sessionId });
  try {
    const result = await getSessionMessages({
      sessionId,
      dbPath,
      maxMessageCount: 1,
      maxUtf8Bytes: 1024 * 1024,
    });

    assert.equal(result.success, true);
    assert.equal(result.messages.length, 1);
    assert.equal(result.messages[0].type, 'user');
    assert.equal(result.totalMessageCount, 2);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('getSessionMessages reports missing database without throwing', async () => {
  const result = await getSessionMessages({
    sessionId: 'ses_missing_db',
    dbPath: join(tmpdir(), 'does-not-exist-opencode.db'),
  });

  assert.equal(result.success, false);
  assert.match(result.error, /database not found/i);
});

async function createSessionListDb({ sessions = [], messages = [], parts = [] }) {
  const dir = mkdtempSync(join(tmpdir(), 'opencode-session-list-'));
  const dbPath = join(dir, 'opencode.db');
  const SQL = await initSqlJs();
  const db = new SQL.Database();

  db.run(
    'create table session (id text, title text, directory text, time_created integer, time_updated integer, time_archived integer)'
  );
  db.run('create table message (id text, session_id text, time_created integer, data text)');
  // 问题1 标题回退需读首条 user 文本(text 落在 part 表);无 parts 入参时建空表不影响既有断言
  db.run('create table part (id text, message_id text, session_id text, time_created integer, data text)');

  for (const s of sessions) {
    db.run('insert into session values (?, ?, ?, ?, ?, ?)', [
      s.id,
      s.title === undefined ? null : s.title,
      s.directory === undefined ? null : s.directory,
      s.timeCreated ?? 0,
      s.timeUpdated ?? 0,
      s.timeArchived === undefined ? null : s.timeArchived,
    ]);
  }
  for (const m of messages) {
    db.run('insert into message values (?, ?, ?, ?)', [
      m.id,
      m.sessionId,
      m.timeCreated ?? 0,
      m.data ?? '{}',
    ]);
  }
  for (const p of parts) {
    db.run('insert into part values (?, ?, ?, ?, ?)', [
      p.id,
      p.messageId,
      p.sessionId,
      p.timeCreated ?? 0,
      p.data ?? '{}',
    ]);
  }

  writeFileSync(dbPath, Buffer.from(db.export()));
  db.close();
  return { dir, dbPath };
}

test('getSessionList maps session rows to frontend contract with message counts and order', async () => {
  const { dir, dbPath } = await createSessionListDb({
    sessions: [
      {
        id: 'ses_a',
        title: '实现登录功能',
        directory: 'D:\\work\\myapp',
        timeCreated: 1710000000000,
        timeUpdated: 1710000050000,
      },
      {
        id: 'ses_b',
        title: '',
        directory: 'D:\\work\\myapp\\sub',
        timeCreated: 1710000010000,
        timeUpdated: 1710000060000,
      },
    ],
    messages: [
      { id: 'm1', sessionId: 'ses_a' },
      { id: 'm2', sessionId: 'ses_a' },
      { id: 'm3', sessionId: 'ses_a' },
      { id: 'm4', sessionId: 'ses_b' },
    ],
  });
  try {
    const result = await getSessionList({ dbPath });

    assert.equal(result.success, true);
    assert.equal(result.sessionCount, 2);
    // 按 time_updated 倒序 → ses_b 在前
    assert.equal(result.sessions[0].sessionId, 'ses_b');
    assert.equal(result.sessions[0].title, '(未命名)');
    assert.equal(result.sessions[0].messageCount, 1);
    assert.equal(result.sessions[0].firstTimestamp, 1710000010000);
    assert.equal(result.sessions[0].lastTimestamp, 1710000060000);
    assert.equal(result.sessions[0].cwd, 'D:\\work\\myapp\\sub');

    assert.equal(result.sessions[1].sessionId, 'ses_a');
    assert.equal(result.sessions[1].title, '实现登录功能');
    assert.equal(result.sessions[1].messageCount, 3);
    assert.equal(result.sessions[1].firstTimestamp, 1710000000000);
    assert.equal(result.sessions[1].lastTimestamp, 1710000050000);
    assert.equal(result.sessions[1].cwd, 'D:\\work\\myapp');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('getSessionList filters sessions to the requested project path (equals + subdir, backslash normalized)', async () => {
  const projectPath = 'D:/work/myapp';
  const { dir, dbPath } = await createSessionListDb({
    sessions: [
      { id: 'ses_match_eq', title: '根目录会话', directory: 'D:\\work\\myapp', timeUpdated: 1 },
      { id: 'ses_match_sub', title: '子目录会话', directory: 'D:\\work\\myapp\\sub', timeUpdated: 2 },
      { id: 'ses_unrelated', title: '无关会话', directory: 'D:\\other\\project', timeUpdated: 3 },
      { id: 'ses_prefix_only', title: '前缀陷阱', directory: 'D:\\work\\myapp-extra', timeUpdated: 4 },
    ],
  });
  try {
    const result = await getSessionList({ projectPath, dbPath });

    assert.equal(result.success, true);
    const ids = result.sessions.map((s) => s.sessionId).sort();
    // 仅精确匹配与子目录命中；前缀陷阱(myapp-extra)与无关目录被剔除
    assert.deepEqual(ids, ['ses_match_eq', 'ses_match_sub']);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('getSessionList returns all sessions when projectPath is empty', async () => {
  const { dir, dbPath } = await createSessionListDb({
    sessions: [
      { id: 'ses_one', title: '一', directory: 'D:\\a', timeUpdated: 1 },
      { id: 'ses_two', title: '二', directory: 'D:\\b', timeUpdated: 2 },
    ],
  });
  try {
    const result = await getSessionList({ projectPath: '', dbPath });

    assert.equal(result.success, true);
    assert.equal(result.sessionCount, 2);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('getSessionList excludes archived sessions', async () => {
  const { dir, dbPath } = await createSessionListDb({
    sessions: [
      { id: 'ses_live', title: '活跃', directory: 'D:\\a', timeUpdated: 1 },
      { id: 'ses_archived', title: '已归档', directory: 'D:\\a', timeUpdated: 2, timeArchived: 9999 },
    ],
  });
  try {
    const result = await getSessionList({ projectPath: '', dbPath });

    assert.equal(result.success, true);
    assert.deepEqual(
      result.sessions.map((s) => s.sessionId),
      ['ses_live']
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('getSessionList degrades to empty list when session table is absent', async () => {
  // 旧 schema:仅有 message/part,无 session 表
  const dir = mkdtempSync(join(tmpdir(), 'opencode-no-session-table-'));
  const dbPath = join(dir, 'opencode.db');
  const SQL = await initSqlJs();
  const db = new SQL.Database();
  db.run('create table message (id text, session_id text, time_created integer, data text)');
  writeFileSync(dbPath, Buffer.from(db.export()));
  db.close();
  try {
    const result = await getSessionList({ projectPath: '', dbPath });

    assert.equal(result.success, true);
    assert.deepEqual(result.sessions, []);
    assert.equal(result.sessionCount, 0);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('getSessionList reports missing database without throwing', async () => {
  const result = await getSessionList({
    projectPath: '',
    dbPath: join(tmpdir(), 'does-not-exist-opencode-list.db'),
  });

  assert.equal(result.success, false);
  assert.match(result.error, /database not found/i);
});

// 问题1:OpenCode AI 标题生成偶发不触发(3/11 会话 title 从创建起始终是默认时间戳),
// 生成逻辑在 OpenCode 内部插件层无法修。getSessionList 在序列化 JSON 给前端时做兜底:
// title 命中默认时间戳模式(或空)→ 从首条 user 消息派生标题;有真实 AI 标题则忽略。
// 不动 db,只在输出 JSON 层替换。

test('isDefaultSessionTitle flags OpenCode default timestamp titles and blanks', () => {
  assert.equal(isDefaultSessionTitle('New session - 2026-07-03T06:12:37.102Z'), true);
  assert.equal(isDefaultSessionTitle('New session - 2026-07-03T05:49:25.138Z'), true);
  assert.equal(isDefaultSessionTitle(''), true);
  assert.equal(isDefaultSessionTitle(null), true);
  assert.equal(isDefaultSessionTitle('   '), true);
  // 真实 AI 标题不命中
  assert.equal(isDefaultSessionTitle('测试连通性'), false);
  assert.equal(isDefaultSessionTitle('Greeting'), false);
  assert.equal(isDefaultSessionTitle('17x23 step-by-step calculation'), false);
});

test('deriveFallbackTitle takes first line of real user text', () => {
  assert.equal(deriveFallbackTitle('帮我看下这个登录 bug'), '帮我看下这个登录 bug');
  // 多行取首行
  assert.equal(deriveFallbackTitle('第一行问题\n第二行补充'), '第一行问题');
});

test('deriveFallbackTitle strips IDE-appended context before deriving', () => {
  // 首条 user 文本含 IDE 拼接的 ## Project Modules 上下文(问题3 同源),标题只取真实正文
  const text = '请用中文介绍 Python\n\n## Project Modules\n\nThis project contains multiple modules:\n- a';
  assert.equal(deriveFallbackTitle(text), '请用中文介绍 Python');
});

test('deriveFallbackTitle truncates long text with ellipsis', () => {
  const long = '这是一段非常非常非常非常非常非常非常非常非常非常非常非常非常非常非常长的用户提问内容用来测试截断逻辑是否正常工作';
  const derived = deriveFallbackTitle(long);
  assert.ok(derived.endsWith('…'), `应以省略号结尾,实际: ${derived}`);
  assert.ok(derived.length <= 41, `截断后应 <= 40 字符 + 省略号,实际长度: ${derived.length}`);
});

test('deriveFallbackTitle returns null for empty/whitespace-only text', () => {
  assert.equal(deriveFallbackTitle(''), null);
  assert.equal(deriveFallbackTitle(null), null);
  assert.equal(deriveFallbackTitle('   \n\n   '), null);
});

test('getSessionList derives title from first user message when db title is default timestamp', async () => {
  const { dir, dbPath } = await createSessionListDb({
    sessions: [
      {
        id: 'ses_default_title',
        title: 'New session - 2026-07-03T06:12:37.102Z',
        directory: 'D:\\work\\myapp',
        timeCreated: 1710000000000,
        timeUpdated: 1710000050000,
      },
    ],
    messages: [
      { id: 'm_user', sessionId: 'ses_default_title', timeCreated: 1710000001000, data: JSON.stringify({ role: 'user' }) },
    ],
    parts: [
      {
        id: 'p_text',
        messageId: 'm_user',
        sessionId: 'ses_default_title',
        timeCreated: 1710000001100,
        data: JSON.stringify({ type: 'text', text: '请用中文介绍 Python\n\n## Project Modules\n\n多余上下文' }),
      },
    ],
  });
  try {
    const result = await getSessionList({ dbPath });
    assert.equal(result.success, true);
    // 默认时间戳标题 → 兜底为首条 user 消息正文(且剥除拼接上下文)
    assert.equal(result.sessions[0].title, '请用中文介绍 Python');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('getSessionList ignores fallback when db has a real AI title', async () => {
  const { dir, dbPath } = await createSessionListDb({
    sessions: [
      {
        id: 'ses_ai_title',
        title: '测试连通性',
        directory: 'D:\\work\\myapp',
        timeCreated: 1710000000000,
        timeUpdated: 1710000050000,
      },
    ],
    messages: [
      { id: 'm_user', sessionId: 'ses_ai_title', timeCreated: 1710000001000, data: JSON.stringify({ role: 'user' }) },
    ],
    parts: [
      {
        id: 'p_text',
        messageId: 'm_user',
        sessionId: 'ses_ai_title',
        timeCreated: 1710000001100,
        data: JSON.stringify({ type: 'text', text: '这是首条消息不应覆盖真实标题' }),
      },
    ],
  });
  try {
    const result = await getSessionList({ dbPath });
    assert.equal(result.success, true);
    // 真实 AI 标题保留,不被兜底覆盖
    assert.equal(result.sessions[0].title, '测试连通性');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('getSessionList keeps (未命名) when default title session has no first user text', async () => {
  const { dir, dbPath } = await createSessionListDb({
    sessions: [
      {
        id: 'ses_no_text',
        title: 'New session - 2026-07-03T06:04:08.318Z',
        directory: 'D:\\work\\myapp',
        timeCreated: 1710000000000,
        timeUpdated: 1710000050000,
      },
    ],
    // 无消息/无 part → 派生不出首条文本 → 回退到通用 (未命名)
    messages: [],
    parts: [],
  });
  try {
    const result = await getSessionList({ dbPath });
    assert.equal(result.success, true);
    assert.equal(result.sessions[0].title, '(未命名)');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('defaultDbPath honors XDG_DATA_HOME when set', () => {
  const saved = process.env.XDG_DATA_HOME;
  const xdgDir = mkdtempSync(join(tmpdir(), 'opencode-xdg-'));
  try {
    process.env.XDG_DATA_HOME = xdgDir;
    assert.equal(defaultDbPath(), join(xdgDir, 'opencode', 'opencode.db'));
  } finally {
    rmSync(xdgDir, { recursive: true, force: true });
    if (saved === undefined) delete process.env.XDG_DATA_HOME;
    else process.env.XDG_DATA_HOME = saved;
  }
});

test('defaultDbPath falls back to ~/.local/share when XDG_DATA_HOME is unset', () => {
  const saved = process.env.XDG_DATA_HOME;
  try {
    delete process.env.XDG_DATA_HOME;
    assert.equal(
      defaultDbPath(),
      join(homedir(), '.local', 'share', 'opencode', 'opencode.db')
    );
  } finally {
    if (saved !== undefined) process.env.XDG_DATA_HOME = saved;
  }
});

test('defaultDbPath ignores blank XDG_DATA_HOME and falls back', () => {
  const saved = process.env.XDG_DATA_HOME;
  try {
    process.env.XDG_DATA_HOME = '   ';
    assert.equal(
      defaultDbPath(),
      join(homedir(), '.local', 'share', 'opencode', 'opencode.db')
    );
  } finally {
    if (saved === undefined) delete process.env.XDG_DATA_HOME;
    else process.env.XDG_DATA_HOME = saved;
  }
});

test('archiveSession marks a session archived and hides it from getSessionList', async () => {
  const { dir, dbPath } = await createSessionListDb({
    sessions: [
      { id: 'ses_archive', title: '要归档', directory: 'D:\\a', timeUpdated: 1 },
      { id: 'ses_keep', title: '保留', directory: 'D:\\a', timeUpdated: 2 },
    ],
  });
  try {
    const result = await archiveSession({ sessionId: 'ses_archive', dbPath });
    assert.equal(result.success, true);
    assert.equal(result.archived, 1);

    const list = await getSessionList({ projectPath: '', dbPath });
    assert.deepEqual(
      list.sessions.map((s) => s.sessionId),
      ['ses_keep']
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('archiveSession reports zero rows for unknown session id without throwing', async () => {
  const { dir, dbPath } = await createSessionListDb({
    sessions: [{ id: 'ses_real', title: '真实', directory: 'D:\\a', timeUpdated: 1 }],
  });
  try {
    const result = await archiveSession({ sessionId: 'ses_missing', dbPath });
    assert.equal(result.success, true);
    assert.equal(result.archived, 0);

    // 真实会话未受影响,仍出现在列表
    const list = await getSessionList({ projectPath: '', dbPath });
    assert.deepEqual(list.sessions.map((s) => s.sessionId), ['ses_real']);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('archiveSession is idempotent — re-archiving an archived session touches zero rows', async () => {
  const { dir, dbPath } = await createSessionListDb({
    sessions: [{ id: 'ses_once', title: '一次', directory: 'D:\\a', timeUpdated: 1 }],
  });
  try {
    await archiveSession({ sessionId: 'ses_once', dbPath });
    const second = await archiveSession({ sessionId: 'ses_once', dbPath });
    assert.equal(second.success, true);
    assert.equal(second.archived, 0);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('archiveSession reports missing database without throwing', async () => {
  const result = await archiveSession({
    sessionId: 'ses_missing_db',
    dbPath: join(tmpdir(), 'does-not-exist-opencode-archive.db'),
  });
  assert.equal(result.success, false);
  assert.match(result.error, /database not found/i);
});

test('archiveSession reports missing sessionId without touching the database', async () => {
  const { dir, dbPath } = await createSessionListDb({ sessions: [] });
  try {
    const result = await archiveSession({ sessionId: '', dbPath });
    assert.equal(result.success, false);
    assert.match(result.error, /sessionId/i);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// 问题2:OpenCode 用 SQLite WAL 日志模式写新会话,写入先进 -wal 边车文件,checkpoint 后才进主文件。
// 旧读取器 sql.js(WASM)从 readFileSync 的主文件 buffer 加载,对 WAL 视而不见 → 漏掉刚写的会话,
// 表现为"上一个会话过一会儿才出现在历史列表"。node:sqlite(Node 内置)按文件路径打开、WAL 感知,
// 能读到 WAL 里尚未 checkpoint 的行。本测试用 node:sqlite 建库 + WAL + 写入 + 不 checkpoint,
// 断言 getSessionList 能读到——sql.js 实现下 RED,node:sqlite 实现下 GREEN。
test('getSessionList reads WAL-mode sessions not yet checkpointed (问题2: sql.js WAL-blind)', async () => {
  const { DatabaseSync } = await import('node:sqlite');
  const dir = mkdtempSync(join(tmpdir(), 'opencode-wal-'));
  const dbPath = join(dir, 'opencode.db');
  const writer = new DatabaseSync(dbPath);
  try {
    writer.exec(`create table session (id text, title text, directory text, time_created integer, time_updated integer, time_archived integer)`);
    writer.exec(`create table message (id text, session_id text, time_created integer, data text)`);
    writer.exec(`pragma journal_mode=wal`);
    // WAL 已启用:此行写入进入 opencode.db-wal 边车文件,主文件 opencode.db 里尚无此行
    writer.exec(`insert into session values ('ses_in_wal', 'WAL 会话', 'D:\\a', 1, 2, null)`);
    // 不关闭 writer,保持 WAL 未 checkpoint(模拟 OpenCode serve 持有连接、频繁写入)
    const result = await getSessionList({ projectPath: '', dbPath });
    assert.equal(result.success, true);
    const ids = result.sessions.map((s) => s.sessionId);
    assert.ok(
      ids.includes('ses_in_wal'),
      `WAL 里的新会话应可见(否则历史列表漏显示),实际: ${JSON.stringify(ids)}`
    );
    assert.equal(result.sessions[0].title, 'WAL 会话');
  } finally {
    writer.close();
    rmSync(dir, { recursive: true, force: true });
  }
});

// 回显空白根因(对称 §问题2,但作用在 getSessionMessages):点击历史记录回显时,
// getSessionMessages 读 message/part 表。OpenCode 用 WAL 模式,新会话消息先进 -wal 边车,
// checkpoint 前主文件没有。旧实现走 sql.js(WASM,从 readFileSync 的主文件 buffer 加载,对 WAL
// 视而不见)→ 读不到刚写的消息 → 返回空 → 前端点击历史记录后聊天窗空白(Claude/Codex 是
// JSONL 文件无此问题,故仅 OpenCode 复现)。node:sqlite 按路径打开、WAL 感知,能确定性读到。
// 本测试用 node:sqlite 建库 + WAL + 写消息 + 不 checkpoint,断言 getSessionMessages 能读到——
// sql.js 实现下 RED(读到 0 条),node:sqlite 实现下 GREEN。
test('getSessionMessages reads WAL-mode messages not yet checkpointed (回显空白根因: sql.js WAL-blind)', async () => {
  const { DatabaseSync } = await import('node:sqlite');
  const dir = mkdtempSync(join(tmpdir(), 'opencode-msg-wal-'));
  const dbPath = join(dir, 'opencode.db');
  const writer = new DatabaseSync(dbPath);
  try {
    writer.exec(`create table message (id text, session_id text, time_created integer, data text)`);
    writer.exec(`create table part (id text, message_id text, session_id text, time_created integer, data text)`);
    writer.exec(`pragma journal_mode=wal`);
    // 写入进入 opencode.db-wal 边车,主文件 opencode.db 里尚无这些行
    writer.exec(`insert into message values ('msg_wal_user', 'ses_in_wal', 1710000000000, '{"role":"user"}')`);
    writer.exec(`insert into part values ('part_wal_text', 'msg_wal_user', 'ses_in_wal', 1710000000001, '{"type":"text","text":"WAL里的消息应可回显"}')`);
    // 不关闭 writer,保持 WAL 未 checkpoint(模拟 OpenCode serve 持有连接、新会话消息未落主文件)
    const result = await getSessionMessages({ sessionId: 'ses_in_wal', dbPath });
    assert.equal(result.success, true);
    assert.equal(
      result.messages.length,
      1,
      `WAL 里的消息应可回显(否则点击历史记录聊天窗空白),实际: ${JSON.stringify(result.messages)}`
    );
    assert.equal(result.messages[0].type, 'user');
    assert.equal(result.messages[0].content, 'WAL里的消息应可回显');
    assert.deepEqual(result.messages[0].raw.content, [
      { type: 'text', text: 'WAL里的消息应可回显' },
    ]);
  } finally {
    writer.close();
    rmSync(dir, { recursive: true, force: true });
  }
});

// 问题4:OpenCode 历史回显多了一对双引号。用户发送"你好呀，你会什么"(IDE 拼接 ## Project Modules
// 等上下文后含换行)入库时,opencode 在 message.part.updated.1 事件源头的 part.text 就包了一对
// 双引号(实测 ses_0d520b4f8ffe2D017l45gKV3Gu:part.text = "\"你好呀，你会什么\n\n## Project Modules...\"")。
// 短消息(无换行/上下文)不带引号,assistant 文本从不带引号。这是 opencode 侧行为(我们只读 db 不可能
// 引入),不可控;回显时在 normalizePart/textBlock 剥掉这对配对的首尾双引号,恢复用户原始输入。
test('getSessionMessages strips opencode wrapping double quotes from multiline user prompt (问题4)', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'opencode-quoted-prompt-'));
  const dbPath = join(dir, 'opencode.db');
  const SQL = await initSqlJs();
  const db = new SQL.Database();
  db.run('create table message (id text, session_id text, time_created integer, data text)');
  db.run('create table part (id text, message_id text, session_id text, time_created integer, data text)');
  db.run(
    'insert into message values (?, ?, ?, ?)',
    ['msg_u', 'ses_q', 1710000000000, JSON.stringify({ role: 'user' })]
  );
  db.run(
    'insert into part values (?, ?, ?, ?, ?)',
    [
      'part_u',
      'msg_u',
      'ses_q',
      1710000000001,
      // opencode 入库后的实际形态:首尾各一个双引号包裹整段含换行的 prompt
      JSON.stringify({ type: 'text', text: '"你好呀，你会什么\n\n## Project Modules"' }),
    ]
  );
  writeFileSync(dbPath, Buffer.from(db.export()));
  db.close();
  try {
    const result = await getSessionMessages({ sessionId: 'ses_q', dbPath });
    assert.equal(result.success, true);
    assert.equal(result.messages.length, 1);
    // 首尾双引号被剥,恢复用户原始输入(含 IDE 拼接的上下文)
    assert.equal(result.messages[0].content, '你好呀，你会什么\n\n## Project Modules');
    assert.deepEqual(result.messages[0].raw.content, [
      { type: 'text', text: '你好呀，你会什么\n\n## Project Modules' },
    ]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('getSessionMessages leaves short single-line user prompt untouched (问题4 守卫:不误剥)', async () => {
  // 短消息(无换行)opencode 不加引号;stripWrappingQuotes 守卫"含 \n 且首尾配对双引号"才剥,
  // 避免误剥用户手输首尾引号的短消息(罕见但需保护)。
  const dir = mkdtempSync(join(tmpdir(), 'opencode-short-prompt-'));
  const dbPath = join(dir, 'opencode.db');
  const SQL = await initSqlJs();
  const db = new SQL.Database();
  db.run('create table message (id text, session_id text, time_created integer, data text)');
  db.run('create table part (id text, message_id text, session_id text, time_created integer, data text)');
  db.run(
    'insert into message values (?, ?, ?, ?)',
    ['msg_u2', 'ses_s', 1710000000000, JSON.stringify({ role: 'user' })]
  );
  db.run(
    'insert into part values (?, ?, ?, ?, ?)',
    [
      'part_u2',
      'msg_u2',
      'ses_s',
      1710000000001,
      JSON.stringify({ type: 'text', text: '回复ok' }),
    ]
  );
  writeFileSync(dbPath, Buffer.from(db.export()));
  db.close();
  try {
    const result = await getSessionMessages({ sessionId: 'ses_s', dbPath });
    assert.equal(result.success, true);
    assert.equal(result.messages[0].content, '回复ok');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
