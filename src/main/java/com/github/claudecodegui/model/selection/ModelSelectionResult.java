package com.github.claudecodegui.model.selection;

public record ModelSelectionResult(
        String provider,
        String selectedModel,
        String identifier,
        String storedModel,
        String resolvedActualModel,
        int effectiveContextWindow,
        int maxTokens,
        boolean supportsLongContext
) {
    public ModelSelectionResult(String provider, String selectedModel, String storedModel,
                                String resolvedActualModel, int effectiveContextWindow,
                                int maxTokens, boolean supportsLongContext) {
        this(provider, selectedModel, null, storedModel, resolvedActualModel,
                effectiveContextWindow, maxTokens, supportsLongContext);
    }
}
