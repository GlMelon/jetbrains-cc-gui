package com.github.claudecodegui.model.selection;

import com.github.claudecodegui.common.ClaudeRole;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.config.ModelRegistryConfig;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DefaultModelCapabilityResolver implements ModelCapabilityResolver {
    // 默认 / 1M 上下文窗口阈值的 SSOT 已上移至 CommonConstants（全局共享，消除散落硬编码）。
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
        // longContextEnabled 是前端意图的权威来源(新协议);requestedContextWindow>=1M 仅作
        // 向后兼容 —— 旧前端不发 longContextEnabled,仅发 contextWindow=1M。两者取并集,
        // 使「显式发 longContextEnabled」与「旧前端发 contextWindow=1M」都能触发 1M 窗口。
        boolean requestsOneMillion = request.longContextEnabled()
                || (request.requestedContextWindow() != null
                        && request.requestedContextWindow() >= CommonConstants.ONE_MILLION_CONTEXT_WINDOW);
        int modelLimit = resolveModelLimit(provider, selectedModel, resolvedActualModel, registrySelection);
        if (supportsLongContext && requestsOneMillion) {
            modelLimit = Math.max(modelLimit, CommonConstants.ONE_MILLION_CONTEXT_WINDOW);
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
                supportsLongContext && modelLimit >= CommonConstants.ONE_MILLION_CONTEXT_WINDOW
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
        int configuredLimit = selection.contextWindow() > 0 ? selection.contextWindow() : CommonConstants.DEFAULT_CONTEXT_WINDOW;
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
        return role != null ? role.contextWindow() : CommonConstants.DEFAULT_CONTEXT_WINDOW;
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
