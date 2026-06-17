package com.github.claudecodegui.config;

/**
 * Single model entry in the configurable model registry.
 */
public record ModelConfig(
        String id,
        String provider,
        String label,
        String description,
        int contextWindow,
        boolean supports1MContext,
        boolean enabled
) {
    public ModelConfig normalized() {
        String normalizedId = id == null ? "" : id.trim();
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase();
        String normalizedLabel = label == null || label.trim().isEmpty() ? normalizedId : label.trim();
        String normalizedDescription = description == null || description.trim().isEmpty() ? "" : description.trim();
        return new ModelConfig(
                normalizedId,
                normalizedProvider,
                normalizedLabel,
                normalizedDescription,
                contextWindow,
                supports1MContext,
                enabled
        );
    }
}
