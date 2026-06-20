package com.github.claudecodegui.config;

import com.github.claudecodegui.common.ClaudeRole;
import com.github.claudecodegui.common.CommonConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

        if (CommonConstants.PROVIDER_CODEX.equals(normalizedProvider)) {
            ModelConfig model = configured.orElse(null);
            String actual = model != null && !model.actualModel().isBlank()
                    ? model.actualModel()
                    : baseSelected;
            return new ResolvedModelSelection(
                    selected,
                    null,
                    actual,
                    model != null ? model.contextWindow() : CommonConstants.DEFAULT_CONTEXT_WINDOW,
                    model != null && model.supports1MContext()
            );
        }

        ModelConfig model = configured.orElse(null);
        String role = model != null && !model.role().isBlank()
                ? model.role()
                : roleFromModelId(baseSelected);
        String actual = model != null ? applyRequestCapacity(selected, model.actualModel()) : "";
        return new ResolvedModelSelection(
                selected,
                role,
                actual.isBlank() ? null : actual,
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

    private static String normalizeProvider(String provider) {
        return CommonConstants.PROVIDER_CODEX.equalsIgnoreCase(provider)
                ? CommonConstants.PROVIDER_CODEX
                : CommonConstants.PROVIDER_CLAUDE;
    }

    private static String roleFromModelId(String modelId) {
        ClaudeRole role = ClaudeRole.fromModelId(modelId);
        return role != null ? role.shortName() : null;
    }
}
