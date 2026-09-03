// ipc-server-selective-refresh.test.mjs — applySnapshot 选择性刷新的回归保护。
//
// 修复前:applySnapshot 对全部 supervisor 无差别 refresh()——即使配置未变且健康,也会对每个
// server 重跑 listTools(http 型 server 是真实网络往返),全部阻塞 POST /snapshot 响应,拉长
// Java 侧 postMs、挤压 buildCliConfig 的 2s 发送预算(易降级直连)。
// 修复后只刷:① 新建/替换(configHash 变化)② 不健康 ③ client 已死 ④ toolsStale(list_changed)。
// 健康且未变的直接用缓存 tools。

import test from 'node:test';
import assert from 'node:assert/strict';

import { HealthStore } from '../../mcp-gateway/health-store.js';
import { IpcServer } from '../../mcp-gateway/ipc-server.js';
import { RevisionStore } from '../../mcp-gateway/revision-store.js';

function createServer(supervisors) {
  return new IpcServer({
    token: 'test-token',
    revisionStore: new RevisionStore(),
    healthStore: new HealthStore(),
    supervisors,
    startedAt: Date.now(),
  });
}

function specOf(serverId, extra) {
  return { enabled: true, sourceProvider: 'test', serverId, ...extra };
}

function makeSupervisor(serverId, { healthy = true, toolsStale = false, dead = false } = {}) {
  const calls = { refresh: 0, stop: 0 };
  const supervisor = {
    configHash: JSON.stringify(specOf(serverId)),
    tools: [{ name: `tool-${serverId}` }],
    healthy,
    toolsStale,
    isClientDead: () => dead,
    refresh() {
      calls.refresh += 1;
      this.healthy = true;
      this.toolsStale = false;
      return Promise.resolve(this.tools);
    },
    stop() {
      calls.stop += 1;
    },
    calls,
  };
  return supervisor;
}

test('applySnapshot skips refresh for unchanged healthy supervisors', async () => {
  const fast = makeSupervisor('fast');
  const supervisors = new Map([['test:fast', fast]]);
  const server = createServer(supervisors);

  await server.applySnapshot({ revision: 1, servers: [specOf('fast')] });
  assert.equal(fast.calls.refresh, 0, '健康且 configHash 未变的 supervisor 不应重刷');
  assert.deepEqual(server.revisionStore.get(1).tools, [{ name: 'tool-fast' }],
    '跳过重刷时 catalog 仍用缓存 tools');
});

test('applySnapshot replaces changed-config supervisors and refreshes the new instance', async () => {
  const stale = makeSupervisor('srv');
  stale.configHash = '{"outdated":true}';
  const supervisors = new Map([['test:srv', stale]]);
  const server = createServer(supervisors);

  await server.applySnapshot({ revision: 1, servers: [specOf('srv')] });

  assert.equal(stale.calls.stop, 1, 'configHash 变化必须 stop 旧 supervisor');
  const replacement = supervisors.get('test:srv');
  assert.notEqual(replacement, stale, 'configHash 变化必须替换为新 supervisor 实例');
  // 新实例是真实 ServerSupervisor:refresh 被调用过(spec 无 command → createMcpClient 抛错
  // → BACKOFF)。健康记录证明 refresh 确实执行,未被选择性刷新跳过。
  const entry = server.healthStore.servers.get('test:srv');
  assert.ok(entry, '新 supervisor 必须有健康记录');
  assert.equal(entry.state, 'BACKOFF', 'refresh 已执行但因 spec 无 command 失败 → BACKOFF');
});

test('applySnapshot refreshes unhealthy supervisors (backoff retry path preserved)', async () => {
  const broken = makeSupervisor('broken', { healthy: false });
  const supervisors = new Map([['test:broken', broken]]);
  const server = createServer(supervisors);

  await server.applySnapshot({ revision: 1, servers: [specOf('broken')] });
  assert.equal(broken.calls.refresh, 1, '不健康 supervisor 必须重刷(退避重试语义保留)');
});

test('applySnapshot refreshes supervisors with dead clients', async () => {
  const died = makeSupervisor('died', { healthy: true, dead: true });
  const supervisors = new Map([['test:died', died]]);
  const server = createServer(supervisors);

  await server.applySnapshot({ revision: 1, servers: [specOf('died')] });
  assert.equal(died.calls.refresh, 1, 'client 已死的 supervisor 必须重刷以重建连接');
});

test('applySnapshot refreshes supervisors marked toolsStale (list_changed notification)', async () => {
  const changed = makeSupervisor('changed', { healthy: true, toolsStale: true });
  const supervisors = new Map([['test:changed', changed]]);
  const server = createServer(supervisors);

  await server.applySnapshot({ revision: 1, servers: [specOf('changed')] });
  assert.equal(changed.calls.refresh, 1, '收到 tools/list_changed 的 supervisor 必须重刷');
  assert.equal(changed.toolsStale, false, 'refresh 成功后 toolsStale 应清零');
});
