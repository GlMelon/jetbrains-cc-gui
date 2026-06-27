/**
 * §15.7 B16:OpenCode SSE 事件 → 统一 bridge NDJSON 映射。
 *
 * 事件 schema 来自 2026-06-27 本地 opencode v1.17.11 + @opencode-ai/sdk 实跑捕获(非臆造),
 * 见 memory `opencode-real-api-contract`。
 *
 * SSE 事件统一结构 `{id, type, properties:{...}}`;event.subscribe() 返回的流是**全局的**
 * (跨所有 session),故须按 `properties.sessionID` 过滤当前会话。
 *
 * 关键实测事实:
 * - `message.updated`(info.role, info.id)先于其 `message.part.updated` 到达;part 携带
 *   `messageID` 与 info.id 关联。**用户消息的 text part 是回显,须按 role=user 跳过**,
 *   否则 assistant 文本会混入用户输入。
 * - usage 在 assistant role 的 `message.updated`(info.tokens)。
 *
 * 映射(实测):
 * - `server.connected`/首事件 → stream_start + message_start(只发一次)
 * - 首个携带本会话 sessionID 的事件 → session_id(只发一次,单一来源)
 * - `message.part.updated` part.type=text(role=assistant) → content_delta(增量去重)
 * - `message.part.updated` part.type=reasoning → thinking_delta
 * - `message.part.updated` part.type=tool → assistant(原始 tool 块)
 * - `message.updated` role=assistant 且 tokens>0 → usage
 * - `session.idle` / `session.status` status=idle → stream_end + message_end(终止幂等)
 * - `error` → error
 */

/**
 * 创建一个有状态的 SSE→NDJSON 映射器。
 * @param {string} sessionId 当前会话 id(用于全局 SSE 流过滤)
 * @returns {{map:(event:object)=>object[]}}
 */
export function createOpenCodeEventMapper(sessionId) {
    let streamStarted = false;
    let streamEnded = false;
    let sessionIdEmitted = false;
    let assistantText = ''; // 累积 assistant 文本,用于 part.updated 增量去重
    let reasoningText = ''; // 累积 reasoning 文本
    let lastUsageKey = ''; // 上次下发 usage 的指纹,幂等 message.updated 去重(避免 token 用量翻倍)
    const roleByMessageId = new Map(); // messageID → role("user"|"assistant")

    function ensureStarted() {
        if (streamStarted) return [];
        streamStarted = true;
        return [{ type: 'stream_start' }, { type: 'message_start' }];
    }

    function emitSessionIdOnce(props) {
        if (sessionIdEmitted) return [];
        const sid = props?.sessionID;
        if (!sid || sid !== sessionId) return [];
        sessionIdEmitted = true;
        return [{ type: 'session_id', session_id: sid }];
    }

    function delta(previous, next) {
        const oldText = previous || '';
        const newText = next || '';
        if (!newText || newText === oldText) return '';
        if (!oldText) return newText;
        if (newText.startsWith(oldText)) return newText.slice(oldText.length);
        return newText;
    }

    function map(event) {
        if (!event || typeof event !== 'object') return [];
        const type = event.type;
        const props = event.properties || {};

        // 终止后忽略一切(幂等)
        if (streamEnded) return [];

        switch (type) {
            case 'server.connected':
            case 'server.heartbeat': {
                return ensureStarted();
            }

            case 'session.next.model.switched': {
                if (!isCurrentSession(props)) return [];
                return ensureStarted().concat(emitSessionIdOnce(props));
            }

            case 'message.updated': {
                if (!isCurrentSession(props)) return [];
                const out = ensureStarted().concat(emitSessionIdOnce(props));
                const info = props.info || {};
                if (info.id && info.role) {
                    roleByMessageId.set(info.id, info.role);
                }
                if (info.role === 'assistant' && info.tokens) {
                    const t = info.tokens;
                    const total = Number(t.total || 0);
                    const input = Number(t.input || 0);
                    const output = Number(t.output || 0);
                    // 全 0 的早期 assistant 占位消息不发(噪声)
                    if (total > 0 || input > 0 || output > 0) {
                        const cache = t.cache || {};
                        const cacheRead = Number(cache.read || 0);
                        const cacheWrite = Number(cache.write || 0);
                        // 去重:serve 幂等重发同一 assistant message.updated(tokens 相同),
                        // 重复下发会让前端 token 用量翻倍(累加语义)。值变化时仍下发(累积更新)。
                        const usageKey = `${input}|${output}|${cacheRead}|${cacheWrite}`;
                        if (usageKey === lastUsageKey) return out;
                        lastUsageKey = usageKey;
                        return out.concat([{
                            type: 'usage',
                            usage: {
                                input_tokens: input,
                                output_tokens: output,
                                cache_read_input_tokens: cacheRead,
                                cache_creation_input_tokens: cacheWrite
                            }
                        }]);
                    }
                }
                return out;
            }

            case 'message.part.updated': {
                if (!isCurrentSession(props)) return [];
                const out = ensureStarted().concat(emitSessionIdOnce(props));
                const part = props.part || {};
                const role = part.messageID ? roleByMessageId.get(part.messageID) : undefined;
                // role=user 的 text part 是用户消息回显,跳过(避免污染 assistant 文本)
                if (role === 'user') return out;
                if (part.type === 'text') {
                    const d = delta(assistantText, part.text);
                    if (d) {
                        assistantText += d;
                        return out.concat([{ type: 'content_delta', text: d }]);
                    }
                    return out;
                }
                if (part.type === 'reasoning') {
                    const d = delta(reasoningText, part.text);
                    if (d) {
                        reasoningText += d;
                        return out.concat([{ type: 'thinking_delta', text: d }]);
                    }
                    return out;
                }
                if (part.type === 'tool') {
                    return out.concat([{ type: 'assistant', content: JSON.stringify(part) }]);
                }
                // step-start / step-finish / 其它 part:仅确保流已开始
                return out;
            }

            case 'message.part.delta': {
                if (!isCurrentSession(props)) return [];
                const out = ensureStarted().concat(emitSessionIdOnce(props));
                const part = props.part || props;
                if (part && part.text) {
                    assistantText += part.text;
                    return out.concat([{ type: 'content_delta', text: part.text }]);
                }
                return out;
            }

            case 'session.idle': {
                if (!isCurrentSession(props)) return [];
                return endStream();
            }

            case 'session.status': {
                if (!isCurrentSession(props)) return [];
                if (props.status && props.status.type === 'idle') {
                    return endStream();
                }
                return ensureStarted().concat(emitSessionIdOnce(props));
            }

            case 'error': {
                const out = ensureStarted();
                const message = (props && typeof props.message === 'string' && props.message) ||
                    (props && props.data && props.data.message) ||
                    'OpenCode stream error';
                return out.concat([{ type: 'error', message }]);
            }

            default:
                return [];
        }
    }

    function isCurrentSession(props) {
        const sid = props?.sessionID;
        if (!sid) return true; // 无 sessionID 的事件(如 server.*)不按会话过滤
        return sid === sessionId;
    }

    function endStream() {
        const out = ensureStarted();
        if (streamEnded) return [];
        streamEnded = true;
        return out.concat([{ type: 'stream_end' }, { type: 'message_end' }]);
    }

    return { map };
}
