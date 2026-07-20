package com.github.claudecodegui.settings;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.config.ModelConfig;
import com.github.claudecodegui.config.ModelConfigValidator;
import com.github.claudecodegui.config.ModelRegistryConfig;
import com.github.claudecodegui.config.ReadOnlyDefaultModels;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.List;

/**
 * ModelRegistry(模型注册表)领域 Service(A3 领域拆分第四步,docs §A3)。
 *
 * <p>封装 effective registry 的读写:effective = merge(persisted user layer, 现算只读默认)。
 * 只读默认(Claude 4 roles from {@code ~/.claude/settings.json} + Codex from {@code ~/.codex/config.toml}
 * + OpenCode)运行时计算,永不持久化;写盘只存 user layer(readOnly 项剥离)。
 *
 * <p>与 {@link AppearanceSettingsService} / {@link AiFeatureToggleSettingsService} /
 * {@link CodexSandboxModeSettingsService} 同为「模式 A 半拆」:构造注入 {@link CodemossSettingsService},
 * 持久化走 {@code css.readConfig()/writeConfig()}。核心理由同第一步 —— 文件缺失时
 * {@code CSS.readConfig()} 返回 {@code createDefaultConfig()} 全局骨架,Service 在其上读写,
 * 行为与历史逐字等价;直连 {@link ConfigRepository} 会丢失全局默认段。
 *
 * <p><b>与静态 {@link ModelRegistryService} 分工(不合并)</b>:静态 {@link ModelRegistryService}
 * 是「payload codec + handler orchestration」(serialize/parse 给前端下发 + 3 Action Handler
 * 实例 API);本类是「persistence + validation + merge orchestration」。{@link #getModelRegistryJson}
 * 继续调静态 {@link ModelRegistryService#serialize} 以下发 {@code supportedReasoningLevels} 派生字段
 * (契约 H3,否则前端 ReasoningSelect 整体隐藏);写盘路径 {@link #serializeModelRegistry} 刻意不含
 * 派生字段(避免双写)。
 *
 * <p><b>AI Feature 交叉依赖</b>:CSS {@code normalizeAiFeatureClaudeModel} 调
 * {@code css.getModelRegistry().find(...)}(单点)—— 留在 CSS 经动态分发走本类委托,零改动。
 *
 * <p><b>parseModelRegistry NPE 语义保留</b>:本类 {@link #parseModelRegistry}(写盘路径,
 * {@code supports1MContext} 缺 null 守卫)与静态 {@link ModelRegistryService#parse}(payload 路径,
 * 有守卫)有细微语义差异 —— 迁移期间逐字保留两份,不顺手统一(NPE 是独立 bug,§13 单一职责)。
 *
 * <p><b>Facade 不变</b>:CSS 3 个 public 签名保留为单行转发委托;调用面(22 外部调用点 + 8 测试)
 * 与既有 6 测试类零改动。
 */
public final class ModelRegistrySettingsService {
    private static final Logger LOG = Logger.getInstance(ModelRegistrySettingsService.class);

    private final CodemossSettingsService settingsService;

    // ==================== Field key (promoted from CSS inline literal) ====================

    private static final String MODEL_REGISTRY_KEY = "models";

    public ModelRegistrySettingsService(CodemossSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    // ==================== Model Registry Config Management ====================

    /**
     * Read the effective model registry = merge(persisted user layer, read-only defaults).
     * Read-only defaults (Claude 4 roles from settings.json + Codex from config.toml) are
     * computed at runtime and never persisted.
     */
    public ModelRegistryConfig getModelRegistry() {
        try {
            return ReadOnlyDefaultModels.mergeWithReadOnlyDefaults(readPersistedUserLayer());
        } catch (Exception e) {
            LOG.warn("[ModelRegistrySettings] Failed to read model registry, using read-only defaults: " + e.getMessage());
            return ReadOnlyDefaultModels.mergeWithReadOnlyDefaults(new ModelRegistryConfig(java.util.List.of()));
        }
    }

    /**
     * Save the user-layer model registry. Read-only items are stripped (never persisted).
     * New entries conflicting with read-only default keys are rejected; validation runs on
     * the effective registry (user layer + read-only defaults) so the read-only roles
     * guarantee "at least one enabled" — an empty user layer is therefore valid.
     */
    public ModelConfigValidator.ValidationResult setModelRegistry(ModelRegistryConfig registry) {
        ModelRegistryConfig userOnly = stripReadOnly(registry);
        ModelConfigValidator.ValidationResult conflict = checkNoNewConflictsWithReadOnly(userOnly);
        if (!conflict.isValid()) {
            LOG.warn("[ModelRegistrySettings] Model registry conflicts with read-only defaults, not saving: "
                    + conflict.errors());
            return conflict;
        }
        ModelConfigValidator.ValidationResult validation =
                ModelConfigValidator.validate(ReadOnlyDefaultModels.mergeWithReadOnlyDefaults(userOnly));
        if (!validation.isValid()) {
            LOG.warn("[ModelRegistrySettings] Model registry validation failed, not saving: " + validation.errors());
            return validation;
        }
        try {
            JsonObject config = settingsService.readConfig();
            config.add(MODEL_REGISTRY_KEY, serializeModelRegistry(userOnly));
            settingsService.writeConfig(config);
            LOG.info("[ModelRegistrySettings] Saved model registry");
            return validation;
        } catch (Exception e) {
            LOG.error("[ModelRegistrySettings] Failed to save model registry: " + e.getMessage());
            var errors = new java.util.ArrayList<String>();
            errors.add("保存失败: " + e.getMessage());
            return new ModelConfigValidator.ValidationResult(errors, java.util.List.of());
        }
    }

    /**
     * Read the raw persisted user layer without read-only defaults and without the
     * getDefault() fallback. Missing/invalid config returns an empty user layer.
     */
    private ModelRegistryConfig readPersistedUserLayer() {
        try {
            JsonObject config = settingsService.readConfig();
            if (!config.has(MODEL_REGISTRY_KEY) || !config.get(MODEL_REGISTRY_KEY).isJsonObject()) {
                return new ModelRegistryConfig(java.util.List.of());
            }
            ModelRegistryConfig parsed = parseModelRegistry(config.getAsJsonObject(MODEL_REGISTRY_KEY));
            return stripReadOnly(parsed); // 防御:磁盘上不应残留只读项
        } catch (Exception e) {
            return new ModelRegistryConfig(java.util.List.of());
        }
    }

    /** 剥离 readOnly=true 项(后端权威:只读默认永不进持久化)。 */
    private static ModelRegistryConfig stripReadOnly(ModelRegistryConfig registry) {
        java.util.List<ModelConfig> userOnly = new java.util.ArrayList<>();
        for (ModelConfig model : registry.models()) {
            if (!model.readOnly()) {
                userOnly.add(model);
            }
        }
        return new ModelRegistryConfig(userOnly);
    }

    /**
     * 仅拦截"新增"冲突:用户层中、与只读默认键相同、且当前磁盘用户层不存在的项。
     * legacy 同键项放行(合并时 role 被跳过 / codex 被用户覆盖),避免阻塞无关保存。
     */
    private ModelConfigValidator.ValidationResult checkNoNewConflictsWithReadOnly(ModelRegistryConfig incoming) {
        java.util.Set<String> currentKeys = new java.util.HashSet<>();
        for (ModelConfig model : readPersistedUserLayer().models()) {
            currentKeys.add(ReadOnlyDefaultModels.dedupKey(model.provider(), model.id()));
        }
        java.util.Set<String> readOnlyKeys = new java.util.HashSet<>();
        for (ModelConfig model : ReadOnlyDefaultModels.compute()) {
            readOnlyKeys.add(ReadOnlyDefaultModels.dedupKey(model.provider(), model.id()));
        }
        java.util.List<String> errors = new java.util.ArrayList<>();
        for (ModelConfig model : incoming.models()) {
            String key = ReadOnlyDefaultModels.dedupKey(model.provider(), model.id());
            if (readOnlyKeys.contains(key) && !currentKeys.contains(key)) {
                errors.add("模型 " + model.id() + " 与配置文件默认模型冲突,无法新增");
            }
        }
        return errors.isEmpty()
                ? new ModelConfigValidator.ValidationResult(java.util.List.of(), java.util.List.of())
                : new ModelConfigValidator.ValidationResult(errors, java.util.List.of());
    }

    /**
     * 序列化当前 effective registry 为 JSON 字符串,供提供商切换/登录后推送刷新(下发前端)。
     *
     * <p>必须复用 {@link ModelRegistryService#serialize} 以下发 supportedReasoningLevels 等
     * 派生字段;否则前端 ReasoningSelect 拿不到档位会整体隐藏(H3)。与写盘路径
     * {@code serializeModelRegistry}({@link #setModelRegistry},只持久化原始字段、派生字段不落盘)
     * 刻意区分:下发需派生字段,写盘不需要(避免双写)。
     */
    public String getModelRegistryJson() {
        return ModelRegistryService.serialize(getModelRegistry()).toString();
    }

    private ModelRegistryConfig parseModelRegistry(JsonObject modelRegistryObj) {
        List<ModelConfig> models = new java.util.ArrayList<>();
        if (modelRegistryObj.has("items") && modelRegistryObj.get("items").isJsonArray()) {
            JsonArray items = modelRegistryObj.getAsJsonArray("items");
            for (JsonElement item : items) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject obj = item.getAsJsonObject();
                String id = readString(obj, "id");
                String provider = readString(obj, "provider");
                String role = readString(obj, "role");
                String label = readString(obj, "label");
                String actualModel = readString(obj, "actualModel");
                String description = readString(obj, "description");
                int contextWindow = obj.has("contextWindow") && obj.get("contextWindow").isJsonPrimitive()
                        ? obj.get("contextWindow").getAsInt()
                        : CommonConstants.DEFAULT_CONTEXT_WINDOW;
                boolean supports1MContext = obj.has("supports1MContext")
                        && obj.get("supports1MContext").getAsBoolean();
                boolean enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
                models.add(new ModelConfig(id, provider, role, label, actualModel,
                        description, contextWindow, supports1MContext, enabled));
            }
        }
        return new ModelRegistryConfig(models);
    }

    private JsonObject serializeModelRegistry(ModelRegistryConfig registry) {
        JsonObject root = new JsonObject();
        JsonArray items = new JsonArray();
        for (ModelConfig model : registry.models()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", model.id());
            obj.addProperty("provider", model.provider());
            obj.addProperty("role", model.role());
            obj.addProperty("label", model.label());
            if (model.actualModel() == null || model.actualModel().isEmpty()) {
                obj.add("actualModel", JsonNull.INSTANCE);
            } else {
                obj.addProperty("actualModel", model.actualModel());
            }
            if (model.description() == null || model.description().isEmpty()) {
                obj.add("description", JsonNull.INSTANCE);
            } else {
                obj.addProperty("description", model.description());
            }
            obj.addProperty("contextWindow", model.contextWindow());
            obj.addProperty("supports1MContext", model.supports1MContext());
            obj.addProperty("enabled", model.enabled());
            obj.addProperty("readOnly", model.readOnly());
            items.add(obj);
        }
        root.add("items", items);
        return root;
    }

    private static String readString(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        return obj.get(key).getAsString();
    }
}
