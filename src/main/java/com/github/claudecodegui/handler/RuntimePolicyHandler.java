package com.github.claudecodegui.handler;

import com.github.claudecodegui.config.ProviderRuntimePolicy;
import com.github.claudecodegui.config.RuntimePolicyConfig;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.session.runtime.RuntimeType;
import com.github.claudecodegui.util.GsonHolder;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.HashSet;
import java.util.LinkedHashMap;

/**
 * Handles runtime policy (per-provider SDK/CLI routing) get/set/reset/schema operations.
 *
 * <p>B3 slice: runtime-policy. 自 {@code SettingsHandler} 迁出,逻辑逐字等价。
 */
public class RuntimePolicyHandler {

    private static final Logger LOG = Logger.getInstance(RuntimePolicyHandler.class);

    private final HandlerContext context;
    private final Gson gson = GsonHolder.GSON;

    public RuntimePolicyHandler(HandlerContext context) {
        this.context = context;
    }

    public void handleGetRuntimePolicy() {
        try {
            var policyConfig = context.getSettingsService().getRuntimePolicy();
            JsonObject response = serializeRuntimePolicyToJson(policyConfig);
            context.dispatchEvent(DownstreamEvent.RUNTIME_POLICY.value(), context.escapeJs(response.toString()));
        } catch (Exception e) {
            LOG.error("[RuntimePolicyHandler] Failed to get runtime policy: " + e.getMessage(), e);
            context.dispatchEvent(DownstreamEvent.RUNTIME_POLICY_ERROR.value(), context.escapeJs("获取路由策略失败: " + e.getMessage()));
        }
    }

    public void handleSetRuntimePolicy(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            var policyConfig = parseRuntimePolicyFromJson(json);
            var result = context.getSettingsService().setRuntimePolicy(policyConfig);
            if (result.isValid()) {
                // 保存成功，推送最新配置
                var savedConfig = context.getSettingsService().getRuntimePolicy();
                JsonObject response = serializeRuntimePolicyToJson(savedConfig);
                response.addProperty("success", true);
                context.dispatchEvent(DownstreamEvent.RUNTIME_POLICY_UPDATED.value(), context.escapeJs(response.toString()));
            } else {
                // 校验失败，返回错误
                JsonObject response = new JsonObject();
                response.addProperty("success", false);
                var errorsArray = new JsonArray();
                result.errors().forEach(errorsArray::add);
                response.add("errors", errorsArray);
                context.dispatchEvent(DownstreamEvent.RUNTIME_POLICY_UPDATED.value(), context.escapeJs(response.toString()));
            }
        } catch (Exception e) {
            LOG.error("[RuntimePolicyHandler] Failed to set runtime policy: " + e.getMessage(), e);
            JsonObject response = new JsonObject();
            response.addProperty("success", false);
            var errorsArray = new JsonArray();
            errorsArray.add("保存失败: " + e.getMessage());
            response.add("errors", errorsArray);
            context.dispatchEvent(DownstreamEvent.RUNTIME_POLICY_UPDATED.value(), context.escapeJs(response.toString()));
        }
    }

    public void handleResetRuntimePolicy() {
        try {
            context.getSettingsService().resetRuntimePolicy();
            var defaultConfig = context.getSettingsService().getRuntimePolicy();
            JsonObject response = serializeRuntimePolicyToJson(defaultConfig);
            response.addProperty("success", true);
            response.addProperty("reset", true);
            context.dispatchEvent(DownstreamEvent.RUNTIME_POLICY_UPDATED.value(), context.escapeJs(response.toString()));
        } catch (Exception e) {
            LOG.error("[RuntimePolicyHandler] Failed to reset runtime policy: " + e.getMessage(), e);
        }
    }

    public void handleGetRuntimePolicySchema() {
        // 返回 schema 描述，前端据此渲染表单与提示
        JsonObject schema = new JsonObject();
        schema.addProperty("title", "路由策略配置");
        schema.addProperty("description", "配置各 provider 的 runtime 模式（SDK/CLI）。修改后立即生效。");

        var claudeSchema = new JsonObject();
        claudeSchema.addProperty("type", "object");
        claudeSchema.addProperty("description", "Claude provider 路由策略");
        var claudeProps = new JsonObject();
        claudeProps.addProperty("enabled", "是否启用 (boolean)");
        claudeProps.addProperty("supported", "支持的 runtime 列表 (array: SDK, CLI)");
        claudeProps.addProperty("default", "默认 runtime (SDK 或 CLI)");
        claudeSchema.add("properties", claudeProps);
        schema.add(ProviderType.CLAUDE.value(), claudeSchema);

        var codexSchema = new JsonObject();
        codexSchema.addProperty("type", "object");
        codexSchema.addProperty("description", "Codex provider 路由策略");
        var codexProps = new JsonObject();
        codexProps.addProperty("enabled", "是否启用 (boolean)");
        codexProps.addProperty("supported", "支持的 runtime 列表 (array: SDK, CLI)");
        codexProps.addProperty("default", "默认 runtime (SDK 或 CLI)");
        codexSchema.add("properties", codexProps);
        schema.add(ProviderType.CODEX.value(), codexSchema);

        context.dispatchEvent(DownstreamEvent.RUNTIME_POLICY_SCHEMA.value(), context.escapeJs(schema.toString()));
    }

    private JsonObject serializeRuntimePolicyToJson(RuntimePolicyConfig policyConfig) {
        JsonObject result = new JsonObject();
        JsonObject providers = new JsonObject();
        for (var entry : policyConfig.providers().entrySet()) {
            String key = entry.getKey().value();
            var policy = entry.getValue();
            JsonObject policyObj = new JsonObject();
            policyObj.addProperty("enabled", policy.enabled());
            var supportedArray = new JsonArray();
            if (policy.supported() != null) {
                for (var rt : policy.supported()) {
                    supportedArray.add(rt.name());
                }
            }
            policyObj.add("supported", supportedArray);
            if (policy.defaultRuntime() != null) {
                policyObj.addProperty("default", policy.defaultRuntime().name());
            }
            providers.add(key, policyObj);
        }
        result.add("providers", providers);
        return result;
    }

    private RuntimePolicyConfig parseRuntimePolicyFromJson(JsonObject json) {
        var config = new RuntimePolicyConfig();
        var providers = new LinkedHashMap<ProviderType, ProviderRuntimePolicy>();

        if (json.has("providers") && json.get("providers").isJsonObject()) {
            JsonObject providersObj = json.getAsJsonObject("providers");
            for (String key : providersObj.keySet()) {
                var pt = ProviderType.fromString(key);
                if (providersObj.get(key).isJsonObject()) {
                    JsonObject policyObj = providersObj.getAsJsonObject(key);
                    boolean enabled = policyObj.has("enabled") && policyObj.get("enabled").getAsBoolean();
                    var supported = new HashSet<RuntimeType>();
                    if (policyObj.has("supported") && policyObj.get("supported").isJsonArray()) {
                        for (var el : policyObj.getAsJsonArray("supported")) {
                            String rtStr = el.getAsString();
                            if ("SDK".equalsIgnoreCase(rtStr)) {
                                supported.add(RuntimeType.SDK);
                            } else if ("CLI".equalsIgnoreCase(rtStr)) {
                                supported.add(RuntimeType.CLI);
                            }
                        }
                    }
                    RuntimeType defaultRt = null;
                    if (policyObj.has("default") && !policyObj.get("default").isJsonNull()) {
                        String defStr = policyObj.get("default").getAsString();
                        if ("SDK".equalsIgnoreCase(defStr)) {
                            defaultRt = RuntimeType.SDK;
                        } else if ("CLI".equalsIgnoreCase(defStr)) {
                            defaultRt = RuntimeType.CLI;
                        }
                    }
                    try {
                        providers.put(pt, new ProviderRuntimePolicy(enabled, supported, defaultRt));
                    } catch (Exception e) {
                        LOG.warn("[RuntimePolicyHandler] Invalid runtime policy for " + key + ": " + e.getMessage());
                    }
                }
            }
        }
        config.setProviders(providers);
        return config;
    }
}
