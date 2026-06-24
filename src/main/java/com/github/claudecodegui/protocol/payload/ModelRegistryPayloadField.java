package com.github.claudecodegui.protocol.payload;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ModelRegistry 下行 payload 的 wire 字段声明(C1·payload SSOT)。
 *
 * <p>显式声明 {@link com.github.claudecodegui.settings.ModelRegistryService#serialize}
 * 下发的每一个 JSON key,作为 manifest → TS payload 接口生成的真相源。
 *
 * <p><b>为何显式声明而非反射 ModelConfig 记录组件</b>:serialize 还派生
 * {@code supportedReasoningLevels}(由 role 计算,不存入 ModelConfig),反射记录组件
 * 会有派生间隙(漏该字段)。显式声明自然包含派生字段,且守住"声明==实际产出"的契约
 * (见 {@code ModelRegistryPayloadFieldTest} 后端守门)。
 *
 * <p>每个常量携带三参,供 {@code generate-protocol-types.mjs} 多参解析:
 * <ul>
 *   <li>{@code wireKey} —— JSON key 名(前后端 wire 契约)</li>
 *   <li>{@code tsType}  —— 生成 TS 接口时该字段的类型(原始类型 string/number/boolean;
 *       数组用 readonly 修饰,以兼容前端业务类型收窄如 readonly ReasoningEffort[])</li>
 *   <li>{@code optional} —— 是否条件性下发(决定 TS 接口字段是否带 {@code ?})</li>
 * </ul>
 *
 * <p>字段顺序与 serialize 产出顺序一致,便于生成可读的 TS 接口。
 */
public enum ModelRegistryPayloadField {
    ID("id", "string", false),
    PROVIDER("provider", "string", false),
    ROLE("role", "string", true),
    LABEL("label", "string", false),
    ACTUAL_MODEL("actualModel", "string", true),
    DESCRIPTION("description", "string", true),
    CONTEXT_WINDOW("contextWindow", "number", false),
    SUPPORTS_1M_CONTEXT("supports1MContext", "boolean", false),
    SUPPORTED_REASONING_LEVELS("supportedReasoningLevels", "readonly string[]", true),
    ENABLED("enabled", "boolean", false),
    READ_ONLY("readOnly", "boolean", false);

    private final String wireKey;
    private final String tsType;
    private final boolean optional;

    ModelRegistryPayloadField(String wireKey, String tsType, boolean optional) {
        this.wireKey = wireKey;
        this.tsType = tsType;
        this.optional = optional;
    }

    /** JSON key 名(前后端 wire 契约)。 */
    public String wireKey() {
        return wireKey;
    }

    /** 生成 TS 接口时该字段的类型字面量。 */
    public String tsType() {
        return tsType;
    }

    /** 是否条件性下发(TS 接口对应字段带 {@code ?})。 */
    public boolean optional() {
        return optional;
    }

    /** 全部 wire key 集合(后端守门:声明集 == serialize 实际产出集)。 */
    public static Set<String> wireKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (ModelRegistryPayloadField f : values()) {
            keys.add(f.wireKey);
        }
        return keys;
    }
}
