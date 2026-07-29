import test from 'node:test';
import assert from 'node:assert/strict';

import { sendMessage, splitModelForSdk } from '../../../services/opencode/message-service.js';

/**
 * §15.7 B2/B11/B16:message-service.js 编排层测试。
 * 用 mock client 注入(clientFactory),不依赖真实 opencode serve。
 * 覆盖:会话续接/新建、模型拆分、SSE 流消费→NDJSON、prompt resolve、baseUrl 透传。
 */

/** 构造一个受控 mock client,event.stream 吐出给定事件序列后关闭。 */
function makeMockClient({ events = [], createdSessionId = 'ses_mock_1', promptError = null } = {}) {
    const calls = { create: [], prompt: [], subscribe: 0 };
    async function* stream() {
        for (const e of events) yield e;
    }
    const client = {
        session: {
            create: async (opts) => { calls.create.push(opts); return { data: { id: createdSessionId } }; },
            prompt: async (opts) => {
                calls.prompt.push(opts);
                if (promptError) throw promptError;
                return { data: { id: 'msg_x', role: 'assistant' } };
            }
        },
        event: {
            subscribe: async () => { calls.subscribe++; return { stream: stream() }; }
        }
    };
    return { client, calls };
}

test('creates a new session when threadId is empty and surfaces session_id', async () => {
    const { client, calls } = makeMockClient({ events: [
        { type: 'server.connected', properties: {} },
        { type: 'message.part.updated', properties: { sessionID: 'ses_mock_1', part: { type: 'text', text: 'hi' } } },
        { type: 'session.idle', properties: { sessionID: 'ses_mock_1' } }
    ] });
    const written = [];
    await sendMessage({
        message: 'hello', threadId: '', cwd: '/tmp', model: 'opencode/mimo-v2.5-free'
    }, { clientFactory: async () => client, write: (o) => written.push(o) });

    assert.equal(calls.create.length, 1, 'session.create called once');
    assert.equal(calls.create[0].body.title, '/tmp', 'session title = cwd');
    // directory 让 getSessionList 的项目路径过滤命中(否则新会话不在历史列表)
    assert.equal(calls.create[0].body.directory, '/tmp', 'session directory = cwd');
    const sidEvents = written.filter((o) => o.type === 'session_id');
    assert.equal(sidEvents.length, 1);
    assert.equal(sidEvents[0].session_id, 'ses_mock_1');
});

test('reuses existing session when threadId provided (no session.create)', async () => {
    const { client, calls } = makeMockClient({ events: [
        { type: 'server.connected', properties: {} },
        { type: 'session.idle', properties: { sessionID: 'ses_existing' } }
    ] });
    const written = [];
    await sendMessage({
        message: 'more', threadId: 'ses_existing', cwd: '/tmp', model: 'opencode/mimo-v2.5-free'
    }, { clientFactory: async () => client, write: (o) => written.push(o) });

    assert.equal(calls.create.length, 0, 'must NOT create a new session');
    assert.equal(calls.prompt.length, 1);
    assert.equal(calls.prompt[0].path.id, 'ses_existing', 'prompt targets existing session');
});

test('splits provider/model into providerID/modelID on prompt body', async () => {
    const { client, calls } = makeMockClient({ events: [
        { type: 'server.connected', properties: {} },
        { type: 'session.idle', properties: { sessionID: 'ses_mock_1' } }
    ] });
    await sendMessage({
        message: 'x', threadId: '', cwd: '/tmp', model: 'anthropic/claude-3-5-sonnet'
    }, { clientFactory: async () => client, write: () => {} });

    const body = calls.prompt[0].body;
    assert.deepEqual(body.model, { providerID: 'anthropic', modelID: 'claude-3-5-sonnet' });
});

test('passes text message as a text part', async () => {
    const { client, calls } = makeMockClient({ events: [
        { type: 'server.connected', properties: {} },
        { type: 'session.idle', properties: { sessionID: 'ses_mock_1' } }
    ] });
    await sendMessage({
        message: 'do the thing', threadId: '', cwd: '/tmp', model: 'opencode/mimo-v2.5-free'
    }, { clientFactory: async () => client, write: () => {} });

    const parts = calls.prompt[0].body.parts;
    assert.ok(parts.some((p) => p.type === 'text' && p.text === 'do the thing'));
});

test('maps SSE events to NDJSON stream lifecycle', async () => {
    const { client } = makeMockClient({ events: [
        { type: 'server.connected', properties: {} },
        { type: 'message.part.updated', properties: { sessionID: 'ses_mock_1', part: { type: 'text', text: 'answer' } } },
        { type: 'message.updated', properties: { sessionID: 'ses_mock_1', info: { role: 'assistant', tokens: { total: 10, input: 4, output: 6, cache: { read: 0, write: 0 } } } } },
        { type: 'session.idle', properties: { sessionID: 'ses_mock_1' } }
    ] });
    const written = [];
    await sendMessage({
        message: 'q', threadId: '', cwd: '/tmp', model: 'opencode/mimo-v2.5-free'
    }, { clientFactory: async () => client, write: (o) => written.push(o) });

    const types = written.map((o) => o.type);
    assert.ok(types.includes('stream_start'));
    assert.ok(types.includes('content_delta'));
    assert.ok(types.includes('usage'));
    assert.ok(types.includes('stream_end'));
    assert.ok(types.includes('message_end'));
    // 顺序:stream_start 在 content_delta 之前;stream_end 在最后段
    assert.ok(types.indexOf('stream_start') < types.indexOf('content_delta'));
    assert.equal(types.lastIndexOf('stream_end'), types.length - 2, 'stream_end near end (before message_end)');
});

test('emits error NDJSON and exits when prompt throws', async () => {
    const { client } = makeMockClient({
        events: [{ type: 'server.connected', properties: {} }],
        promptError: new Error('auth failed')
    });
    const written = [];
    // 不应抛(错误经 NDJSON 下发)
    await sendMessage({
        message: 'q', threadId: '', cwd: '/tmp', model: 'opencode/mimo-v2.5-free'
    }, { clientFactory: async () => client, write: (o) => written.push(o) });

    const errs = written.filter((o) => o.type === 'error');
    assert.ok(errs.length >= 1);
    assert.ok(errs[0].message.includes('auth failed') || errs[0].message.includes('OpenCode'), 'error message carried: ' + errs[0].message);
});

test('prompt rejection terminates even when SSE never ends and aborts both requests', async () => {
    let subscribeSignal;
    let promptSignal;
    async function* endlessStream() {
        yield { type: 'server.connected', properties: {} };
        await new Promise(() => {});
    }
    const client = {
        session: {
            create: async () => ({ data: { id: 'ses_hanging' } }),
            prompt: async (opts) => {
                promptSignal = opts.signal;
                throw new Error('prompt rejected');
            }
        },
        event: {
            subscribe: async (opts) => {
                subscribeSignal = opts.signal;
                return { stream: endlessStream() };
            }
        }
    };
    const written = [];

    await sendMessage({ message: 'q', cwd: '/tmp', model: 'opencode/test' }, {
        clientFactory: async () => client,
        write: (event) => written.push(event),
        requestTimeoutMs: 1000
    });

    assert.equal(subscribeSignal, promptSignal, 'SSE and prompt share one cancellation signal');
    assert.equal(subscribeSignal.aborted, true, 'request signal is aborted during cleanup');
    assert.equal(written.filter((event) => event.type === 'session_id').length, 1);
    assert.equal(written.filter((event) => event.type === 'error').length, 1);
    assert.equal(written.filter((event) => event.type === 'stream_end').length, 1);
    assert.equal(written.filter((event) => event.type === 'message_end').length, 1);
    assert.match(written.find((event) => event.type === 'error').message, /prompt rejected/);
});

test('clientFactory receives baseUrl', async () => {
    let receivedBaseUrl = null;
    const { client } = makeMockClient({ events: [
        { type: 'server.connected', properties: {} },
        { type: 'session.idle', properties: { sessionID: 'ses_mock_1' } }
    ] });
    await sendMessage({
        message: 'q', threadId: '', cwd: '/tmp', model: 'opencode/mimo-v2.5-free',
        baseUrl: 'http://127.0.0.1:14096'
    }, {
        clientFactory: async (baseUrl) => { receivedBaseUrl = baseUrl; return client; },
        write: () => {}
    });
    assert.equal(receivedBaseUrl, 'http://127.0.0.1:14096');
});

test('splitModelForSdk re-exports splitModel contract', () => {
    assert.deepEqual(splitModelForSdk('opencode/mimo-v2.5-free'), { providerID: 'opencode', modelID: 'mimo-v2.5-free' });
});
