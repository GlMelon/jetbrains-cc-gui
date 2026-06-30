import test from 'node:test';
import assert from 'node:assert/strict';
import { buildRuntimeSignature } from './runtime-lifecycle.js';

// MCP Gateway SDK 维度:启用 Gateway 时,每轮固定一个 schema revision(配置变更递增)。
// revision 必须进入 runtime 缓存签名,否则配置变更后仍复用旧 runtime,SDK 不会重建
// stdio 聚合 server。维度名 mcpGatewaySchemaRevision 与 Java/Node binding 字段一致,
// 空字符串表示未启用 Gateway(与既有行为等价)。

test('buildRuntimeSignature: 包含传入的 mcpGatewaySchemaRevision', () => {
  const sig = buildRuntimeSignature({ cwd: '/p' }, '', false, 'e1', null, 'rev:5');
  const material = JSON.parse(sig);
  assert.strictEqual(material.mcpGatewaySchemaRevision, 'rev:5');
});

test('buildRuntimeSignature: 省略时 mcpGatewaySchemaRevision 为空字符串', () => {
  const sig = buildRuntimeSignature({ cwd: '/p' }, '', false, 'e1', null);
  const material = JSON.parse(sig);
  assert.strictEqual(material.mcpGatewaySchemaRevision, '');
});

test('buildRuntimeSignature: 不同 gateway revision 产生不同签名', () => {
  const a = buildRuntimeSignature({ cwd: '/p' }, '', false, 'e1', null, 'rev:5');
  const b = buildRuntimeSignature({ cwd: '/p' }, '', false, 'e1', null, 'rev:6');
  assert.notStrictEqual(a, b);
});
