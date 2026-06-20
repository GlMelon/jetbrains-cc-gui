package com.github.claudecodegui.config;

import com.github.claudecodegui.common.ClaudeRole;
import com.github.claudecodegui.common.CommonConstants;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates the persisted model registry before it is used or saved.
 */
public final class ModelConfigValidator {
    public static final int MIN_CONTEXT_WINDOW = 8_192;
    public static final int MAX_CONTEXT_WINDOW = 2_000_000;
    public static final int MAX_MODEL_ID_LENGTH = 256;

    private ModelConfigValidator() {
    }

    public record ValidationResult(List<String> errors, List<String> warnings) {
        public boolean isValid() {
            return errors == null || errors.isEmpty();
        }
    }

    public static ValidationResult validate(ModelRegistryConfig config) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (config == null || config.models() == null) {
            errors.add("models config is required");
            return new ValidationResult(errors, warnings);
        }
        if (config.models().isEmpty()) {
            errors.add("models config must include at least one model");
            return new ValidationResult(errors, warnings);
        }

        Set<String> seen = new HashSet<>();
        boolean hasEnabledClaude = false;
        boolean hasEnabledCodex = false;
        for (ModelConfig rawModel : config.models()) {
            if (rawModel == null) {
                errors.add("model entry cannot be null");
                continue;
            }
            ModelConfig model = rawModel.normalized();
            if (model.id().isEmpty()) {
                errors.add("model id is required");
                continue;
            }
            if (model.id().length() > MAX_MODEL_ID_LENGTH) {
                errors.add("model id is too long: " + model.id());
            }
            if (!CommonConstants.PROVIDER_CLAUDE.equals(model.provider()) && !CommonConstants.PROVIDER_CODEX.equals(model.provider())) {
                errors.add("model provider must be claude or codex: " + model.id());
            }
            if (CommonConstants.PROVIDER_CLAUDE.equals(model.provider())) {
                if (!isClaudeRole(model.role())) {
                    errors.add("claude model role must be sonnet, opus, fable, or haiku: " + model.id());
                }
            }
            if (CommonConstants.PROVIDER_CODEX.equals(model.provider()) && !model.role().isBlank()) {
                errors.add("codex model role must be empty: " + model.id());
            }
            String duplicateKey = model.provider() + "\n" + model.id().toLowerCase(Locale.ROOT);
            if (!seen.add(duplicateKey)) {
                errors.add("duplicate model id for provider " + model.provider() + ": " + model.id());
            }
            if (model.contextWindow() < MIN_CONTEXT_WINDOW || model.contextWindow() > MAX_CONTEXT_WINDOW) {
                errors.add("contextWindow out of range for " + model.id());
            }
            if (model.supports1MContext() && !CommonConstants.PROVIDER_CLAUDE.equals(model.provider())
                    && model.contextWindow() < CommonConstants.ONE_MILLION_CONTEXT_WINDOW) {
                errors.add("supports1MContext requires contextWindow >= 1000000 for " + model.id());
            }
            if (model.enabled() && CommonConstants.PROVIDER_CLAUDE.equals(model.provider())) {
                hasEnabledClaude = true;
            }
            if (model.enabled() && CommonConstants.PROVIDER_CODEX.equals(model.provider())) {
                hasEnabledCodex = true;
            }
        }

        if (!hasEnabledClaude && !hasEnabledCodex) {
            errors.add("at least one model must be enabled");
        }

        return new ValidationResult(errors, warnings);
    }

    private static boolean isClaudeRole(String role) {
        return ClaudeRole.fromShortName(role) != null;
    }
}
