import test from 'node:test';
import assert from 'node:assert/strict';

import { HealthStore } from '../../mcp-gateway/health-store.js';
import { IpcServer } from '../../mcp-gateway/ipc-server.js';
import { RevisionStore } from '../../mcp-gateway/revision-store.js';

const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

function createServer(supervisors) {
  return new IpcServer({
    token: 'test-token',
    revisionStore: new RevisionStore(),
    healthStore: new HealthStore(),
    supervisors,
    startedAt: Date.now(),
  });
}

function snapshot() {
  return {
    revision: 1,
    servers: [
      { enabled: true, sourceProvider: 'test', serverId: 'fast' },
      { enabled: true, sourceProvider: 'test', serverId: 'slow' },
    ],
  };
}

test('applySnapshot starts refreshes in parallel and waits for the slowest one', async () => {
  const started = [];
  let releaseSlow;
  const slowReleased = new Promise((resolve) => {
    releaseSlow = resolve;
  });

  const makeSupervisor = (serverId, refresh) => ({
    configHash: JSON.stringify({ enabled: true, sourceProvider: 'test', serverId }),
    tools: [],
    stop() {},
    refresh,
  });

  const supervisors = new Map([
    [
      'test:fast',
      makeSupervisor('fast', async function refresh() {
        started.push('fast');
        await sleep(10);
        this.tools = [{ name: 'fast-tool' }];
        return this.tools;
      }),
    ],
    [
      'test:slow',
      makeSupervisor('slow', async function refresh() {
        started.push('slow');
        await slowReleased;
        this.tools = [{ name: 'slow-tool' }];
        return this.tools;
      }),
    ],
  ]);

  const server = createServer(supervisors);
  const applyPromise = server.applySnapshot(snapshot());

  // If refreshes were sequential, the slow refresh would not even start until
  // the fast one completed. The implementation starts both before awaiting.
  while (started.length < 2) {
    await sleep(1);
  }
  assert.deepEqual(started.sort(), ['fast', 'slow']);

  let completed = false;
  void applyPromise.then(() => {
    completed = true;
  });
  await sleep(20);
  assert.equal(completed, false, 'applySnapshot must wait for the slowest refresh');

  releaseSlow();
  await applyPromise;
  assert.deepEqual(server.revisionStore.get(1).tools, [
    { name: 'fast-tool' },
    { name: 'slow-tool' },
  ]);
});

test('applySnapshot does not reject when one refresh fails, but still waits for all refreshes', async () => {
  let releaseSlow;
  const slowReleased = new Promise((resolve) => {
    releaseSlow = resolve;
  });
  let slowFinished = false;

  const makeSupervisor = (serverId, refresh) => ({
    configHash: JSON.stringify({ enabled: true, sourceProvider: 'test', serverId }),
    tools: [],
    stop() {},
    refresh,
  });

  const supervisors = new Map([
    [
      'test:fast',
      makeSupervisor('fast', async () => {
        throw new Error('simulated MCP failure');
      }),
    ],
    [
      'test:slow',
      makeSupervisor('slow', async function refresh() {
        await slowReleased;
        slowFinished = true;
        this.tools = [{ name: 'slow-tool' }];
        return this.tools;
      }),
    ],
  ]);

  const server = createServer(supervisors);
  const applyPromise = server.applySnapshot(snapshot());

  let completed = false;
  void applyPromise.then(() => {
    completed = true;
  });
  await sleep(20);
  assert.equal(completed, false, 'Promise.allSettled still waits for the pending refresh');
  assert.equal(slowFinished, false);

  releaseSlow();
  await applyPromise;
  assert.equal(slowFinished, true);
  assert.deepEqual(server.revisionStore.get(1).tools, [{ name: 'slow-tool' }]);
});
