/**
 * §15.7 B2:OpenCode 模型工具。
 *
 * OpenCode 是模型聚合器,用 `provider/model` 字符串格式(背后 models.dev),
 * 与 Claude/Codex 的单一 provider 不同。SDK session.prompt 需要 `{providerID, modelID}`
 * 对象,故须从聚合字符串拆分。
 */

/**
 * 拆分 `provider/model` 聚合字符串为 {providerID, modelID}。
 *
 * 规则:
 * - 仅按首个 `/` 拆分(provider 分隔符);modelID 内部可能再含 `/`(如 `requesty/xai/grok-4`)。
 * - 无 `/` 时 providerID=null,modelID 为原值(交由上游决策默认 provider)。
 * - 空/blank/null 输入返回 {null, null}。
 *
 * @param {string|null|undefined} model
 * @returns {{providerID: string|null, modelID: string|null}}
 */
export function splitModel(model) {
    if (model == null) {
        return { providerID: null, modelID: null };
    }
    const trimmed = String(model).trim();
    if (trimmed === '') {
        return { providerID: null, modelID: null };
    }
    const slashIndex = trimmed.indexOf('/');
    if (slashIndex < 0) {
        return { providerID: null, modelID: trimmed };
    }
    const providerID = trimmed.slice(0, slashIndex);
    const modelID = trimmed.slice(slashIndex + 1);
    return {
        providerID: providerID === '' ? null : providerID,
        modelID: modelID === '' ? null : modelID
    };
}
