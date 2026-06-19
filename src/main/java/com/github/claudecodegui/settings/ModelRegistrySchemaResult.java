package com.github.claudecodegui.settings;

import com.google.gson.JsonObject;

/**
 * Schema description for the model registry form. Mirrors the previous
 * hardcoded schema emitted by SettingsHandler.handleGetModelRegistrySchema.
 */
public final class ModelRegistrySchemaResult {
    private final JsonObject schema;

    public ModelRegistrySchemaResult(JsonObject schema) {
        this.schema = schema;
    }

    public static ModelRegistrySchemaResult defaultSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("title", "模型配置中心");
        schema.addProperty("description", "配置 Claude/Codex 可选模型、上下文窗口与 1M 能力。错误配置会被后端拒绝。");
        schema.addProperty("providers", "claude, codex");
        schema.addProperty("contextWindow", "8192 到 2000000 的整数 tokens");
        schema.addProperty("supports1MContext", "为 true 时 contextWindow 必须 >= 1000000");
        return new ModelRegistrySchemaResult(schema);
    }

    public JsonObject schema() { return schema; }
}
