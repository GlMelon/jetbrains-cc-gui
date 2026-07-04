/**
 * §15.7 B2/B11/B16:OpenCode SDK 消息服务。
 *
 * 通过 @opencode-ai/sdk 的 `createOpencodeClient({baseUrl})` 连接由
 * `OpenCodeDaemonCoordinator` 常驻的 `opencode serve`,执行 session.create/prompt +
 * event.subscribe(SSE)消费,把真实 SSE 事件经 {@link createOpenCodeEventMapper}
 * 映射为统一 NDJSON 写 stdout,由 Java 侧 `OpenCodeSDKBridge.processOutputLine` 解析。
 *
 * 事件 schema 来自本地 opencode v1.17.11 实跑(见 memory opencode-real-api-contract)。
 *
 * 可注入 clientFactory/write 便于单测(无真实 serve 依赖)。
 */

import { loadOpencodeSdk } from '../../utils/sdk-loader.js';
import { splitModel } from './model-utils.js';
import { createOpenCodeEventMapper } from './event-mapper.js';

export { splitModel as splitModelForSdk } from './model-utils.js';

/** 默认 NDJSON 写出:逐行写 stdout。 */
function defaultWrite(obj) {
    process.stdout.write(JSON.stringify(obj) + '\n');
}

/** 默认 client 工厂:动态加载已安装的 @opencode-ai/sdk 并 createOpencodeClient。 */
async function defaultClientFactory(baseUrl) {
    const sdk = await loadOpencodeSdk();
    return sdk.createOpencodeClient({ baseUrl });
}

/**
 * 发送消息到 OpenCode 并回流 NDJSON 事件流。
 *
 * @param {object} params
 * @param {string} params.message     用户消息文本
 * @param {string} params.threadId    会话 id(空则新建)
 * @param {string} params.cwd         工作目录(用作 session title)
 * @param {string} params.permissionMode 权限模式(透传,SDK 侧由 serve 配置消费)
 * @param {string} params.model       `provider/model` 聚合字符串
 * @param {string} [params.reasoningEffort] 推理档位(透传)
 * @param {Array}  [params.attachments] 附件 parts(按 opencode part schema)
 * @param {string} params.baseUrl     opencode serve 的 http base url
 * @param {object} [params.env]       环境变量(透传)
 * @param {object} [deps]             注入点(测试用)
 * @param {(baseUrl:string)=>Promise<object>} [deps.clientFactory]
 * @param {(obj:object)=>void} [deps.write]
 */
export async function sendMessage(params, deps = {}) {
    const {
        message = '',
        threadId = '',
        cwd = '',
        permissionMode = '',
        model = '',
        reasoningEffort = '',
        attachments = [],
        baseUrl = '',
        env = {}
    } = params || {};

    const clientFactory = deps.clientFactory || defaultClientFactory;
    const write = deps.write || defaultWrite;

    // §15.7:AbortController 控制 SSE 连接生命周期。for-await break 不会关闭 hey-api
    // stream 的底层 fetch 连接(实测:break 后进程仍挂起),须在 finally 主动 abort 释放,
    // 否则 channel 进程无法自然退出(连接泄漏致 Node 事件循环保持活跃)。
    const controller = new AbortController();

    try {
        const client = await clientFactory(baseUrl);

        // 1. session:threadId 非空则续接,否则新建
        let sessionId = threadId;
        if (!sessionId) {
            // directory 显式绑定项目目录,getSessionList 据此按 projectPath 过滤命中;
            // 否则新会话 directory 缺失,被项目过滤剔除 → 不出现在历史列表。
            const session = await client.session.create({ body: { title: cwd || 'opencode', directory: cwd } });
            sessionId = session?.data?.id;
            // session_id 由 createOpenCodeEventMapper 在首个携带本会话 sessionID 的 SSE 事件时
            // 单一下发(单一来源,避免双发)。
        }

        // 2. model 拆分 provider/model → {providerID, modelID}
        const { providerID, modelID } = splitModel(model);

        // 3. parts:文本 + 附件
        const parts = [];
        if (message) parts.push({ type: 'text', text: message });
        for (const att of attachments) {
            if (att && typeof att === 'object') parts.push(att);
        }

        // 4. model 对象(若拆不出 providerID,退化为仅 modelID)
        const modelBody = providerID
            ? { providerID, modelID }
            : { modelID };

        const mapper = createOpenCodeEventMapper(sessionId);

        // 5. 并发:订阅 SSE 流 + 发 prompt(signal 绑定 controller,finally abort 释放连接)
        const events = await client.event.subscribe({ signal: controller.signal });
        const promptPromise = client.session.prompt({
            path: { id: sessionId },
            body: { model: modelBody, parts }
        }).catch((e) => e); // 捕获错误,延后处理(先消费已到达的 SSE)

        let promptError = null;
        let streamEnded = false;

        // 6. 消费 SSE 事件 → NDJSON
        for await (const event of events.stream) {
            const mapped = mapper.map(event);
            for (const nd of mapped) {
                if (nd.type === 'stream_end') streamEnded = true;
                write(nd);
            }
            if (streamEnded) break;
        }

        // 7. 等待 prompt 完成(可能返错误)
        const promptResult = await promptPromise;
        if (promptResult instanceof Error) {
            promptError = promptResult;
        }

        // 8. 兜底:若 SSE 未显式下发终止(异常断流),补发 stream_end/message_end
        if (!streamEnded) {
            write({ type: 'stream_end' });
            write({ type: 'message_end' });
        }

        if (promptError) {
            write({ type: 'error', message: promptError.message || 'OpenCode prompt failed' });
        }
    } catch (e) {
        // 顶层异常(auth/网络/SDK 未装)→ error NDJSON(AbortError 由 finally 触发,忽略)
        if (e?.name === 'AbortError') return;
        write({ type: 'stream_start' });
        write({ type: 'error', message: e?.message || 'OpenCode SDK error' });
        write({ type: 'stream_end' });
        write({ type: 'message_end' });
    } finally {
        // §15.7:主动断开 SSE 连接。hey-api 的 stream 在 for-await break 后不释放底层 fetch,
        // 不 abort 会泄漏连接、令 channel 进程挂起无法退出。
        try { controller.abort(); } catch (_) { /* ignore */ }
    }
}

/**
 * 中断当前会话(best-effort)。
 * @param {object} params
 * @param {string} params.threadId
 * @param {string} params.baseUrl
 * @param {object} [deps]
 */
export async function abortSession(params, deps = {}) {
    const { threadId = '', baseUrl = '' } = params || {};
    const clientFactory = deps.clientFactory || defaultClientFactory;
    if (!threadId) return;
    try {
        const client = await clientFactory(baseUrl);
        await client.session.abort({ path: { id: threadId } });
    } catch (e) {
        // best-effort:中断失败不影响主流程
        console.error('[opencode] abort failed:', e?.message);
    }
}
