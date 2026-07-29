import test from 'node:test';
import assert from 'node:assert/strict';
import { buildCodexThreadCacheSignature } from '../../../services/codex/codex-utils.js';

// buildCodexThreadCacheSignature 为 codex 线程缓存生成复用键。MCP Gateway 启用后,
// revision 必须进入签名——revision 变(gateway 工具目录变化)必须触发新 codex 实例/thread,
// 避免复用持有过期 gateway 工具集的会话(与 Claude 的 mcpGatewaySchemaRevision 对称)。

test('buildCodexThreadCacheSignature changes when mcpGatewayRevision differs', () => {
  const opts = { baseUrl: 'https://x', apiKey: 'k', env: {} };
  const threadOpts = { model: 'gpt-5.5', sandboxMode: 'workspace-write' };
  const sigA = buildCodexThreadCacheSignature(opts, threadOpts, 5);
  const sigB = buildCodexThreadCacheSignature(opts, threadOpts, 6);
  assert.notEqual(sigA, sigB);
});

test('buildCodexThreadCacheSignature is backward compatible without revision', () => {
  const opts = { baseUrl: 'https://x' };
  const threadOpts = { model: 'gpt-5.5' };
  const sigOmitted = buildCodexThreadCacheSignature(opts, threadOpts);
  const sigNull = buildCodexThreadCacheSignature(opts, threadOpts, null);
  assert.equal(sigOmitted, sigNull);
});

test('buildCodexThreadCacheSignature preserves existing dimensions and includes revision', () => {
  const opts = { baseUrl: 'https://x', apiKey: 'k' };
  const threadOpts = { model: 'gpt-5.5' };
  const sig = buildCodexThreadCacheSignature(opts, threadOpts, 7);
  assert.ok(sig.includes('gpt-5.5'));
  assert.ok(sig.includes('7'));
});
