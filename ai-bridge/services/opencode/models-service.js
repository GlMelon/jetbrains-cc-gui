/**
 * §15.8 §11:OpenCode 模型列表查询(能力层,前端 UI defer 见 docs/决策)。
 *
 * 调 `client.config.providers()`(返回已配置 provider 的模型,即用户实际可用集;
 * 区别于 `provider.list()` 返回所有 provider 含未连接),扁平化 provider/models 树为
 * 统一模型列表 [{provider, model, name, contextLimit, reasoning, attachment, toolcall}],
 * 供后端 ModelRegistry 刷新或前端(未来)消费。
 *
 * 真实格式(2026-06-27 本地 opencode v1.17.11 实测,见 memory opencode-real-api-contract):
 *   {data:{providers:[{id, name, models:{modelId:{name, capabilities:{reasoning,attachment,toolcall}, limit:{context,output}}}}], default}}
 *
 * 一次性 HTTP 查询(非 SSE 流),channel-manager 对 opencode provider 已 force-exit,
 * undici keep-alive 连接由其兜底释放,本服务无需自带 abort。
 *
 * 可注入 clientFactory/write 便于单测(无真实 serve 依赖)。
 */

import { loadOpencodeSdk } from '../../utils/sdk-loader.js';

/** 默认 client 工厂:动态加载 @opencode-ai/sdk 并 createOpencodeClient。 */
async function defaultClientFactory(baseUrl) {
    const sdk = await loadOpencodeSdk();
    return sdk.createOpencodeClient({ baseUrl });
}

function defaultWrite(obj) {
    process.stdout.write(JSON.stringify(obj) + '\n');
}

/**
 * 查询 OpenCode 可用模型列表。
 * @param {object} params
 * @param {string} params.baseUrl opencode serve 的 http base url
 * @param {object} [deps]
 * @param {(baseUrl:string)=>Promise<object>} [deps.clientFactory]
 * @param {(obj:object)=>void} [deps.write]
 */
export async function listModels(params, deps = {}) {
    const { baseUrl = '' } = params || {};
    const clientFactory = deps.clientFactory || defaultClientFactory;
    const write = deps.write || defaultWrite;
    try {
        const client = await clientFactory(baseUrl);
        const result = await client.config.providers();
        const data = result?.data || result || {};
        const providers = Array.isArray(data.providers) ? data.providers : [];
        const models = [];
        for (const prov of providers) {
            const providerId = prov.id;
            const provModels = prov.models || {};
            for (const [modelId, detail] of Object.entries(provModels || {})) {
                const caps = detail?.capabilities || {};
                const limit = detail?.limit || {};
                models.push({
                    provider: providerId,
                    model: modelId,
                    name: detail?.name || modelId,
                    contextLimit: Number(limit.context || 0),
                    reasoning: !!caps.reasoning,
                    attachment: !!caps.attachment,
                    toolcall: !!caps.toolcall
                });
            }
        }
        write({ success: true, models });
    } catch (e) {
        write({ success: false, error: e?.message || 'OpenCode listModels failed' });
    }
}
