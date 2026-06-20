package com.github.claudecodegui.settings;

import com.github.claudecodegui.common.ClaudeRole;
import com.github.claudecodegui.config.ModelConfig;
import com.github.claudecodegui.config.ModelRegistryConfig;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Backend service for the configurable model registry.
 *
 * <p>Encapsulates payload (de)serialization, schema assembly and orchestration of
 * read/write/reset. Persistence and validation are delegated to
 * {@link CodemossSettingsService}; this service adds no front-end or action-string
 * coupling.
 */
public final class ModelRegistryService {
    private static final Logger LOG = Logger.getInstance(ModelRegistryService.class);

    private final CodemossSettingsService settingsService;

    public ModelRegistryService(CodemossSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public ModelRegistryResult getRegistry() {
        try {
            return ModelRegistryResult.success(serialize(settingsService.getModelRegistry()));
        } catch (Exception e) {
            LOG.error("[ModelRegistryService] Failed to get model registry: " + e.getMessage(), e);
            return ModelRegistryResult.failure("获取模型配置失败: " + e.getMessage());
        }
    }

    public ModelRegistryResult setRegistry(String payload) {
        try {
            // payload 解析置于 try 内:畸形 JSON 触发的 JsonSyntaxException 在此被捕获,
            // 返回 failure("保存失败: ..."),与原 SettingsHandler.handleSetModelRegistry 逐字等价。
            JsonObject json = GsonHolder.GSON.fromJson(payload, JsonObject.class);
            ModelRegistryConfig registry = parse(json);
            var result = settingsService.setModelRegistry(registry);
            if (result.isValid()) {
                return ModelRegistryResult.success(serialize(settingsService.getModelRegistry()));
            }
            return ModelRegistryResult.failure(result.errors());
        } catch (Exception e) {
            LOG.error("[ModelRegistryService] Failed to set model registry: " + e.getMessage(), e);
            return ModelRegistryResult.failure("保存失败: " + e.getMessage());
        }
    }

    public ModelRegistryResult resetRegistry() {
        try {
            settingsService.resetModelRegistry();
            return ModelRegistryResult.resetSuccess(serialize(settingsService.getModelRegistry()));
        } catch (Exception e) {
            LOG.error("[ModelRegistryService] Failed to reset model registry: " + e.getMessage(), e);
            return ModelRegistryResult.failure("重置模型配置失败: " + e.getMessage());
        }
    }

    public ModelRegistrySchemaResult getSchema() {
        return ModelRegistrySchemaResult.defaultSchema();
    }

    /** Serialize a registry into the {@code {items:[...]}} payload shape expected by the webview. */
    public static JsonObject serialize(ModelRegistryConfig registry) {
        JsonObject root = new JsonObject();
        var items = new com.google.gson.JsonArray();
        for (ModelConfig model : registry.models()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", model.id());
            obj.addProperty("provider", model.provider());
            obj.addProperty("role", model.role());
            obj.addProperty("label", model.label());
            if (model.actualModel() == null || model.actualModel().isEmpty()) {
                obj.add("actualModel", com.google.gson.JsonNull.INSTANCE);
            } else {
                obj.addProperty("actualModel", model.actualModel());
            }
            obj.addProperty("description", model.description());
            obj.addProperty("contextWindow", model.contextWindow());
            obj.addProperty("supports1MContext", model.supports1MContext());
            // supportedReasoningLevels 为派生字段:由 role 权威计算,不存入 ModelConfig
            // (避免前后端双写;自定义 claude 模型的 role 由用户新增模型时选定)。
            List<String> reasoningLevels = reasoningLevelsFor(model);
            if (reasoningLevels != null) {
                var levelsArr = new com.google.gson.JsonArray();
                for (String lvl : reasoningLevels) {
                    levelsArr.add(lvl);
                }
                obj.add("supportedReasoningLevels", levelsArr);
            }
            obj.addProperty("enabled", model.enabled());
            obj.addProperty("readOnly", model.readOnly());
            items.add(obj);
        }
        root.add("items", items);
        return root;
    }

    /**
     * 派生字段:由模型 role 权威计算支持的 reasoning effort 级别。
     * <p>
     * 仅 claude provider 且 role 已知时返回(自定义 claude 模型的 role 由用户新增时选定);
     * 否则返回 {@code null}(serialize 时跳过该字段,前端不渲染)。
     */
    private static List<String> reasoningLevelsFor(ModelConfig model) {
        if (!"claude".equalsIgnoreCase(model.provider()) || model.role() == null || model.role().isBlank()) {
            return null;
        }
        ClaudeRole role = ClaudeRole.fromShortName(model.role());
        return role == null ? null : role.reasoningLevels();
    }

    /** Parse the {@code {items:[...]}} payload back into a {@link ModelRegistryConfig}. */
    public static ModelRegistryConfig parse(JsonObject json) {
        List<ModelConfig> models = new ArrayList<>();
        if (json != null && json.has("items") && json.get("items").isJsonArray()) {
            for (var item : json.getAsJsonArray("items")) {
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
                int contextWindow = obj.has("contextWindow") && !obj.get("contextWindow").isJsonNull()
                        ? obj.get("contextWindow").getAsInt()
                        : 200_000;
                boolean supports1MContext = obj.has("supports1MContext")
                        && !obj.get("supports1MContext").isJsonNull()
                        && obj.get("supports1MContext").getAsBoolean();
                boolean enabled = !obj.has("enabled") || obj.get("enabled").isJsonNull()
                        || obj.get("enabled").getAsBoolean();
                models.add(new ModelConfig(id, provider, role, label, actualModel, description,
                        contextWindow, supports1MContext, enabled));
            }
        }
        return new ModelRegistryConfig(models);
    }

    private static String readString(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        return obj.get(key).getAsString();
    }
}
