package com.github.claudecodegui.config;

import java.util.Locale;

/**
 * Single model entry in the configurable model registry.
 *
 * <p>{@code readOnly=true} 表示该项来自 CLI 配置文件(settings.json / config.toml),
 * 由后端运行时计算,不可被用户层编辑/删除/停用,也不进持久化。
 */
public record ModelConfig(
        String id,
        String identifier,
        String provider,
        String role,
        String label,
        String actualModel,
        String description,
        int contextWindow,
        boolean supports1MContext,
        boolean enabled,
        boolean readOnly
) {
    public ModelConfig {
        identifier = identifier == null || identifier.trim().isEmpty()
                ? ModelIdentifier.create(provider, role, id)
                : identifier.trim().toLowerCase(Locale.ROOT);
    }

    /** 10 参兼容构造器:identifier 由后端统一派生。 */
    public ModelConfig(String id, String provider, String role, String label, String actualModel,
                       String description, int contextWindow, boolean supports1MContext,
                       boolean enabled, boolean readOnly) {
        this(id, null, provider, role, label, actualModel, description,
                contextWindow, supports1MContext, enabled, readOnly);
    }

    /** 9 参便利构造器:委托规范构造器,readOnly 默认 false(后端权威:解析/持久化路径用此)。 */
    public ModelConfig(String id, String provider, String role, String label, String actualModel,
                       String description, int contextWindow, boolean supports1MContext, boolean enabled) {
        this(id, null, provider, role, label, actualModel, description,
                contextWindow, supports1MContext, enabled, false);
    }

    /** 10 参便利构造器:显式 identifier,readOnly 默认 false。 */
    public ModelConfig(String id, String identifier, String provider, String role, String label,
                       String actualModel, String description, int contextWindow,
                       boolean supports1MContext, boolean enabled) {
        this(id, identifier, provider, role, label, actualModel, description,
                contextWindow, supports1MContext, enabled, false);
    }

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
                ModelIdentifier.normalizeOrCreate(identifier, normalizedProvider, normalizedRole, normalizedId),
                normalizedProvider,
                normalizedRole,
                normalizedLabel,
                normalizedActualModel,
                normalizedDescription,
                contextWindow,
                supports1MContext,
                enabled,
                readOnly
        );
    }
}
