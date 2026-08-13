package com.github.claudecodegui.config;

import com.github.claudecodegui.common.CommonConstants;

import java.util.Locale;

/** 后端权威的模型 UI 唯一标识生成器。identifier 仅用于定位，不得作为 CLI 模型参数。 */
public final class ModelIdentifier {
    private static final String SEPARATOR = "-";

    private ModelIdentifier() {
    }

    public static String create(String provider, String source, String modelId) {
        String normalizedProvider = normalizePart(provider);
        String normalizedModelId = normalizePart(ModelRegistryConfig.stripCapacitySuffix(modelId));
        if (CommonConstants.PROVIDER_OPENCODE.equals(normalizedProvider)) {
            String normalizedSource = normalizePart(source);
            if (!normalizedSource.isEmpty()) {
                return String.join(SEPARATOR, normalizedProvider, normalizedSource, normalizedModelId);
            }
        }
        return String.join(SEPARATOR, normalizedProvider, normalizedModelId);
    }

    public static String normalizeOrCreate(
            String identifier,
            String provider,
            String source,
            String modelId
    ) {
        return identifier == null || identifier.trim().isEmpty()
                ? create(provider, source, modelId)
                : identifier.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizePart(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
