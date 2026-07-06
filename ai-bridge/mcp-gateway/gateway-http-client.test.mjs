// gateway-http-client.test.mjs — GatewayHttpClient 超时 + runToolsList 降级测试。
//
// 范式对称 transport/http-client.test.mjs:fake fetch 注入 + AbortController 超时验证,
// 避免真实 HTTP server 让 test 进程不退出(Windows 下尤其)。runToolsList 用 fake httpClient
// 验证降级语义(空工具 + stderr 标记)与成功路径。
//
// 30s 根因回归保护:request 必须有超时(原 http.request 无超时挂死 TCP),且 connect error
// (gateway 进程已死)必须立即 reject 不等满超时。

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { GatewayHttpClient, runToolsList } from './gateway-http-client.js';

// 辅助:用 fake fetch 替换 globalThis.fetch,测试后恢复。fake 接受 signal,abort 时 reject AbortError。
function withFakeFetch(fake, fn) {
  const original = globalThis.fetch;
  globalThis.fetch = fake;
  return Promise.resolve(fn()).finally(() => { globalThis.fetch = original; });
}

// ════════════════════════════════════════════════════════════════════════════
// GatewayHttpClient 超时(30s 根因核心修复)
// ════════════════════════════════════════════════════════════════════════════

test('request: fetch 永不 settle 时按 timeoutMs reject(转 timeout 错误)', async () => {
  const client = new GatewayHttpClient({ port: 9999, token: 't', timeoutMs: 80 });
  await withFakeFetch((_url, options) => new Promise((_resolve, reject) => {
    const signal = options?.signal;
    if (!signal) return;
    if (signal.aborted) {
      reject(Object.assign(new Error('aborted'), { name: 'AbortError' }));
      return;
    }
    signal.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' })));
  }), async () => {
    await assert.rejects(client.get('/runtime/tools/list'), /timeout/i);
  });
});

test('request: connect error(gateway 已死)立即 reject,不等满 timeoutMs', async () => {
  // gateway 进程崩溃场景:fetch 抛 connect error(非超时),应立即抛而非等 5s。
  // 原实现 http.request 无超时 → 挂死;现 fetch connect error 路径必须快失败。
  const client = new GatewayHttpClient({ port: 9999, token: 't', timeoutMs: 5000 });
  const start = Date.now();
  await withFakeFetch(async () => {
    throw Object.assign(new Error('connect ECONNREFUSED 127.0.0.1:9999'), { code: 'ECONNREFUSED' });
  }, async () => {
    await assert.rejects(client.get('/runtime/tools/list'), /ECONNREFUSED/);
  });
  const elapsed = Date.now() - start;
  assert.ok(elapsed < 1000, `connect error 应在 <1s 内 reject,实际 ${elapsed}ms(不应等满 5s timeout)`);
});

// ════════════════════════════════════════════════════════════════════════════
// 成功 / 非 2xx / 请求格式
// ════════════════════════════════════════════════════════════════════════════

test('request: 2xx 返回解析后的 JSON', async () => {
  const client = new GatewayHttpClient({ port: 9999, token: 't' });
  await withFakeFetch(async (url, options) => {
    assert.match(url, /http:\/\/127\.0\.0\.1:9999\/runtime\/tools\/list/);
    assert.equal(options.headers.authorization, 'Bearer t');
    return { ok: true, status: 200, text: async () => JSON.stringify({ tools: [{ name: 'x' }] }) };
  }, async () => {
    const result = await client.get('/runtime/tools/list');
    assert.equal(result.tools.length, 1);
    assert.equal(result.tools[0].name, 'x');
  });
});

test('request: 非 2xx status reject(Gateway HTTP <status>)', async () => {
  const client = new GatewayHttpClient({ port: 9999, token: 't' });
  await withFakeFetch(async () => ({ ok: false, status: 500, text: async () => 'err' }), async () => {
    await assert.rejects(client.get('/x'), /Gateway HTTP 500/);
  });
});

test('post: 发 JSON body + content-type + authorization', async () => {
  const client = new GatewayHttpClient({ port: 9999, token: 'tok' });
  await withFakeFetch(async (url, options) => {
    assert.equal(options.method, 'POST');
    assert.equal(options.headers['content-type'], 'application/json');
    assert.equal(options.headers.authorization, 'Bearer tok');
    const body = JSON.parse(options.body);
    assert.equal(body.name, 'my-tool');
    return { ok: true, status: 200, text: async () => JSON.stringify({ ok: true }) };
  }, async () => {
    const result = await client.post('/runtime/tools/call', { name: 'my-tool', arguments: {} });
    assert.equal(result.ok, true);
  });
});

test('get: GET 请求不带 body、不带 content-type', async () => {
  const client = new GatewayHttpClient({ port: 9999, token: 't' });
  await withFakeFetch(async (url, options) => {
    assert.equal(options.method, 'GET');
    assert.ok(options.body == null, 'GET 不应有 body(null/undefined)');
    assert.equal(options.headers['content-type'], undefined, 'GET 不应有 content-type');
    assert.equal(options.headers.authorization, 'Bearer t');
    return { ok: true, status: 200, text: async () => JSON.stringify({ ok: true }) };
  }, async () => {
    await client.get('/runtime/tools/list');
  });
});

// ════════════════════════════════════════════════════════════════════════════
// runToolsList 降级(对话继续,不再挂 30s)
// ════════════════════════════════════════════════════════════════════════════

test('runToolsList: gateway 不可达 → 降级返 {tools:[]} + 写 [melon-gateway-down] stderr', async () => {
  const written = [];
  const stderrWritten = [];
  const output = { write: (c) => written.push(c) };
  const stderr = { write: (c) => stderrWritten.push(c) };
  const httpClient = { get: async () => { throw new Error('connect ECONNREFUSED'); } };
  const handled = await runToolsList({
    httpClient,
    revision: 1,
    message: { jsonrpc: '2.0', id: 7, method: 'tools/list' },
    output,
    stderr,
  });
  assert.equal(handled, true, 'tools/list 应被处理');
  const msg = JSON.parse(written[0].toString('utf8').trimEnd());
  assert.equal(msg.id, 7);
  assert.deepEqual(msg.result, { tools: [] }, '降级必须返空工具列表(非 error)');
  assert.equal(msg.error, undefined, '降级路径绝不返 JSON-RPC error');
  assert.ok(stderrWritten.join('').includes('[melon-gateway-down]'), 'stderr 须含降级标记供 Java toast');
});

test('runToolsList: gateway 可达 → 返真实 tools', async () => {
  const written = [];
  const output = { write: (c) => written.push(c) };
  const httpClient = { get: async () => ({ tools: [{ name: 'real-tool', description: 'd' }] }) };
  const handled = await runToolsList({
    httpClient,
    revision: 1,
    message: { jsonrpc: '2.0', id: 8, method: 'tools/list' },
    output,
    stderr: { write: () => {} },
  });
  assert.equal(handled, true);
  const msg = JSON.parse(written[0].toString('utf8').trimEnd());
  assert.equal(msg.result.tools[0].name, 'real-tool');
});

test('runToolsList: 非 tools/list 方法返回 false(调用方走基类)', async () => {
  const httpClient = { get: async () => { throw new Error('should not call'); } };
  const handled = await runToolsList({
    httpClient,
    revision: 1,
    message: { jsonrpc: '2.0', id: 9, method: 'initialize' },
    output: { write: () => {} },
    stderr: { write: () => {} },
  });
  assert.equal(handled, false, '非 tools/list 不应被处理');
});
