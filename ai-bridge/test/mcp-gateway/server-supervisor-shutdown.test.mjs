import test from 'node:test';
import assert from 'node:assert/strict';

import { HealthStore } from '../../mcp-gateway/health-store.js';
import { ServerSupervisor } from '../../mcp-gateway/server-supervisor.js';

function deferred() {
  /** @type {() => void} */
  let resolve;
  const promise = new Promise((promiseResolve) => {
    resolve = promiseResolve;
  });
  return { promise, resolve };
}

test('stop is idempotent and waits for the active transport close', async () => {
  const closed = deferred();
  let closeCalls = 0;
  const healthStore = new HealthStore();
  const supervisor = new ServerSupervisor({
    sourceProvider: 'test',
    serverId: 'slow',
    config: { command: 'fake', args: [] },
  }, healthStore);
  supervisor.client = {
    close() {
      closeCalls += 1;
      return closed.promise;
    },
  };

  const first = supervisor.stop();
  const second = supervisor.stop();
  assert.strictEqual(first, second, '重复 stop 应复用同一个 Promise');
  await Promise.resolve();
  assert.equal(closeCalls, 1);
  assert.equal(supervisor.client, null);
  assert.notEqual(healthStore.servers.get('test:slow')?.state, 'STOPPED', 'transport 未退出前不得提前标记 STOPPED');

  closed.resolve();
  await first;
  assert.equal(healthStore.servers.get('test:slow')?.state, 'STOPPED');
});

test('refresh does not create a new client after shutdown starts', async () => {
  const healthStore = new HealthStore();
  const supervisor = new ServerSupervisor({
    sourceProvider: 'test',
    serverId: 'closed',
    config: { command: 'must-not-spawn', args: [] },
  }, healthStore);

  await supervisor.stop();
  const tools = await supervisor.refresh();

  assert.deepEqual(tools, []);
  assert.equal(supervisor.client, null);
  assert.equal(healthStore.servers.get('test:closed')?.state, 'STOPPED');
});