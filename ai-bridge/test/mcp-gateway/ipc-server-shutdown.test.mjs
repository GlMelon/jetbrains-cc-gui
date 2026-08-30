import test from 'node:test';
import assert from 'node:assert/strict';

import { HealthStore } from '../../mcp-gateway/health-store.js';
import { IpcServer } from '../../mcp-gateway/ipc-server.js';
import { RevisionStore } from '../../mcp-gateway/revision-store.js';

const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

function deferred() {
  /** @type {(value?: unknown) => void} */
  let resolve;
  const promise = new Promise((promiseResolve) => {
    resolve = promiseResolve;
  });
  return { promise, resolve };
}

function createServer(supervisors = new Map(), shutdownDeadlineMs = 5_000) {
  return new IpcServer({
    token: 'test-token',
    revisionStore: new RevisionStore(),
    healthStore: new HealthStore(),
    supervisors,
    startedAt: Date.now(),
    shutdownDeadlineMs,
  });
}

function responseRecorder() {
  return {
    status: 0,
    body: null,
    writeHead(status) {
      this.status = status;
    },
    end(body) {
      this.body = body;
    },
  };
}

test('close is idempotent and waits for supervisor shutdown', async () => {
  const stopping = deferred();
  let stopCalls = 0;
  const server = createServer(new Map([
    ['test:slow', { stop: () => { stopCalls += 1; return stopping.promise; }, tools: [] }],
  ]));

  const first = server.close();
  const second = server.close();
  assert.strictEqual(first, second, '重复 close 应复用同一个 Promise');
  await Promise.resolve();
  assert.equal(stopCalls, 1, '重复 close 不应重复 stop supervisor');

  let settled = false;
  void first.then(() => { settled = true; });
  await sleep(20);
  assert.equal(settled, false, '应等待 supervisor stop 完成');

  stopping.resolve();
  await first;
  assert.equal(settled, true);
});

test('close rejects authenticated requests with 503 while shutting down', async () => {
  const stopping = deferred();
  const server = createServer(new Map([
    ['test:slow', { stop: () => stopping.promise, tools: [] }],
  ]));
  const closing = server.close();
  const response = responseRecorder();

  await server.handle({
    method: 'GET',
    url: '/status',
    headers: { authorization: 'Bearer test-token' },
  }, response);

  assert.equal(response.status, 503);
  assert.deepEqual(JSON.parse(response.body), { error: 'gateway shutting down' });
  stopping.resolve();
  await closing;
});

test('close deadline destroys remaining connections instead of hanging forever', async () => {
  const neverStops = new Promise(() => {});
  const server = createServer(new Map([
    ['test:hung', { stop: () => neverStops, tools: [] }],
  ]), 30);
  let destroyed = false;
  server.connections.add(/** @type {import('node:net').Socket} */ ({
    destroy() { destroyed = true; },
  }));

  const startedAt = Date.now();
  await server.close();
  const elapsed = Date.now() - startedAt;

  assert.ok(elapsed >= 20 && elapsed < 500, `deadline close 应有界完成,实际 ${elapsed}ms`);
  assert.equal(destroyed, true, 'deadline 到达后应销毁残留连接');
  assert.equal(server.connections.size, 0);
});

test('applySnapshot does not publish a revision after shutdown begins', async () => {
  const refreshing = deferred();
  const refreshStarted = deferred();
  const spec = { enabled: true, sourceProvider: 'test', serverId: 'slow' };
  const supervisor = {
    configHash: JSON.stringify(spec),
    tools: [],
    stop() {},
    async refresh() {
      refreshStarted.resolve();
      await refreshing.promise;
      this.tools = [{ name: 'late-tool' }];
      return this.tools;
    },
  };
  const server = createServer(new Map([['test:slow', supervisor]]));

  const applying = server.applySnapshot({ revision: 1, servers: [spec] });
  await refreshStarted.promise;
  const closing = server.close();
  refreshing.resolve();

  await assert.rejects(applying, /gateway shutting down/);
  await closing;
  assert.deepEqual(server.revisionStore.get(1).tools, [], '关闭期间不得写入新 catalog revision');
});