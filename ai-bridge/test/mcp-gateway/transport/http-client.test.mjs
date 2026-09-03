import { test } from 'node:test';
import assert from 'node:assert/strict';
import { HttpMcpClient } from '../../../mcp-gateway/transport/http-client.js';

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

// 取消传播(总则六):gateway 侧客户端断开 → 外部 AbortSignal abort → fetch 中止,
// 报 cancelled 而非 timeout(与超时路径区分,便于上层识别取消语义)。

test('request rejects with cancelled (not timeout) when external signal aborts mid-flight', async () => {
  const client = new HttpMcpClient({ serverId: 'slow', config: { url: 'http://localhost/x' } });
  const originalFetch = globalThis.fetch;
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
    const controller = new AbortController();
    const pending = client.request('tools/call', { name: 'slow_tool' }, 60_000, controller.signal);
    setImmediate(() => controller.abort());
    await assert.rejects(pending, /cancelled/i);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('request with pre-aborted signal rejects immediately without calling fetch', async () => {
  const client = new HttpMcpClient({ serverId: 'pre', config: { url: 'http://localhost/x' } });
  const originalFetch = globalThis.fetch;
  let fetchCalled = 0;
  globalThis.fetch = async () => { fetchCalled += 1; return { ok: true, json: async () => ({}) }; };
  try {
    const controller = new AbortController();
    controller.abort();
    await assert.rejects(client.request('tools/call', { name: 'x' }, 1000, controller.signal), /cancelled/i);
    assert.equal(fetchCalled, 0, '已中止的请求不应发 fetch');
  } finally {
    globalThis.fetch = originalFetch;
  }
});
