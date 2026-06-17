package com.github.claudecodegui.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Configurable model registry. Defaults mirror the previous hard-coded model
 * lists and context limits.
 */
public class ModelRegistryConfig {
    private static final ModelRegistryConfig DEFAULT = buildDefault();

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

    public static ModelRegistryConfig getDefault() {
        return new ModelRegistryConfig(DEFAULT.models);
    }

    public static String stripCapacitySuffix(String modelId) {
        if (modelId == null) {
            return "";
        }
        return modelId.trim().replaceFirst("(?i)\\s*\\[[0-9.]+[kKmM]\\]\\s*$", "");
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
        defaults.add(new ModelConfig("claude-sonnet-4-6", "claude", "Sonnet 4.6",
                "Sonnet 4.6 · Use the default model", 200_000, true, true));
        defaults.add(new ModelConfig("claude-opus-4-8", "claude", "Opus 4.8",
                "Opus 4.8 · Latest and most capable", 200_000, true, true));
        defaults.add(new ModelConfig("claude-opus-4-7", "claude", "Opus 4.7",
                "Opus 4.7 · Previous flagship model", 200_000, true, true));
        defaults.add(new ModelConfig("claude-opus-4-6", "claude", "Opus 4.6",
                "Opus 4.6 for long sessions", 200_000, true, true));
        defaults.add(new ModelConfig("claude-haiku-4-5", "claude", "Haiku 4.5",
                "Haiku 4.5 · Fastest for quick answers", 200_000, false, true));

        defaults.add(new ModelConfig("gpt-5.5", "codex", "GPT-5.5",
                "Latest frontier model with stronger capabilities.", 1_000_000, true, true));
        defaults.add(new ModelConfig("gpt-5.4", "codex", "GPT-5.4",
                "Latest frontier model with enhanced capabilities.", 1_000_000, true, true));
        defaults.add(new ModelConfig("gpt-5.2-codex", "codex", "GPT-5.2-Codex",
                "Frontier agentic coding model.", 258_000, false, true));
        defaults.add(new ModelConfig("gpt-5.1-codex-max", "codex", "GPT-5.1-Codex-Max",
                "Codex-optimized flagship for deep and fast reasoning.", 258_000, false, true));
        defaults.add(new ModelConfig("gpt-5.4-mini", "codex", "GPT-5.4-Mini",
                "Smaller frontier agentic coding model.", 400_000, false, true));
        defaults.add(new ModelConfig("gpt-5.3-codex", "codex", "GPT-5.3-Codex",
                "Latest frontier agentic coding model with enhanced capabilities.", 258_000, false, true));
        defaults.add(new ModelConfig("gpt-5.3-codex-spark", "codex", "GPT-5.3-Codex-Spark",
                "Ultra-fast coding model.", 258_000, false, true));
        defaults.add(new ModelConfig("gpt-5.2", "codex", "GPT-5.2",
                "Optimized for professional work and long-running agents.", 258_000, false, true));
        defaults.add(new ModelConfig("gpt-5.1-codex-mini", "codex", "GPT-5.1-Codex-Mini",
                "Optimized for Codex. Cheaper, faster, but less capable.", 128_000, false, true));
        defaults.add(new ModelConfig("o3", "codex", "o3", "OpenAI reasoning model.", 200_000, false, true));
        defaults.add(new ModelConfig("o3-mini", "codex", "o3-mini", "OpenAI compact reasoning model.", 200_000, false, true));
        defaults.add(new ModelConfig("o1", "codex", "o1", "OpenAI reasoning model.", 200_000, false, true));
        defaults.add(new ModelConfig("o1-mini", "codex", "o1-mini", "OpenAI compact reasoning model.", 128_000, false, true));
        defaults.add(new ModelConfig("o1-preview", "codex", "o1-preview", "OpenAI preview reasoning model.", 128_000, false, true));
        defaults.add(new ModelConfig("gpt-4o", "codex", "GPT-4o", "OpenAI GPT-4o model.", 128_000, false, true));
        defaults.add(new ModelConfig("gpt-4o-mini", "codex", "GPT-4o Mini", "OpenAI GPT-4o mini model.", 128_000, false, true));
        defaults.add(new ModelConfig("gpt-4-turbo", "codex", "GPT-4 Turbo", "OpenAI GPT-4 Turbo model.", 128_000, false, true));
        defaults.add(new ModelConfig("gpt-4", "codex", "GPT-4", "OpenAI GPT-4 model.", 8_192, false, true));
        return new ModelRegistryConfig(defaults);
    }
}
