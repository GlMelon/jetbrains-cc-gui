package com.github.claudecodegui.model.selection;

import com.github.claudecodegui.common.ClaudeRole;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.config.ModelRegistryConfig;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DefaultModelCapabilityResolver implements ModelCapabilityResolver {
    private static final int DEFAULT_CONTEXT_WINDOW = 200_000;
    private static final int ONE_MILLION_CONTEXT_WINDOW = 1_000_000;
    private static final Pattern CAPACITY_SUFFIX = Pattern.compile("\\s*\\[([0-9.]+)([kKmM])\\]\\s*$");

    private final ModelRegistryConfig registry;

    public DefaultModelCapabilityResolver(ModelRegistryConfig registry) {
        this.registry = registry == null ? ModelRegistryConfig.getDefault() : registry;
    }

    @Override
    public ModelSelectionResult resolve(ModelSelectionRequest request) {
        String provider = normalizeProvider(request.provider());
        String selectedModel = ModelRegistryConfig.stripCapacitySuffix(request.selectedModel());
        ModelRegistryConfig.ResolvedModelSelection registrySelection =
                registry.resolveModelSelection(provider, selectedModel);
        String resolvedActualModel = resolveActualModel(selectedModel, registrySelection);

        boolean supportsLongContext = supportsLongContext(selectedModel, registrySelection);
        boolean requestsOneMillion = request.longContextEnabled()
                && request.requestedContextWindow() != null
                && request.requestedContextWindow() >= ONE_MILLION_CONTEXT_WINDOW;
        int modelLimit = resolveModelLimit(provider, selectedModel, resolvedActualModel, registrySelection);
        if (supportsLongContext && requestsOneMillion) {
            modelLimit = Math.max(modelLimit, ONE_MILLION_CONTEXT_WINDOW);
        }

        int effectiveContextWindow = request.requestedContextWindow() != null && request.requestedContextWindow() > 0
                ? request.requestedContextWindow()
                : modelLimit;
        int maxTokens = Math.min(effectiveContextWindow, modelLimit);
        String storedModel = resolveStoredModel(selectedModel, supportsLongContext, requestsOneMillion);

        return new ModelSelectionResult(
                provider,
                selectedModel,
                storedModel,
                resolvedActualModel,
                effectiveContextWindow,
                maxTokens,
                supportsLongContext && modelLimit >= ONE_MILLION_CONTEXT_WINDOW
        );
    }

    private String resolveActualModel(String selectedModel, ModelRegistryConfig.ResolvedModelSelection selection) {
        if (selection.actualModel() != null && !selection.actualModel().isBlank()) {
            return ModelRegistryConfig.stripCapacitySuffix(selection.actualModel());
        }
        return selectedModel;
    }

    private int resolveModelLimit(
            String provider,
            String selectedModel,
            String resolvedActualModel,
            ModelRegistryConfig.ResolvedModelSelection selection
    ) {
        int configuredLimit = selection.contextWindow() > 0 ? selection.contextWindow() : DEFAULT_CONTEXT_WINDOW;
        if (registry.find(provider, selectedModel).isPresent()) {
            return configuredLimit;
        }
        return contextLimit(resolvedActualModel);
    }

    private String resolveStoredModel(String selectedModel, boolean supportsLongContext, boolean requestsOneMillion) {
        if (supportsLongContext && requestsOneMillion && isClaudeFamily(selectedModel)) {
            return selectedModel + "[1m]";
        }
        return selectedModel;
    }

    private boolean supportsLongContext(
            String selectedModel,
            ModelRegistryConfig.ResolvedModelSelection registrySelection
    ) {
        if (registrySelection.supports1MContext()) {
            return true;
        }
        ClaudeRole role = ClaudeRole.fromModelId(selectedModel);
        return role != null && role.supports1MContext();
    }

    private int contextLimit(String model) {
        int suffixLimit = parseCapacitySuffix(model);
        if (suffixLimit > 0) {
            return suffixLimit;
        }

        ClaudeRole role = ClaudeRole.fromModelId(model);
        return role != null ? role.contextWindow() : DEFAULT_CONTEXT_WINDOW;
    }

    private int parseCapacitySuffix(String model) {
        if (model == null || model.isBlank()) {
            return 0;
        }
        Matcher matcher = CAPACITY_SUFFIX.matcher(model);
        if (!matcher.find()) {
            return 0;
        }
        double value = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2).toLowerCase();
        return "m".equals(unit) ? (int) (value * 1_000_000) : (int) (value * 1_000);
    }

    private boolean isClaudeFamily(String model) {
        if (model == null) {
            return false;
        }
        String lower = model.toLowerCase();
        return lower.startsWith("claude-") || lower.startsWith("claude_");
    }

    private String normalizeProvider(String provider) {
        return CommonConstants.PROVIDER_CODEX.equalsIgnoreCase(provider)
                ? CommonConstants.PROVIDER_CODEX
                : CommonConstants.PROVIDER_CLAUDE;
    }
}
