package com.github.claudecodegui.model.selection;

public record ModelSelectionResult(
        String provider,
        String selectedModel,
        String storedModel,
        String resolvedActualModel,
        int effectiveContextWindow,
        int maxTokens,
        boolean supportsLongContext
) {
}
