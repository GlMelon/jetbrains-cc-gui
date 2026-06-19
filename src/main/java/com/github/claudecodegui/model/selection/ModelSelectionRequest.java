package com.github.claudecodegui.model.selection;

public record ModelSelectionRequest(
        String provider,
        String selectedModel,
        Integer requestedContextWindow,
        boolean longContextEnabled
) {
}
