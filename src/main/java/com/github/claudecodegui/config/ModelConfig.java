package com.github.claudecodegui.config;

/**
 * Single model entry in the configurable model registry.
 */
public record ModelConfig(
        String id,
        String provider,
        String role,
        String label,
        String actualModel,
        String description,
        int contextWindow,
        boolean supports1MContext,
        boolean enabled
) {
    public ModelConfig normalized() {
        String normalizedId = id == null ? "" : id.trim();
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase();
        String normalizedRole = role == null ? "" : role.trim().toLowerCase();
        String normalizedLabel = label == null || label.trim().isEmpty() ? normalizedId : label.trim();
        String normalizedActualModel = actualModel == null || actualModel.trim().isEmpty()
                ? ""
                : actualModel.trim();
        String normalizedDescription = description == null || description.trim().isEmpty() ? "" : description.trim();
        return new ModelConfig(
                normalizedId,
                normalizedProvider,
                normalizedRole,
                normalizedLabel,
                normalizedActualModel,
                normalizedDescription,
                contextWindow,
                supports1MContext,
                enabled
        );
    }
}
