import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join } from 'node:path';
import initSqlJs from 'sql.js';

import { getSessionMessages, getSessionList, defaultDbPath } from './history-service.js';

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

test('getSessionMessages reports missing database without throwing', async () => {
  const result = await getSessionMessages({
    sessionId: 'ses_missing_db',
    dbPath: join(tmpdir(), 'does-not-exist-opencode.db'),
  });

  assert.equal(result.success, false);
  assert.match(result.error, /database not found/i);
});

async function createSessionListDb({ sessions = [], messages = [] }) {
  const dir = mkdtempSync(join(tmpdir(), 'opencode-session-list-'));
  const dbPath = join(dir, 'opencode.db');
  const SQL = await initSqlJs();
  const db = new SQL.Database();

  db.run(
    'create table session (id text, title text, directory text, time_created integer, time_updated integer, time_archived integer)'
  );
  db.run('create table message (id text, session_id text, time_created integer, data text)');

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
