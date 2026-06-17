package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.common.CommonConstants;

/**
 * Provider 维度枚举（路由键之二）。
 * 区分 Claude 与 Codex 两个 AI 提供者。
 */
public enum ProviderType {
    CLAUDE,
    CODEX;

    /**
     * 从 provider 字符串转换为 ProviderType。
     * 兼容现有字符串常量 CommonConstants.PROVIDER_CLAUDE / PROVIDER_CODEX。
     */
    public static ProviderType fromString(String provider) {
        if (provider == null) {
            return CLAUDE;
        }
        return switch (provider.trim().toLowerCase()) {
            case CommonConstants.PROVIDER_CODEX -> CODEX;
            default -> CLAUDE;
        };
    }

    /**
     * 转换为小写字符串，兼容现有常量。
     */
    public String toLowerCase() {
        return switch (this) {
            case CLAUDE -> CommonConstants.PROVIDER_CLAUDE;
            case CODEX -> CommonConstants.PROVIDER_CODEX;
        };
    }
}
