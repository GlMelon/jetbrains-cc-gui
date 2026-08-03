package com.github.claudecodegui.config;

import com.github.claudecodegui.common.ClaudeRole;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.session.runtime.ProviderType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Configurable model registry. Defaults expose stable Claude role selectors;
 * Codex models are supplied by provider catalogs or user configuration.
 */
public class ModelRegistryConfig {
    private static final ModelRegistryConfig DEFAULT = buildDefault();
    private static final String SUFFIX_1M = "[1m]";

    private final List<ModelConfig> models;

    public ModelRegistryConfig(List<ModelConfig> models) {
        this.models = models == null ? new ArrayList<>() : normalize(models);
    }

    public List<ModelConfig> models() {
        return List.copyOf(models);
    }

    public ModelConfigValidator.ValidationResult validate() {
        return ModelConfigValidator.validate(this);
    }

    public Optional<ModelConfig> find(String modelId) {
        String baseModel = stripCapacitySuffix(modelId);
        return models.stream()
                .filter(ModelConfig::enabled)
                .filter(model -> model.id().equalsIgnoreCase(baseModel))
                .findFirst();
    }

    public Optional<ModelConfig> find(String provider, String modelId) {
        String normalizedProvider = normalizeProvider(provider);
        String baseModel = stripCapacitySuffix(modelId);
        return models.stream()
                .filter(ModelConfig::enabled)
                .filter(model -> model.provider().equals(normalizedProvider))
                .filter(model -> model.id().equalsIgnoreCase(baseModel))
                .findFirst();
    }

    public ResolvedModelSelection resolveModelSelection(String provider, String selectedModel) {
        String normalizedProvider = normalizeProvider(provider);
        String selected = selectedModel == null ? "" : selectedModel.trim();
        String baseSelected = stripCapacitySuffix(selected);
        Optional<ModelConfig> configured = find(normalizedProvider, selected);
        ModelConfig model = configured.orElse(null);

        // per-provider 策略查表(总则五·开闭 / E4):取代原 codex/claude if 分支,
        // 新增 provider 只需在 STRATEGIES 注册策略,本方法路由主体不变。
        ModelSelectionStrategy strategy = STRATEGIES.get(normalizedProvider);
        if (strategy == null) {
            // 理论不可达:normalizeProvider 已归一到 claude/codex 之一
            throw new IllegalStateException("No model selection strategy for provider: " + normalizedProvider);
        }
        String role = strategy.resolveRole(model, baseSelected);
        String actual = strategy.resolveActualModel(model, selected, baseSelected);
        return new ResolvedModelSelection(
                selected,
                role,
                actual,
                model != null ? model.contextWindow() : CommonConstants.DEFAULT_CONTEXT_WINDOW,
                model != null && model.supports1MContext()
        );
    }

    public static ModelRegistryConfig getDefault() {
        return new ModelRegistryConfig(DEFAULT.models);
    }

    public static String stripCapacitySuffix(String modelId) {
        if (modelId == null) {
            return "";
        }
        return modelId.trim().replaceFirst("(?i)\\s*\\[[0-9.]+[kKmM]\\]\\s*$", "");
    }

    public record ResolvedModelSelection(
            String selectedModel,
            String role,
            String actualModel,
            int contextWindow,
            boolean supports1MContext
    ) {
    }

    private static List<ModelConfig> normalize(List<ModelConfig> source) {
        List<ModelConfig> normalized = new ArrayList<>();
        for (ModelConfig model : source) {
            if (model != null) {
                normalized.add(model.normalized());
            }
        }
        return normalized;
    }

    private static ModelRegistryConfig buildDefault() {
        List<ModelConfig> defaults = new ArrayList<>();
        // roleId / shortName / contextWindow / supports1MContext 由 ClaudeRole 单一数据源派生,
        // 消除重复的 "claude-role-*" 字面量;description 为 UI 展示文案,保留于此。
        defaults.add(roleConfig(ClaudeRole.SONNET, "Sonnet role · Uses ANTHROPIC_DEFAULT_SONNET_MODEL"));
        defaults.add(roleConfig(ClaudeRole.OPUS, "Opus role · Uses ANTHROPIC_DEFAULT_OPUS_MODEL"));
        defaults.add(roleConfig(ClaudeRole.FABLE, "Fable role · Uses ANTHROPIC_DEFAULT_FABLE_MODEL"));
        defaults.add(roleConfig(ClaudeRole.HAIKU, "Haiku role · Uses ANTHROPIC_DEFAULT_HAIKU_MODEL"));
        return new ModelRegistryConfig(defaults);
    }

    private static ModelConfig roleConfig(ClaudeRole role, String description) {
        return new ModelConfig(
                role.roleId(),
                CommonConstants.PROVIDER_CLAUDE,
                role.shortName(),
                capitalize(role.shortName()),
                "",
                description,
                role.contextWindow(),
                role.supports1MContext(),
                true
        );
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    private static String applyRequestCapacity(String selectedModel, String actualModel) {
        if (actualModel == null || actualModel.isBlank()) {
            return "";
        }
        String baseActual = actualModel.trim().replaceFirst("(?i)\\[1m\\]$", "");
        return has1MSuffix(selectedModel) ? baseActual + SUFFIX_1M : baseActual;
    }

    private static boolean has1MSuffix(String modelId) {
        return modelId != null && modelId.trim().matches("(?i).*\\[1m\\]$");
    }

    /**
     * 根据 longContextEnabled 意图给 model id 追加 [1m] 容量后缀(D5:1M 构造下沉)。
     * 前端不再构造 [1m],只上送 longContextEnabled 布尔(已与 supports1M 取并集);
     * 后端据此权威决定是否追加后缀,保持与旧前端 apply1MContextSuffix 行为等价。
     */
    public static String apply1MSuffix(String modelId, boolean longContextEnabled) {
        String base = stripCapacitySuffix(modelId);
        return longContextEnabled ? base + SUFFIX_1M : base;
    }

    private static String normalizeProvider(String provider) {
        // 委托 ProviderType.fromString 归一(总则五·开闭 / E4,与 CliSessionManager.normalizeInterruptProvider 范式一致),
        // 消除手写 if(PROVIDER_CODEX.equalsIgnoreCase) 分支。fromString: codex→CODEX→"codex", 其余→CLAUDE→"claude"。
        return ProviderType.fromString(provider).value();
    }

    /**
     * 模型选择策略:per-provider 的 role / actualModel 计算(总则五·开闭 / E4)。
     * 新增 provider 只需在 {@link #STRATEGIES} 注册一个策略,resolveModelSelection 路由主体不变。
     */
    interface ModelSelectionStrategy {
        String provider();

        /** 计算下发 role(null 表示该 provider 无 role 概念,如 Codex)。 */
        String resolveRole(ModelConfig model, String baseSelected);

        /** 计算下发 actualModel(返回 null 表示无 actualModel;语义由策略自定,保持与原 if/else 等价)。 */
        String resolveActualModel(ModelConfig model, String selected, String baseSelected);
    }

    /** 策略注册表:provider → 策略。归一后的 provider 必命中(normalizeProvider 保证 claude/codex/opencode)。 */
    private static final Map<String, ModelSelectionStrategy> STRATEGIES = Map.of(
            CommonConstants.PROVIDER_CLAUDE, claudeStrategy(),
            CommonConstants.PROVIDER_CODEX, codexStrategy(),
            CommonConstants.PROVIDER_OPENCODE, opencodeStrategy()
    );

    private static ModelSelectionStrategy claudeStrategy() {
        return new ModelSelectionStrategy() {
            @Override
            public String provider() {
                return CommonConstants.PROVIDER_CLAUDE;
            }

            @Override
            public String resolveRole(ModelConfig model, String baseSelected) {
                return model != null && !model.role().isBlank()
                        ? model.role()
                        : roleFromModelId(baseSelected);
            }

            @Override
            public String resolveActualModel(ModelConfig model, String selected, String baseSelected) {
                if (model == null) {
                    return null;
                }
                String actual = applyRequestCapacity(selected, model.actualModel());
                return actual.isBlank() ? null : actual;
            }
        };
    }

    private static ModelSelectionStrategy codexStrategy() {
        return new ModelSelectionStrategy() {
            @Override
            public String provider() {
                return CommonConstants.PROVIDER_CODEX;
            }

            @Override
            public String resolveRole(ModelConfig model, String baseSelected) {
                return null;
            }

            @Override
            public String resolveActualModel(ModelConfig model, String selected, String baseSelected) {
                return model != null && !model.actualModel().isBlank()
                        ? model.actualModel()
                        : baseSelected;
            }
        };
    }

    private static ModelSelectionStrategy opencodeStrategy() {
        return new ModelSelectionStrategy() {
            @Override
            public String provider() {
                return CommonConstants.PROVIDER_OPENCODE;
            }

            @Override
            public String resolveRole(ModelConfig model, String baseSelected) {
                // OpenCode 使用 role 概念（与 Claude 类似）
                return model != null && !model.role().isBlank()
                        ? model.role()
                        : roleFromModelId(baseSelected);
            }

            @Override
            public String resolveActualModel(ModelConfig model, String selected, String baseSelected) {
                if (model == null) {
                    return null;
                }
                String actual = applyRequestCapacity(selected, model.actualModel());
                return actual.isBlank() ? null : actual;
            }
        };
    }

    private static String roleFromModelId(String modelId) {
        ClaudeRole role = ClaudeRole.fromModelId(modelId);
        return role != null ? role.shortName() : null;
    }
}
