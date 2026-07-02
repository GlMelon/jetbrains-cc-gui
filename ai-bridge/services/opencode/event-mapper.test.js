import test from 'node:test';
import assert from 'node:assert/strict';

import { createOpenCodeEventMapper } from './event-mapper.js';

/**
 * §15.7 B16:OpenCode SSE 事件 → 统一 NDJSON 映射。
 * 事件 schema 来自 2026-06-27 本地 opencode v1.17.11 实跑捕获(非臆造),
 * 见 memory opencode-real-api-contract。事件结构 {id, type, properties:{...}},
 * SSE 流全局跨 session,须按 properties.sessionID 过滤当前会话。
 *
 * 映射:server.connected→stream_start+message_start;message.part.updated(part.type=text)→content_delta;
 * part.type=reasoning→thinking_delta;message.updated(assistant tokens)→usage;session.idle/status idle→stream_end+message_end。
 */
const SID = 'ses_probe123';

function ev(type, properties = {}) {
    return { id: 'evt_' + type, type, properties };
}

/** 预置 mapper:发 server.connected + session.next.model.switched,使 stream_start 与
 *  session_id 已下细,后续事件断言可聚焦目标事件(role/usage/content_delta 等)。 */
function prime(m) {
    m.map(ev('server.connected'));
    m.map(ev('session.next.model.switched', {
        sessionID: SID,
        model: { id: 'm', providerID: 'opencode', variant: 'default' }
    }));
}

test('first content-bearing event emits stream_start + message_start once', () => {
    const m = createOpenCodeEventMapper(SID);
    // server.connected 是流的第一个事件
    const out = m.map(ev('server.connected', {}));
    assert.deepEqual(out, [
        { type: 'stream_start' },
        { type: 'message_start' }
    ]);
    // 第二个 server.connected 不重复发(幂等)
    assert.deepEqual(m.map(ev('server.heartbeat', {})), []);
});

test('message.part.updated with text part emits content_delta with dedup', () => {
    const m = createOpenCodeEventMapper(SID);
    prime(m);
    // 空 text part 不发增量
    assert.deepEqual(
        m.map(ev('message.part.updated', { sessionID: SID, part: { type: 'text', text: '' } })),
        []
    );
    // 累积 text:首次非空 → 全量作为 delta
    const o1 = m.map(ev('message.part.updated', { sessionID: SID, part: { type: 'text', text: 'hello world' } }));
    assert.equal(o1.length, 1);
    assert.equal(o1[0].type, 'content_delta');
    assert.equal(o1[0].text, 'hello world');
});

test('message.part.updated with reasoning part emits thinking_delta', () => {
    const m = createOpenCodeEventMapper(SID);
    prime(m);
    const out = m.map(ev('message.part.updated', {
        sessionID: SID,
        part: { type: 'reasoning', text: 'thinking about it' }
    }));
    // 首个 reasoning 合成 thinking 激活态 + thinking_delta(断点C);此处聚焦 delta 存在性
    const delta = out.find((e) => e.type === 'thinking_delta');
    assert.ok(delta, 'reasoning must emit thinking_delta');
    assert.equal(delta.text, 'thinking about it');
});

test('message.updated assistant tokens emit usage with mapped fields', () => {
    const m = createOpenCodeEventMapper(SID);
    prime(m);
    const out = m.map(ev('message.updated', {
        sessionID: SID,
        info: {
            role: 'assistant',
            tokens: { total: 35142, input: 53, output: 5, reasoning: 12, cache: { write: 0, read: 35072 } }
        }
    }));
    assert.equal(out.length, 1);
    assert.equal(out[0].type, 'usage');
    assert.deepEqual(out[0].usage, {
        input_tokens: 53,
        output_tokens: 5,
        cache_read_input_tokens: 35072,
        cache_creation_input_tokens: 0
    });
});

test('usage with identical tokens on repeated message.updated is deduped', () => {
    // 实测:serve 对同一 assistant message 幂等重发 message.updated(tokens 相同),
    // 重复 usage 会让前端 token 用量翻倍(累加语义)。相同值第二次须跳过。
    const m = createOpenCodeEventMapper(SID);
    prime(m);
    const tok = { total: 35147, input: 51, output: 4, cache: { write: 0, read: 35072 } };
    const o1 = m.map(ev('message.updated', { sessionID: SID, info: { role: 'assistant', tokens: tok } }));
    const o2 = m.map(ev('message.updated', { sessionID: SID, info: { role: 'assistant', tokens: tok } }));
    assert.equal(o1.filter((e) => e.type === 'usage').length, 1, 'first emits usage');
    assert.equal(o2.filter((e) => e.type === 'usage').length, 0, 'identical repeat deduped');
});

test('usage with changed tokens is still emitted (cumulative updates)', () => {
    // 累积更新场景:token 值递增,每档仍须下发(前端覆盖取最新)
    const m = createOpenCodeEventMapper(SID);
    prime(m);
    m.map(ev('message.updated', { sessionID: SID, info: { role: 'assistant', tokens: { total: 10, input: 5, output: 1, cache: { write: 0, read: 4 } } } }));
    const o2 = m.map(ev('message.updated', { sessionID: SID, info: { role: 'assistant', tokens: { total: 20, input: 10, output: 2, cache: { write: 0, read: 8 } } } }));
    assert.equal(o2.filter((e) => e.type === 'usage').length, 1, 'changed tokens still emitted');
});

test('zero-token assistant message.updated emits no usage (avoid noise)', () => {
    const m = createOpenCodeEventMapper(SID);
    prime(m);
    const out = m.map(ev('message.updated', {
        sessionID: SID,
        info: { role: 'assistant', tokens: { input: 0, output: 0, reasoning: 0, cache: { read: 0, write: 0 } } }
    }));
    assert.deepEqual(out, []);
});

test('session.idle emits stream_end + message_end', () => {
    const m = createOpenCodeEventMapper(SID);
    m.map(ev('server.connected'));
    const out = m.map(ev('session.idle', { sessionID: SID }));
    assert.deepEqual(out, [{ type: 'stream_end' }, { type: 'message_end' }]);
});

test('session.status idle also emits stream_end + message_end (belt-and-suspenders)', () => {
    const m = createOpenCodeEventMapper(SID);
    m.map(ev('server.connected'));
    const out = m.map(ev('session.status', { sessionID: SID, status: { type: 'idle' } }));
    assert.deepEqual(out, [{ type: 'stream_end' }, { type: 'message_end' }]);
    // 已结束,后续 idle 不重复
    assert.deepEqual(m.map(ev('session.idle', { sessionID: SID })), []);
});

test('session.status busy does not emit terminal events', () => {
    const m = createOpenCodeEventMapper(SID);
    prime(m);
    assert.deepEqual(m.map(ev('session.status', { sessionID: SID, status: { type: 'busy' } })), []);
});

test('events for other sessions are filtered out', () => {
    const m = createOpenCodeEventMapper(SID);
    m.map(ev('server.connected'));
    // 另一个 session 的 text 事件须被忽略
    const out = m.map(ev('message.part.updated', {
        sessionID: 'ses_OTHER',
        part: { type: 'text', text: 'should be ignored' }
    }));
    assert.deepEqual(out, []);
});

test('message.part.updated tool part emits tool_use + tool_result (Anthropic schema)', () => {
    // 断点B 修复:tool part 不再降级为 assistant 乱码,归一为标准 tool_use+tool_result 双发,
    // 对称 CLI OpenCodeCliStreamParser.handleToolUse,使 CodexMessageHandler 渲染工具卡。
    const m = createOpenCodeEventMapper(SID);
    m.map(ev('server.connected'));
    const out = m.map(ev('message.part.updated', {
        sessionID: SID,
        part: {
            type: 'tool',
            tool: 'read',
            callID: 'call_1',
            state: { status: 'completed', input: '{"path":"a.txt"}', output: 'file content' }
        }
    }));
    // tool_use 块:input 字符串须 parse 为对象(对称 CLI 的对象 input)
    const toolUse = out.find((e) => e.type === 'tool_use');
    assert.ok(toolUse, 'tool event must emit tool_use');
    const useBlock = JSON.parse(toolUse.content);
    assert.equal(useBlock.type, 'tool_use');
    assert.equal(useBlock.id, 'call_1');
    assert.equal(useBlock.name, 'read');
    assert.deepEqual(useBlock.input, { path: 'a.txt' });
    // tool_result 块:携带 output
    const toolResult = out.find((e) => e.type === 'tool_result');
    assert.ok(toolResult, 'tool event must emit tool_result');
    const resultBlock = JSON.parse(toolResult.content);
    assert.equal(resultBlock.type, 'tool_result');
    assert.equal(resultBlock.tool_use_id, 'call_1');
    assert.equal(resultBlock.content, 'file content');
    // 不再发 assistant 乱码
    assert.equal(out.filter((e) => e.type === 'assistant').length, 0, 'no assistant downgrade');
});

test('tool_use/tool_result are deduped by callID across repeated part.updated', () => {
    // serve 对同一 tool part 幂等重发,callID 去重避免重复工具卡(对称 Codex emitToolUseOnce)。
    const m = createOpenCodeEventMapper(SID);
    m.map(ev('server.connected'));
    const part = {
        type: 'tool', tool: 'bash', callID: 'call_2',
        state: { status: 'completed', input: '{"command":"ls"}', output: 'a\nb' }
    };
    const o1 = m.map(ev('message.part.updated', { sessionID: SID, part }));
    const o2 = m.map(ev('message.part.updated', { sessionID: SID, part }));
    assert.equal(o1.filter((e) => e.type === 'tool_use').length, 1, 'first emits tool_use');
    assert.equal(o1.filter((e) => e.type === 'tool_result').length, 1, 'first emits tool_result');
    assert.equal(o2.filter((e) => e.type === 'tool_use').length, 0, 'repeat tool_use deduped');
    assert.equal(o2.filter((e) => e.type === 'tool_result').length, 0, 'repeat tool_result deduped');
});

test('tool part staged: running(input) emits tool_use only; completed(output) emits tool_result', () => {
    // 工具调用生命周期:先 running(input)→tool_use,后 completed(output)→tool_result。
    const m = createOpenCodeEventMapper(SID);
    m.map(ev('server.connected'));
    const o1 = m.map(ev('message.part.updated', {
        sessionID: SID,
        part: { type: 'tool', tool: 'read', callID: 'call_3', state: { status: 'running', input: '{"path":"x"}' } }
    }));
    assert.equal(o1.filter((e) => e.type === 'tool_use').length, 1, 'running emits tool_use');
    assert.equal(o1.filter((e) => e.type === 'tool_result').length, 0, 'no result until completed');
    const o2 = m.map(ev('message.part.updated', {
        sessionID: SID,
        part: { type: 'tool', tool: 'read', callID: 'call_3', state: { status: 'completed', input: '{"path":"x"}', output: 'done' } }
    }));
    assert.equal(o2.filter((e) => e.type === 'tool_use').length, 0, 'tool_use already emitted');
    assert.equal(o2.filter((e) => e.type === 'tool_result').length, 1, 'completed emits tool_result');
});

test('first reasoning part emits thinking activation (thinking_start) + thinking_delta', () => {
    // 断点C 修复:首个 reasoning 合成 thinking 激活态(对称 CLI thinkingStart),
    // 使前端"思考中"指示灯亮;后续 reasoning 只发增量。
    const m = createOpenCodeEventMapper(SID);
    prime(m);
    const out = m.map(ev('message.part.updated', {
        sessionID: SID, part: { type: 'reasoning', text: 'first thought' }
    }));
    const activation = out.find((e) => e.type === 'thinking');
    assert.ok(activation, 'first reasoning must emit thinking activation');
    const delta = out.find((e) => e.type === 'thinking_delta');
    assert.ok(delta, 'still emits thinking_delta');
    assert.equal(delta.text, 'first thought');
});

test('subsequent reasoning parts do NOT re-emit thinking activation', () => {
    const m = createOpenCodeEventMapper(SID);
    prime(m);
    m.map(ev('message.part.updated', { sessionID: SID, part: { type: 'reasoning', text: 'a' } }));
    const out = m.map(ev('message.part.updated', { sessionID: SID, part: { type: 'reasoning', text: 'ab' } }));
    assert.equal(out.filter((e) => e.type === 'thinking').length, 0, 'activation only once');
    const delta = out.find((e) => e.type === 'thinking_delta');
    assert.ok(delta);
    assert.equal(delta.text, 'b');
});

test('error event emits error with message', () => {
    const m = createOpenCodeEventMapper(SID);
    m.map(ev('server.connected'));
    const out = m.map(ev('error', { message: 'boom' }));
    assert.deepEqual(out, [{ type: 'error', message: 'boom' }]);
});

test('error event without message uses generic text', () => {
    const m = createOpenCodeEventMapper(SID);
    m.map(ev('server.connected'));
    const out = m.map(ev('error', {}));
    assert.equal(out.length, 1);
    assert.equal(out[0].type, 'error');
    assert.ok(out[0].message.length > 0);
});

test('session.next.model.switched emits session_id once (early sessionId surfacing)', () => {
    // session_id 越早下发越好(前端可建立会话上下文);模型切换事件携带 sessionID
    const m = createOpenCodeEventMapper(SID);
    const out = m.map(ev('session.next.model.switched', {
        sessionID: SID,
        model: { id: 'mimo-v2.5-free', providerID: 'opencode', variant: 'default' }
    }));
    const sidEvent = out.find((e) => e.type === 'session_id');
    assert.ok(sidEvent, 'model.switched should surface session_id');
    assert.equal(sidEvent.session_id, SID);
});

test('user message text part (echo) is NOT emitted as content_delta', () => {
    // 实测:message.updated(role=user)先于其 message.part.updated;text part 属 user 消息=回显,须跳过
    const m = createOpenCodeEventMapper(SID);
    prime(m);
    m.map(ev('message.updated', { sessionID: SID, info: { id: 'msg_u', role: 'user' } }));
    const userEcho = m.map(ev('message.part.updated', {
        sessionID: SID, part: { type: 'text', text: 'say hi', messageID: 'msg_u' }
    }));
    // 回显不得产出 content_delta
    assert.deepEqual(userEcho.filter((o) => o.type === 'content_delta'), []);
});

test('assistant message text part IS emitted as content_delta', () => {
    const m = createOpenCodeEventMapper(SID);
    m.map(ev('server.connected'));
    m.map(ev('message.updated', { sessionID: SID, info: { id: 'msg_a', role: 'assistant' } }));
    const out = m.map(ev('message.part.updated', {
        sessionID: SID, part: { type: 'text', text: 'hello world', messageID: 'msg_a' }
    }));
    const deltas = out.filter((o) => o.type === 'content_delta');
    assert.equal(deltas.length, 1);
    assert.equal(deltas[0].text, 'hello world');
});
