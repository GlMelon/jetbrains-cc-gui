import { test } from 'node:test';
import assert from 'node:assert/strict';
import { HttpMcpClient } from './http-client.js';

// 修复③:gateway http client request 必须有超时(AbortController),否则慢/挂的 HTTP MCP
// server 会无限期等待,拖垮整个 gateway catalog refresh。

test('request rejects with timeout when fetch never settles', async () => {
  const client = new HttpMcpClient({ serverId: 'slow', config: { url: 'http://localhost/x' } });
  const originalFetch = globalThis.fetch;
  // fake fetch:接受 signal,abort 时 reject AbortError(模拟真实 fetch 的 abort 行为)
  globalThis.fetch = (_url, options) => new Promise((_resolve, reject) => {
    const signal = options?.signal;
    if (!signal) return;
    if (signal.aborted) {
      reject(Object.assign(new Error('aborted'), { name: 'AbortError' }));
      return;
    }
    signal.addEventListener('abort',
      () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' })));
  });
  try {
    await assert.rejects(client.request('tools/list', {}, 80), /timeout/i);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('request resolves when fetch responds before timeout', async () => {
  const client = new HttpMcpClient({ serverId: 'ok', config: { url: 'http://localhost/x' } });
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => ({
    ok: true,
    json: async () => ({ jsonrpc: '2.0', id: 1, result: { tools: [] } }),
  });
  try {
    const result = await client.request('tools/list', {}, 2000);
    assert.ok(Array.isArray(result.tools));
    assert.strictEqual(result.tools.length, 0);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
