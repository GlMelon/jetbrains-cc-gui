import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';

import { HealthStore } from '../../mcp-gateway/health-store.js';
import { IpcServer } from '../../mcp-gateway/ipc-server.js';
import { RevisionStore } from '../../mcp-gateway/revision-store.js';
import { ServerSupervisor } from '../../mcp-gateway/server-supervisor.js';
import { installGatewayShutdown } from '../../mcp-gateway/shutdown-controller.js';
import { StdioMcpClient } from '../../mcp-gateway/transport/stdio-client.js';

function deferred() {
  /** @type {() => void} */
  let resolve;
  const promise = new Promise((promiseResolve) => {
    resolve = promiseResolve;
  });
  return { promise, resolve };
}

function fakeProcess() {
  const processRef = new EventEmitter();
  processRef.exitCodes = [];
  processRef.exit = (code) => {
    processRef.exitCodes.push(code);
  };
  return processRef;
}

test('SIGTERM shutdown is idempotent and waits before exiting', async () => {
  const closing = deferred();
  const processRef = fakeProcess();
  let closeCalls = 0;
  let removeCalls = 0;
  const shutdown = installGatewayShutdown({
    ipc: { close: () => { closeCalls += 1; return closing.promise; } },
    stateFile: 'test-state.json',
    processRef,
    removeState: () => { removeCalls += 1; },
    logger: { error() {} },
  });

  processRef.emit('SIGTERM');
  processRef.emit('SIGTERM');
  assert.equal(closeCalls, 1, '重复信号不得重复启动 shutdown');
  assert.deepEqual(processRef.exitCodes, [], '资源关闭前不得提前退出');

  closing.resolve();
  await shutdown('SIGTERM');
  assert.deepEqual(processRef.exitCodes, [0]);
  assert.equal(removeCalls, 1);
});

test('SIGTERM shutdown leaves no stdio MCP child process running', async () => {
  const healthStore = new HealthStore();
  const supervisor = new ServerSupervisor({
    sourceProvider: 'test',
    serverId: 'signal-child',
    config: { command: process.execPath, args: ['-e', 'setInterval(() => {}, 1_000)'] },
  }, healthStore);
  const client = new StdioMcpClient(supervisor.spec);
  supervisor.client = client;
  const ipc = new IpcServer({
    token: 'test-token',
    revisionStore: new RevisionStore(),
    healthStore,
    supervisors: new Map([['test:signal-child', supervisor]]),
    startedAt: Date.now(),
    shutdownDeadlineMs: 2_000,
  });
  const processRef = fakeProcess();
  const shutdown = installGatewayShutdown({
    ipc,
    stateFile: 'test-state.json',
    processRef,
    removeState() {},
    logger: { error() {} },
  });

  try {
    processRef.emit('SIGTERM');
    await shutdown('SIGTERM');
    assert.ok(
      client.process.exitCode != null || client.process.signalCode != null,
      'SIGTERM shutdown 完成后 MCP 子进程必须已退出',
    );
    assert.deepEqual(processRef.exitCodes, [0]);
  } finally {
    await client.close();
  }
});