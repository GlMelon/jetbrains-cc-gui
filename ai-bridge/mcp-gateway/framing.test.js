// framing.test.js — MCP stdio 帧解析双模(NDJSON + LSP Content-Length)兼容测试。
//
// 背景(2026-07-03 排查 opencode gateway 首请求 30s 慢):
//   MCP 官方 spec 规定 stdio 传输用 NDJSON(newline-delimited JSON),无 Content-Length
//   header。opencode.exe(Go,严格遵循 spec)发 NDJSON initialize;而 framing.js 原实现
//   只认 LSP 风格的 `Content-Length: N\r\n\r\n{json}` 帧 → opencode 的 initialize 永远
//   进不了 FramedReader 的解析路径 → gateway 不回 initialize → opencode 等满 30s
//   initialize 超时,标记 melon_gateway status=failed(实测实验 C 35.7s)。
//   反之 codex.exe(Rust,二进制内含 11 处 "Content-Length")沿用 LSP 帧,与原 framing
//   匹配,故 Codex 路径 ~1s 正常。修复=双模解析(NDJSON 默认 + LSP 兼容)+ 响应帧跟随
//   客户端探测到的帧格式(stream.__mcpFrameFormat),opencode/codex 各得其所。
//
// 测试约定:`node --test framing.test.js`,风格对齐 ai-bridge/utils/exit-strategy.test.js。

import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { FramedReader, encodeMessage, writeMessage } from './framing.js';

// 辅助:把 FramedReader 绑到一个模拟 stream(EventEmitter),便于 emit 'data' 推数据。
function harness() {
  const stream = new EventEmitter();
  const reader = new FramedReader(stream);
  return { reader, stream };
}

// 辅助:收集 reader 的 'message' 事件。
function collect(reader) {
  const msgs = [];
  reader.on('message', (m) => msgs.push(m));
  return msgs;
}

// ════════════════════════════════════════════════════════════════════════════
// NDJSON 解析(MCP spec 标准,opencode 走此格式)
// ════════════════════════════════════════════════════════════════════════════

test('NDJSON: 单行消息被解析,lastFormat=ndjson', () => {
  const { reader, stream } = harness();
  const msgs = collect(reader);
  stream.emit('data', Buffer.from(JSON.stringify({ jsonrpc: '2.0', id: 0, method: 'initialize' }) + '\n', 'utf8'));
  assert.equal(msgs.length, 1);
  assert.equal(msgs[0].method, 'initialize');
  assert.equal(reader.lastFormat, 'ndjson');
});

test('NDJSON: 同一 chunk 含多条消息(换行分隔)全部解析', () => {
  const { reader, stream } = harness();
  const msgs = collect(reader);
  const a = JSON.stringify({ method: 'a' });
  const b = JSON.stringify({ method: 'b' });
  const c = JSON.stringify({ method: 'c' });
  stream.emit('data', Buffer.from(`${a}\n${b}\n${c}\n`));
  assert.equal(msgs.length, 3);
  assert.deepEqual(msgs.map((m) => m.method), ['a', 'b', 'c']);
});

test('NDJSON: 跨 chunk 拼接(消息被拆到多个 data 事件)', () => {
  const { reader, stream } = harness();
  const msgs = collect(reader);
  const full = JSON.stringify({ method: 'initialize', params: { x: 1 } });
  stream.emit('data', Buffer.from(full.slice(0, 10)));
  assert.equal(msgs.length, 0, '不完整消息不应 emit');
  stream.emit('data', Buffer.from(full.slice(10, 20)));
  assert.equal(msgs.length, 0);
  stream.emit('data', Buffer.from(full.slice(20) + '\n'));
  assert.equal(msgs.length, 1);
  assert.equal(msgs[0].method, 'initialize');
  assert.equal(msgs[0].params.x, 1);
});

test('NDJSON: 前导空行被跳过,不产生 message 也不报错', () => {
  const { reader, stream } = harness();
  const errors = [];
  reader.on('error', (e) => errors.push(e));
  const msgs = collect(reader);
  stream.emit('data', Buffer.from('\n\n' + JSON.stringify({ method: 'x' }) + '\n'));
  assert.equal(msgs.length, 1);
  assert.equal(msgs[0].method, 'x');
  assert.equal(errors.length, 0);
});

// ════════════════════════════════════════════════════════════════════════════
// LSP(Content-Length)兼容 —— codex.exe 走此格式,不得回归
// ════════════════════════════════════════════════════════════════════════════

test('LSP: Content-Length 单消息被解析,lastFormat=lsp', () => {
  const { reader, stream } = harness();
  const msgs = collect(reader);
  stream.emit('data', encodeMessage({ jsonrpc: '2.0', id: 1, method: 'tools/list' }, 'lsp'));
  assert.equal(msgs.length, 1);
  assert.equal(msgs[0].method, 'tools/list');
  assert.equal(reader.lastFormat, 'lsp');
});

test('LSP: header 与 body 分两个 chunk 到达仍能拼接', () => {
  const { reader, stream } = harness();
  const msgs = collect(reader);
  const body = JSON.stringify({ method: 'initialize' });
  const header = `Content-Length: ${Buffer.byteLength(body, 'utf8')}\r\n\r\n`;
  stream.emit('data', Buffer.from(header, 'utf8'));
  assert.equal(msgs.length, 0, '只有 header 不应 emit');
  stream.emit('data', Buffer.from(body, 'utf8'));
  assert.equal(msgs.length, 1);
  assert.equal(msgs[0].method, 'initialize');
  assert.equal(reader.lastFormat, 'lsp');
});

test('LSP: body 跨 chunk 拼接(header + 部分 body + 剩余 body)', () => {
  const { reader, stream } = harness();
  const msgs = collect(reader);
  const body = JSON.stringify({ method: 'tools/call', params: { name: 'big-tool', n: 123 } });
  const header = `Content-Length: ${Buffer.byteLength(body, 'utf8')}\r\n\r\n`;
  stream.emit('data', Buffer.from(header + body.slice(0, 5), 'utf8'));
  assert.equal(msgs.length, 0);
  stream.emit('data', Buffer.from(body.slice(5), 'utf8'));
  assert.equal(msgs.length, 1);
  assert.equal(msgs[0].params.name, 'big-tool');
});

test('LSP: 连续两条 LSP 消息在同一 chunk', () => {
  const { reader, stream } = harness();
  const msgs = collect(reader);
  const buf = Buffer.concat([
    encodeMessage({ method: 'one' }, 'lsp'),
    encodeMessage({ method: 'two' }, 'lsp'),
  ]);
  stream.emit('data', buf);
  assert.equal(msgs.length, 2);
  assert.deepEqual(msgs.map((m) => m.method), ['one', 'two']);
});

// ════════════════════════════════════════════════════════════════════════════
// NDJSON 流绝不被 LSP 路径误伤(回归核心:opencode 不再卡 30s)
// ════════════════════════════════════════════════════════════════════════════

test('NDJSON 流(无 Content-Length / 无 \\r\\n\\r\\n)绝不触发 "Missing Content-Length" 错误', () => {
  // 这是原 bug 的直接回归保护:旧 FramedReader 在 NDJSON 输入下会 emit
  // 'error'("Missing Content-Length header"),导致 stdio-client.js 的 reader 静默失败,
  // initialize 永不被 server.handle 处理。
  const { reader, stream } = harness();
  const errors = [];
  reader.on('error', (e) => errors.push(e));
  const msgs = collect(reader);
  stream.emit('data', Buffer.from(JSON.stringify({ jsonrpc: '2.0', id: 0, method: 'initialize', params: {} }) + '\n'));
  assert.equal(msgs.length, 1);
  assert.equal(errors.length, 0, 'NDJSON 输入不得产生任何 error 事件');
  assert.equal(reader.lastFormat, 'ndjson');
});

// ════════════════════════════════════════════════════════════════════════════
// encodeMessage 双格式
// ════════════════════════════════════════════════════════════════════════════

test('encodeMessage: 默认 ndjson(JSON + \\n,无 Content-Length)', () => {
  const buf = encodeMessage({ jsonrpc: '2.0', id: 1, result: { ok: true } });
  const text = buf.toString('utf8');
  assert.ok(text.endsWith('\n'), 'ndjson 必须以 \\n 结尾');
  assert.ok(!text.includes('Content-Length'), 'ndjson 不得含 Content-Length header');
  const parsed = JSON.parse(text.trimEnd());
  assert.equal(parsed.id, 1);
  assert.equal(parsed.result.ok, true);
});

test('encodeMessage: lsp 格式带 Content-Length header + \\r\\n\\r\\n 分隔', () => {
  const buf = encodeMessage({ jsonrpc: '2.0', id: 2, result: {} }, 'lsp');
  const text = buf.toString('utf8');
  assert.ok(text.startsWith('Content-Length: '), 'lsp 必须以 Content-Length header 开头');
  assert.ok(text.includes('\r\n\r\n'), 'lsp header 与 body 以 \\r\\n\\r\\n 分隔');
  const body = text.split('\r\n\r\n').slice(1).join('\r\n\r\n');
  const parsed = JSON.parse(body);
  assert.equal(parsed.id, 2);
});

test('encodeMessage: lsp 的 Content-Length 等于 body 的 UTF-8 字节数(含多字节字符)', () => {
  const msg = { result: { msg: '中文测试🚀' } };
  const buf = encodeMessage(msg, 'lsp');
  const text = buf.toString('utf8');
  const m = /Content-Length: (\d+)/.exec(text);
  assert.ok(m, '应有 Content-Length header');
  const declared = Number(m[1]);
  const body = text.split('\r\n\r\n').slice(1).join('\r\n\r\n');
  const actual = Buffer.byteLength(body, 'utf8');
  assert.equal(declared, actual, 'Content-Length 必须等于 body UTF-8 字节数');
});

// ════════════════════════════════════════════════════════════════════════════
// writeMessage 自适应(响应帧跟随客户端探测格式)
// ════════════════════════════════════════════════════════════════════════════

test('writeMessage: 无 format 且 stream 无标记时默认 ndjson', () => {
  const written = [];
  const fakeStream = { write: (chunk) => written.push(chunk) };
  writeMessage(fakeStream, { jsonrpc: '2.0', id: 1, result: {} });
  assert.equal(written.length, 1);
  const text = written[0].toString('utf8');
  assert.ok(text.endsWith('\n'));
  assert.ok(!text.includes('Content-Length'));
});

test('writeMessage: stream.__mcpFrameFormat=lsp 时写 LSP 帧', () => {
  const written = [];
  const fakeStream = { __mcpFrameFormat: 'lsp', write: (chunk) => written.push(chunk) };
  writeMessage(fakeStream, { jsonrpc: '2.0', id: 1, result: {} });
  const text = written[0].toString('utf8');
  assert.ok(text.startsWith('Content-Length: '), 'stream 标记 lsp 时应写 LSP');
});

test('writeMessage: stream.__mcpFrameFormat=ndjson 时写 NDJSON', () => {
  const written = [];
  const fakeStream = { __mcpFrameFormat: 'ndjson', write: (chunk) => written.push(chunk) };
  writeMessage(fakeStream, { jsonrpc: '2.0', id: 1, result: {} });
  const text = written[0].toString('utf8');
  assert.ok(text.endsWith('\n'));
  assert.ok(!text.includes('Content-Length'));
});

test('writeMessage: 显式 format 参数优先于 stream.__mcpFrameFormat', () => {
  const written = [];
  const fakeStream = { __mcpFrameFormat: 'ndjson', write: (chunk) => written.push(chunk) };
  writeMessage(fakeStream, { jsonrpc: '2.0', id: 1, result: {} }, 'lsp');
  const text = written[0].toString('utf8');
  assert.ok(text.startsWith('Content-Length: '), '显式 format 应覆盖 stream 标记');
});

// ════════════════════════════════════════════════════════════════════════════
// 端到端:reader 探测客户端格式 → 响应同格式(对称)
// ════════════════════════════════════════════════════════════════════════════

test('端到端 opencode(NDJSON):initialize → lastFormat=ndjson → 响应 NDJSON', () => {
  const { reader, stream } = harness();
  stream.emit('data', Buffer.from(JSON.stringify({ jsonrpc: '2.0', id: 0, method: 'initialize' }) + '\n'));
  assert.equal(reader.lastFormat, 'ndjson');
  const written = [];
  const stdout = { write: (chunk) => written.push(chunk) };
  stdout.__mcpFrameFormat = reader.lastFormat; // gateway-stdio-client.js 同步此属性
  writeMessage(stdout, { jsonrpc: '2.0', id: 0, result: { protocolVersion: '2024-11-05' } });
  const text = written[0].toString('utf8');
  assert.ok(text.endsWith('\n'), 'opencode 应收到 NDJSON');
  assert.ok(!text.includes('Content-Length'));
});

test('端到端 codex(LSP):initialize → lastFormat=lsp → 响应 LSP', () => {
  const { reader, stream } = harness();
  stream.emit('data', encodeMessage({ jsonrpc: '2.0', id: 0, method: 'initialize' }, 'lsp'));
  assert.equal(reader.lastFormat, 'lsp');
  const written = [];
  const stdout = { write: (chunk) => written.push(chunk) };
  stdout.__mcpFrameFormat = reader.lastFormat;
  writeMessage(stdout, { jsonrpc: '2.0', id: 0, result: { protocolVersion: '2024-11-05' } });
  const text = written[0].toString('utf8');
  assert.ok(text.startsWith('Content-Length: '), 'codex 应收到 LSP');
});
