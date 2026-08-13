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
 * 模型注册表领域 Service。
 *
 * <p>负责用户层与只读默认模型的合并、冲突检查、校验和序列化；持久化仅依赖
 * {@link ConfigStore}，写操作在单一 update 临界区内完成。Facade 只保留兼容调用面。
 */
public final class ModelRegistrySettingsService {
    private static final Logger LOG = Logger.getInstance(ModelRegistrySettingsService.class);

    private final ConfigStore configStore;

    // ==================== Field key (promoted from CSS inline literal) ====================

    private static final String MODEL_REGISTRY_KEY = "models";

    public ModelRegistrySettingsService(ConfigStore configStore) {
        this.configStore = configStore;
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
            JsonObject serialized = serializeModelRegistry(userOnly);
            configStore.update(config -> config.add(MODEL_REGISTRY_KEY, serialized));
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
            JsonObject config = configStore.read();
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
            currentKeys.add(ReadOnlyDefaultModels.dedupKey(model));
        }
        java.util.Set<String> readOnlyKeys = new java.util.HashSet<>();
        for (ModelConfig model : ReadOnlyDefaultModels.compute()) {
            readOnlyKeys.add(ReadOnlyDefaultModels.dedupKey(model));
        }
        java.util.List<String> errors = new java.util.ArrayList<>();
        for (ModelConfig model : incoming.models()) {
            String key = ReadOnlyDefaultModels.dedupKey(model);
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
                String identifier = readString(obj, "identifier");
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
                models.add(new ModelConfig(id, identifier, provider, role, label, actualModel,
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
            obj.addProperty("identifier", model.identifier());
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
