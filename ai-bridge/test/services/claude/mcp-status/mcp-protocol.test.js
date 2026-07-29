import assert from 'node:assert/strict';
import test from 'node:test';

import { readJsonRpcResponse } from '../../../../services/claude/mcp-status/mcp-protocol.js';

function streamingResponse(chunks, contentType = 'text/event-stream') {
  const encoder = new TextEncoder();
  return new Response(new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
      controller.close();
    }
  }), { headers: { 'content-type': contentType } });
}

test('readJsonRpcResponse incrementally selects the matching SSE request id', async () => {
  const response = streamingResponse([
    'data: {"jsonrpc":"2.0","method":"notifications/progress"}\n\n',
    'event: message\ndata: {"jsonrpc":"2.0","id":8,"result":{"ignored":true}}\n\n',
    'data: {"jsonrpc":"2.0","id":9,"result":{"ok":true}}\n\n'
  ]);

  const data = await readJsonRpcResponse(
    response,
    9,
    new AbortController().signal,
    'test response'
  );

  assert.deepEqual(data, { jsonrpc: '2.0', id: 9, result: { ok: true } });
});

test('readJsonRpcResponse rejects an unexpected JSON response id', async () => {
  const response = streamingResponse(
    ['{"jsonrpc":"2.0","id":3,"result":{}}'],
    'application/json'
  );

  await assert.rejects(
    readJsonRpcResponse(response, 4, new AbortController().signal, 'test response'),
    /unexpected JSON-RPC id/
  );
});

test('readJsonRpcResponse enforces a bounded JSON response body', async () => {
  const response = streamingResponse(
    ['x'.repeat(1024 * 1024 + 1)],
    'application/json'
  );

  await assert.rejects(
    readJsonRpcResponse(response, 1, new AbortController().signal, 'test response'),
    /exceeded maximum size/
  );
});
