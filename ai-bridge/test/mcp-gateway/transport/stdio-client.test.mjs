import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { StdioMcpClient } from '../../../mcp-gateway/transport/stdio-client.js';

// 修复③:gateway stdio client request 必须有超时,否则单个 MCP server 挂起会拖到 Java 侧
// 10s 超时,放大首次延迟。

// fake 子进程:stdout 不 emit data(模拟 server 不响应),stdin 可写,kill 无操作。
// 用依赖注入而非真实 spawn,避免持久子进程在 Windows 下让 test 进程不退出。
function createFakeProcess() {
  const stdout = new EventEmitter();
  // stdin 须为 EventEmitter 以承载 'error' 监听(EPIPE 等,见 STAB-01);保留 write() 供 writeMessage 写帧。
  const stdin = new EventEmitter();
  stdin.write = () => {};
  return Object.assign(new EventEmitter(), { stdin, stdout, kill() {} });
}

test('request rejects with timeout when server does not respond', async () => {
  const client = new StdioMcpClient(
    { serverId: 'noop', config: { command: 'fake', args: [] } },
    { spawnFn: () => createFakeProcess() },
  );
  await assert.rejects(
    client.request('initialize', { protocolVersion: '2024-11-05' }, 100),
    /timeout/i,
  );
});

test('request resolves when response arrives before timeout (normal path intact)', async () => {
  const client = new StdioMcpClient(
    { serverId: 'noop', config: { command: 'fake', args: [] } },
    { spawnFn: () => createFakeProcess() },
  );
  const pending = client.request('tools/list', {}, 300);
  // 模拟 server 响应到达(下一 tick 注入,早于 300ms 超时)
  setImmediate(() => {
    const id = client.pending.keys().next().value;
    client.onMessage({ id, result: { ok: true } });
  });
  const result = await pending;
  assert.deepStrictEqual(result, { ok: true });
});

// 修复:Node spawn 在命令不存在(ENOENT)时异步触发 ChildProcess 的 'error' 事件(不走 'exit')。
// 修复前 stdio-client 只挂了 process.on('exit'),没挂 process.on('error'),无监听器 → EventEmitter
// 默认 throw → uncaught exception → 杀掉整个 gateway 进程(idea.log 实证:dbx-mcp-server ENOENT
// 让 gateway 反复崩溃重启,每次 send 都"第一次很慢")。supervisor 的 try/catch 接不住(异步 tick)。
// 期望:process 'error' 被 rejectAll 消化成单个 MCP 失败(BACKOFF),不波及 gateway 进程。

test('process spawn error (ENOENT) rejects pending request instead of crashing gateway', async () => {
  const fakeProcess = createFakeProcess();
  const client = new StdioMcpClient(
    { serverId: 'missing', sourceProvider: 'claude', config: { command: 'dbx-mcp-server', args: [] } },
    { spawnFn: () => fakeProcess },
  );
  const pending = client.request('initialize', { protocolVersion: '2024-11-05' }, 1000);
  // 模拟 Node spawn ENOENT:下一 tick 在 ChildProcess 上触发 'error'(无 exit 事件)
  setImmediate(() => fakeProcess.emit('error', new Error('spawn dbx-mcp-server ENOENT')));
  await assert.rejects(pending, /ENOENT/);
  client.close();
});

test('initialize rejects immediately when spawn errored before any request (errored guard)', async () => {
  const fakeProcess = createFakeProcess();
  const client = new StdioMcpClient(
    { serverId: 'missing', sourceProvider: 'claude', config: { command: 'bad', args: [] } },
    { spawnFn: () => fakeProcess },
  );
  // error 先于任何 request 触发(此时 pending 为空,rejectAll 无可 reject)
  fakeProcess.emit('error', new Error('spawn bad ENOENT'));
  // 后续 initialize 应立即 reject,而非挂到 15s 默认超时
  await assert.rejects(client.initialize(), /ENOENT/);
  client.close();
});

// STAB-02:进程退出后须置 errored。否则 supervisor 持有的死 client 仍非 null,后续 catalog refresh
// 复用死 client 调 listTools 写已关闭 stdin,等满 15s 默认超时才失败;坏 MCP 反复触发持续拖慢首屏。
// 期望:exit 后 errored 置位,后续 request 经 errored 守卫立即 reject(< 1s),不挂 15s。

test('process exit marks client dead so next request fails immediately (not after 15s)', async () => {
  const fakeProcess = createFakeProcess();
  const client = new StdioMcpClient(
    { serverId: 'crash', sourceProvider: 'claude', config: { command: 'fake', args: [] } },
    { spawnFn: () => fakeProcess },
  );
  // 模拟 MCP 进程崩溃退出
  fakeProcess.emit('exit', 1, null);
  assert.ok(client.errored, 'exit 应置 errored');
  // 后续 request 经 errored 守卫立即 reject,而非挂满 DEFAULT_REQUEST_TIMEOUT_MS=15s
  const start = Date.now();
  await assert.rejects(client.request('tools/list', {}), /exited/i);
  const elapsed = Date.now() - start;
  assert.ok(elapsed < 1000, `应在 1s 内立即失败,实际 ${elapsed}ms(疑似仍挂 15s 超时)`);
});

// STAB-01:子进程退出后继续写 stdin 触发 EPIPE。Writable 流若无 'error' 监听器,Node 默认 throw
// → uncaughtException → 整个 gateway 进程崩溃,所有 provider 失去 MCP 工具。
// 期望:stdin 'error' 被监听器消化成 rejectAll(单个 MCP 失败),不波及 gateway 进程,并置 errored。

test('stdin error (EPIPE after process exit) does not crash gateway', async () => {
  const fakeProcess = createFakeProcess();
  const client = new StdioMcpClient(
    { serverId: 'crash', sourceProvider: 'claude', config: { command: 'fake', args: [] } },
    { spawnFn: () => fakeProcess },
  );
  const pending = client.request('initialize', { protocolVersion: '2024-11-05' }, 1000);
  // 模拟进程退出后写 stdin 触发 EPIPE:stdin 上 emit 'error'(修复前无监听器 → throw 杀 gateway)
  setImmediate(() => fakeProcess.stdin.emit('error', new Error('write EPIPE')));
  await assert.rejects(pending, /EPIPE/);
  assert.ok(client.errored, 'stdin error 应置 errored(进程已死)');
  client.close();
});
