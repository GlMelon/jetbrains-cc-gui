// revision-store.test.mjs — RevisionStore 淘汰回退的回归保护。
//
// 修复前:精确 revision 被 MAX_REVISIONS(20)淘汰后,get(旧 revision) 返回占位空快照
// {tools: []}——长会话(钉在启动时的 revision)经历 20+ 次配置推送后 tools/list 静默变空,
// MCP 工具全部消失且无任何信号。
// 修复后:精确版本未命中且 store 非空时,回退到现存最旧快照(与请求版本最接近),
// revision 字段保持实际版本,供 ipc-server / runToolsList 比对并打 [melon-gateway-stale] 标记。

import test from 'node:test';
import assert from 'node:assert/strict';

import { RevisionStore } from '../../mcp-gateway/revision-store.js';

test('get returns the exact revision when present', () => {
  const store = new RevisionStore();
  store.put(1, { revision: 1, tools: [{ name: 'a' }] });
  store.put(2, { revision: 2, tools: [{ name: 'b' }] });
  const snapshot = store.get(1);
  assert.equal(snapshot.revision, 1);
  assert.deepEqual(snapshot.tools, [{ name: 'a' }]);
});

test('get falls back to the oldest retained snapshot when the exact revision was evicted', () => {
  const store = new RevisionStore(3);
  // 推入 5 个版本,容量 3:revision 1、2 被淘汰,留存 3/4/5
  for (let revision = 1; revision <= 5; revision += 1) {
    store.put(revision, { revision, tools: [{ name: `tool-r${revision}` }] });
  }
  const snapshot = store.get(1);
  assert.equal(snapshot.revision, 3, '未命中应回退到现存最旧快照(revision 3),而非空占位');
  assert.deepEqual(snapshot.tools, [{ name: 'tool-r3' }], '回退快照必须带真实工具列表');
});

test('get returns placeholder empty snapshot only when the store is entirely empty', () => {
  const store = new RevisionStore();
  const snapshot = store.get(7);
  assert.equal(snapshot.revision, 7);
  assert.deepEqual(snapshot.tools, []);
});

test('get without revision returns the latest snapshot', () => {
  const store = new RevisionStore();
  store.put(1, { revision: 1, tools: [{ name: 'a' }] });
  store.put(2, { revision: 2, tools: [{ name: 'b' }] });
  const snapshot = store.get();
  assert.equal(snapshot.revision, 2);
});
