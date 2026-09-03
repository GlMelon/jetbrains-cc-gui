import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';

import { HealthStore } from '../../mcp-gateway/health-store.js';
import { IpcServer } from '../../mcp-gateway/ipc-server.js';
import { RevisionStore } from '../../mcp-gateway/revision-store.js';

// 测试模式对齐 ipc-server-shutdown.test.mjs:不起真实端口,直接 handle(fakeReq, fakeRes)。
// fakeReq 用 EventEmitter 承载 readJson 的 data/end 与 GET 流的 close;fakeRes 自实现
// writeHead/write/end/destroyed/writableEnded,避免真实 socket 在 Windows 下挂住测试进程。

function createServer(supervisors = new Map()) {
  return new IpcServer({
    token: 'test-token',
    revisionStore: new RevisionStore(),
    healthStore: new HealthStore(),
    supervisors,
    startedAt: Date.now(),
    shutdownDeadlineMs: 5_000,
  });
}

function fakeRequest({ method = 'POST', url = '/mcp', body, headers = {} } = {}) {
  const req = new EventEmitter();
  req.method = method;
  req.url = url;
  req.headers = { authorization: 'Bearer test-token', ...headers };
  req.destroy = () => {};
  queueMicrotask(() => {
    if (body !== undefined) {
      req.emit('data', Buffer.from(typeof body === 'string' ? body : JSON.stringify(body)));
    }
    req.emit('end');
  });
  return req;
}

function fakeResponse() {
  const res = new EventEmitter();
  res.status = 0;
  res.headers = {};
  res.body = null;
  res.frames = [];
  res.destroyed = false;
  res.writableEnded = false;
  res.writeHead = (status, headers) => {
    res.status = status;
    Object.assign(res.headers, headers ?? {});
  };
  res.write = (chunk) => {
    res.frames.push(String(chunk));
    return true;
  };
  res.end = (body) => {
    res.body = body ?? null;
    res.writableEnded = true;
  };
  return res;
}

function jsonBody(res) {
  return JSON.parse(res.body);
}

async function initialize(server, protocolVersion = '2025-03-26') {
  const req = fakeRequest({
    body: {
      jsonrpc: '2.0',
      id: 1,
      method: 'initialize',
      params: { protocolVersion, capabilities: {}, clientInfo: { name: 'test', version: '0' } },
    },
  });
  const res = fakeResponse();
  await server.handle(req, res);
  return res;
}

test('initialize creates a session and echoes a supported protocol version', async () => {
  const server = createServer();
  const res = await initialize(server, '2025-03-26');

  assert.equal(res.status, 200);
  assert.equal(typeof res.headers['mcp-session-id'], 'string');
  const body = jsonBody(res);
  assert.equal(body.result.protocolVersion, '2025-03-26');
  assert.equal(body.result.capabilities.tools.listChanged, true);
  assert.equal(server.mcpEndpoint.sessions.size, 1);
  await server.close();
});

test('initialize falls back to the latest protocol version for unknown requests', async () => {
  const server = createServer();
  const res = await initialize(server, '1999-01-01');

  assert.equal(jsonBody(res).result.protocolVersion, '2025-06-18');
  await server.close();
});

test('requests without the gateway token are rejected with 401', async () => {
  const server = createServer();
  const req = fakeRequest({ body: { jsonrpc: '2.0', id: 1, method: 'ping' }, headers: { authorization: '' } });
  req.headers = {};
  const res = fakeResponse();
  await server.handle(req, res);

  assert.equal(res.status, 401);
  await server.close();
});

test('requests are rejected with 503 once shutdown begins', async () => {
  const server = createServer();
  const closing = server.close();
  const res = fakeResponse();
  await server.handle(fakeRequest({ body: { jsonrpc: '2.0', id: 1, method: 'ping' } }), res);

  assert.equal(res.status, 503);
  await closing;
});

test('notifications return 202 with an empty body', async () => {
  const server = createServer();
  const init = await initialize(server);
  const sessionId = init.headers['mcp-session-id'];

  const res = fakeResponse();
  await server.handle(fakeRequest({
    body: { jsonrpc: '2.0', method: 'notifications/initialized' },
    headers: { 'mcp-session-id': sessionId },
  }), res);

  assert.equal(res.status, 202);
  assert.equal(res.body, null);
  await server.close();
});

test('ping and tools/list require and use the session, listing the latest catalog', async () => {
  const server = createServer();
  server.latestRevision = 5;
  server.revisionStore.put(5, { revision: 5, tools: [{ name: 'mcp__claude__dbx__run' }] });
  const sessionId = (await initialize(server)).headers['mcp-session-id'];

  const ping = fakeResponse();
  await server.handle(fakeRequest({
    body: { jsonrpc: '2.0', id: 2, method: 'ping' },
    headers: { 'mcp-session-id': sessionId },
  }), ping);
  assert.deepEqual(jsonBody(ping).result, {});

  const list = fakeResponse();
  await server.handle(fakeRequest({
    body: { jsonrpc: '2.0', id: 3, method: 'tools/list' },
    headers: { 'mcp-session-id': sessionId },
  }), list);
  assert.deepEqual(jsonBody(list).result.tools, [{ name: 'mcp__claude__dbx__run' }]);
  await server.close();
});

test('requests without or with an unknown session id get 400/404', async () => {
  const server = createServer();

  const missing = fakeResponse();
  await server.handle(fakeRequest({ body: { jsonrpc: '2.0', id: 1, method: 'ping' } }), missing);
  assert.equal(missing.status, 400);

  const unknown = fakeResponse();
  await server.handle(fakeRequest({
    body: { jsonrpc: '2.0', id: 1, method: 'ping' },
    headers: { 'mcp-session-id': 'no-such-session' },
  }), unknown);
  assert.equal(unknown.status, 404);
  await server.close();
});

test('tools/call routes to the supervisor and passes the result through', async () => {
  const supervisors = new Map([
    ['claude:dbx', {
      callTool: async (name, args) => ({ content: [{ type: 'text', text: 'ok' }], echo: { name, args } }),
    }],
  ]);
  const server = createServer(supervisors);
  const sessionId = (await initialize(server)).headers['mcp-session-id'];

  const res = fakeResponse();
  await server.handle(fakeRequest({
    body: { jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'mcp__claude__dbx__run', arguments: { a: 1 } } },
    headers: { 'mcp-session-id': sessionId },
  }), res);

  const result = jsonBody(res).result;
  assert.equal(result.content[0].text, 'ok');
  assert.deepEqual(result.echo, { name: 'mcp__claude__dbx__run', args: { a: 1 } });
  await server.close();
});

test('a premature client disconnect aborts an in-flight tools/call', async () => {
  let aborted = false;
  const supervisors = new Map([
    ['claude:dbx', {
      callTool: (name, args, signal) => new Promise((resolve, reject) => {
        signal.addEventListener('abort', () => {
          aborted = true;
          reject(new Error('aborted'));
        });
      }),
    }],
  ]);
  const server = createServer(supervisors);
  const sessionId = (await initialize(server)).headers['mcp-session-id'];

  const req = fakeRequest({
    body: { jsonrpc: '2.0', id: 5, method: 'tools/call', params: { name: 'mcp__claude__dbx__run', arguments: {} } },
    headers: { 'mcp-session-id': sessionId },
  });
  const res = fakeResponse();
  const handling = server.handle(req, res);
  // 等 readJson 与 dispatch 进入 tools/call 后再模拟客户端断连。
  await new Promise((resolve) => setTimeout(resolve, 20));
  res.emit('close');
  await handling;

  assert.equal(aborted, true, '客户端中途断开应 abort 进行中的 tools/call');
  assert.equal(jsonBody(res).error.message, 'aborted');
  await server.close();
});

test('GET opens an SSE stream that receives tools/list_changed broadcasts', async () => {
  const server = createServer();
  const sessionId = (await initialize(server)).headers['mcp-session-id'];

  const req = fakeRequest({ method: 'GET' });
  req.headers['mcp-session-id'] = sessionId;
  const res = fakeResponse();
  await server.handle(req, res);

  assert.equal(res.status, 200);
  assert.match(res.headers['content-type'], /text\/event-stream/);
  const session = server.mcpEndpoint.sessions.get(sessionId);
  assert.equal(session.streams.size, 1);

  server.mcpEndpoint.notifyToolsListChanged();
  assert.ok(res.frames.some((frame) => frame.includes('notifications/tools/list_changed')));

  req.emit('close');
  assert.equal(session.streams.size, 0, '连接断开后流应自动注销');
  await server.close();
});

test('applySnapshot broadcasts tools/list_changed to open SSE streams', async () => {
  const server = createServer();
  const sessionId = (await initialize(server)).headers['mcp-session-id'];
  const req = fakeRequest({ method: 'GET' });
  req.headers['mcp-session-id'] = sessionId;
  const res = fakeResponse();
  await server.handle(req, res);

  await server.applySnapshot({ revision: 1, servers: [] });

  assert.ok(res.frames.some((frame) => frame.includes('notifications/tools/list_changed')));
  await server.close();
});

test('DELETE terminates the session and its streams', async () => {
  const server = createServer();
  const sessionId = (await initialize(server)).headers['mcp-session-id'];
  const sseReq = fakeRequest({ method: 'GET' });
  sseReq.headers['mcp-session-id'] = sessionId;
  const sseRes = fakeResponse();
  await server.handle(sseReq, sseRes);

  const del = fakeResponse();
  const delReq = fakeRequest({ method: 'DELETE' });
  delReq.headers['mcp-session-id'] = sessionId;
  await server.handle(delReq, del);

  assert.equal(del.status, 200);
  assert.equal(sseRes.writableEnded, true, 'DELETE 应结束该 session 的 SSE 流');
  assert.equal(server.mcpEndpoint.sessions.size, 0);

  const after = fakeResponse();
  await server.handle(fakeRequest({
    body: { jsonrpc: '2.0', id: 2, method: 'ping' },
    headers: { 'mcp-session-id': sessionId },
  }), after);
  assert.equal(after.status, 404);
  await server.close();
});

test('sessions are bounded by an LRU cap', async () => {
  const server = createServer();
  for (let i = 0; i < 65; i += 1) {
    await initialize(server);
  }
  assert.equal(server.mcpEndpoint.sessions.size, 64, '超出容量应逐出最旧 session');
  await server.close();
});

test('endpoint close ends all SSE streams and rejects further traffic', async () => {
  const server = createServer();
  const sessionId = (await initialize(server)).headers['mcp-session-id'];
  const sseReq = fakeRequest({ method: 'GET' });
  sseReq.headers['mcp-session-id'] = sessionId;
  const sseRes = fakeResponse();
  await server.handle(sseReq, sseRes);

  await server.close();

  assert.equal(sseRes.writableEnded, true, 'close 应先给 SSE 流干净 EOF');
  assert.equal(server.mcpEndpoint.sessions.size, 0);
});

test('batch requests return a batch response', async () => {
  const server = createServer();
  const sessionId = (await initialize(server)).headers['mcp-session-id'];

  const res = fakeResponse();
  await server.handle(fakeRequest({
    body: [
      { jsonrpc: '2.0', id: 10, method: 'ping' },
      { jsonrpc: '2.0', id: 11, method: 'nope/unknown' },
    ],
    headers: { 'mcp-session-id': sessionId },
  }), res);

  assert.equal(res.status, 200);
  const body = jsonBody(res);
  assert.equal(body.length, 2);
  assert.deepEqual(body[0].result, {});
  assert.equal(body[1].error.code, -32601);
  await server.close();
});
