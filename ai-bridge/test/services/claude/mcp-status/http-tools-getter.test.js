import assert from 'node:assert/strict';
import test from 'node:test';

import { getHttpServerTools } from '../../../../services/claude/mcp-status/http-tools-getter.js';

function jsonRpcResponse(id, payload, sessionId) {
  const headers = { 'content-type': 'application/json' };
  if (sessionId) headers['Mcp-Session-Id'] = sessionId;
  return new Response(JSON.stringify({ jsonrpc: '2.0', id, ...payload }), { headers });
}

test('getHttpServerTools repeats the full handshake after session invalidation', async (t) => {
  const originalFetch = globalThis.fetch;
  const requests = [];
  const responses = [
    jsonRpcResponse(1, { result: { protocolVersion: '2024-11-05' } }, 'session-a'),
    new Response('', { status: 202 }),
    jsonRpcResponse(2, { error: { code: -32600, message: 'Invalid session' } }),
    jsonRpcResponse(3, { result: { protocolVersion: '2024-11-05' } }, 'session-b'),
    new Response('', { status: 202 }),
    jsonRpcResponse(4, { result: { tools: [{ name: 'search' }] } })
  ];

  globalThis.fetch = async (_url, options) => {
    requests.push({
      body: JSON.parse(options.body),
      sessionId: options.headers['Mcp-Session-Id'] || null
    });
    const response = responses.shift();
    assert.ok(response, 'unexpected fetch call');
    return response;
  };
  t.after(() => { globalThis.fetch = originalFetch; });

  const result = await getHttpServerTools('test-server', {
    type: 'streamable-http',
    url: 'http://127.0.0.1:3000/mcp'
  });

  assert.equal(result.error, null);
  assert.deepEqual(result.tools, [{ name: 'search' }]);
  assert.deepEqual(requests.map(({ body }) => body.method), [
    'initialize',
    'notifications/initialized',
    'tools/list',
    'initialize',
    'notifications/initialized',
    'tools/list'
  ]);
  assert.deepEqual(requests.map(({ sessionId }) => sessionId), [
    null,
    'session-a',
    'session-a',
    null,
    'session-b',
    'session-b'
  ]);
});

test('getHttpServerTools cancels a non-success response body', async (t) => {
  const originalFetch = globalThis.fetch;
  let cancelled = false;

  globalThis.fetch = async () => new Response(new ReadableStream({
    cancel() {
      cancelled = true;
    }
  }), {
    status: 503,
    statusText: 'Unavailable'
  });
  t.after(() => { globalThis.fetch = originalFetch; });

  const result = await getHttpServerTools('test-server', {
    type: 'streamable-http',
    url: 'http://127.0.0.1:3000/mcp'
  });

  assert.match(result.error, /HTTP 503/);
  assert.equal(cancelled, true);
});
