package com.github.claudecodegui.settings;

import com.github.claudecodegui.common.ClaudeRole;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.config.ModelConfig;
import com.github.claudecodegui.config.ModelRegistryConfig;
import com.github.claudecodegui.reasoning.ReasoningCapabilities;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    /** payload root 级 wire key:provider 默认档位回退表(模型无 registry 条目时前端据此回退)。 */
    private static final String PROVIDER_DEFAULTS_KEY = "providerDefaults";

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
            obj.addProperty("identifier", model.identifier());
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
            // supportedReasoningLevels 为派生字段:claude 由 role 权威计算,其余 provider 由
            // ReasoningCapabilities 数据表派生,均不存入 ModelConfig(避免前后端双写)。
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
        // providerDefaults:root 级回退表(provider → 默认档位数组),供前端在模型无 registry
        // 条目时按 provider 回退。来源为 ReasoningCapabilities provider 默认集;claude 不含
        // (走 role 派生),dsh/minimax 不含(无 reasoning 能力)。
        JsonObject providerDefaults = new JsonObject();
        for (Map.Entry<String, List<String>> entry : ReasoningCapabilities.providerDefaults().entrySet()) {
            var levelsArr = new com.google.gson.JsonArray();
            for (String lvl : entry.getValue()) {
                levelsArr.add(lvl);
            }
            providerDefaults.add(entry.getKey(), levelsArr);
        }
        root.add(PROVIDER_DEFAULTS_KEY, providerDefaults);
        return root;
    }

    /**
     * 派生字段:该模型支持的 reasoning effort 级别。
     * <p>
     * claude 走 role 派生({@link ClaudeRole#reasoningLevels()};role 未知 → null);
     * 其余有能力的 provider 走 {@link ReasoningCapabilities#levelsFor} 数据表
     * (actualModel 非空优先,否则 id;模型未知 → provider 默认集)。
     * 无能力 provider(dsh/minimax)未注册,返回 {@code null}(serialize 时跳过该字段,前端不渲染)。
     * <p>
     * provider 能力经 {@link ModelCapabilityProvider} 注册表查表(总则五·开闭 / E5)。
     * 查表用 provider 小写精确匹配(不归一、不 fallback)。
     */
    private static List<String> reasoningLevelsFor(ModelConfig model) {
        ModelCapabilityProvider capability = model.provider() == null
                ? null
                : CAPABILITY_PROVIDERS.get(model.provider().toLowerCase(Locale.ROOT));
        return capability != null ? capability.reasoningLevels(model) : null;
    }

    /**
     * 模型能力提供者:per-provider 的派生能力(总则五·开闭 / E5)。
     * 新增 provider 若具 reasoning 能力,只需在 {@link #CAPABILITY_PROVIDERS} 注册一个 entry。
     */
    interface ModelCapabilityProvider {
        String provider();

        List<String> reasoningLevels(ModelConfig model);
    }

    /**
     * 数据表驱动的能力提供者:delegates 到 {@link ReasoningCapabilities#levelsFor},
     * modelId 取 actualModel(非空优先)否则 id。
     */
    private static ModelCapabilityProvider tableDriven(ProviderType type) {
        return new ModelCapabilityProvider() {
            @Override
            public String provider() {
                return type.value();
            }

            @Override
            public List<String> reasoningLevels(ModelConfig model) {
                String modelId = model.actualModel() != null && !model.actualModel().isBlank()
                        ? model.actualModel()
                        : model.id();
                return ReasoningCapabilities.levelsFor(type.value(), modelId);
            }
        };
    }

    /**
     * 能力注册表:provider(小写)→ 能力提供者。claude 走 role 派生;codex/grok/kimi/pi/omp/opencode
     * 走 {@link ReasoningCapabilities} 数据表;dsh/minimax 不注册(无 reasoning 能力,返回 null)。
     */
    private static final Map<String, ModelCapabilityProvider> CAPABILITY_PROVIDERS = Map.of(
            CommonConstants.PROVIDER_CLAUDE, new ModelCapabilityProvider() {
                @Override
                public String provider() {
                    return CommonConstants.PROVIDER_CLAUDE;
                }

                @Override
                public List<String> reasoningLevels(ModelConfig model) {
                    ClaudeRole role = ClaudeRole.fromShortName(model.role());
                    return role == null ? null : role.reasoningLevels();
                }
            },
            ProviderType.CODEX.value(), tableDriven(ProviderType.CODEX),
            ProviderType.GROK.value(), tableDriven(ProviderType.GROK),
            ProviderType.KIMI.value(), tableDriven(ProviderType.KIMI),
            ProviderType.PI.value(), tableDriven(ProviderType.PI),
            ProviderType.OMP.value(), tableDriven(ProviderType.OMP),
            ProviderType.OPENCODE.value(), tableDriven(ProviderType.OPENCODE)
    );

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
                String identifier = readString(obj, "identifier");
                String provider = readString(obj, "provider");
                String role = readString(obj, "role");
                String label = readString(obj, "label");
                String actualModel = readString(obj, "actualModel");
                String description = readString(obj, "description");
                int contextWindow = obj.has("contextWindow") && !obj.get("contextWindow").isJsonNull()
                        ? obj.get("contextWindow").getAsInt()
                        : CommonConstants.DEFAULT_CONTEXT_WINDOW;
                boolean supports1MContext = obj.has("supports1MContext")
                        && !obj.get("supports1MContext").isJsonNull()
                        && obj.get("supports1MContext").getAsBoolean();
                boolean enabled = !obj.has("enabled") || obj.get("enabled").isJsonNull()
                        || obj.get("enabled").getAsBoolean();
                models.add(new ModelConfig(id, identifier, provider, role, label, actualModel, description,
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
