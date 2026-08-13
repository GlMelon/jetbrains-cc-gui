package com.github.claudecodegui.model.selection;

public record ModelSelectionRequest(
        String provider,
        String selectedModel,
        String identifier,
        Integer requestedContextWindow,
        boolean longContextEnabled
) {
    public ModelSelectionRequest(String provider, String selectedModel, Integer requestedContextWindow,
                                 boolean longContextEnabled) {
        this(provider, selectedModel, null, requestedContextWindow, longContextEnabled);
    }
}
