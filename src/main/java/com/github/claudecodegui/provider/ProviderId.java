package com.github.claudecodegui.provider;

import com.github.claudecodegui.common.CommonConstants;

import java.util.Locale;

public record ProviderId(String value) {
    public static final ProviderId CLAUDE = new ProviderId(CommonConstants.PROVIDER_CLAUDE);
    public static final ProviderId CODEX = new ProviderId(CommonConstants.PROVIDER_CODEX);
    public static final ProviderId OPENCODE = new ProviderId(CommonConstants.PROVIDER_OPENCODE);

    public ProviderId {
        value = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public static ProviderId of(String value) {
        return new ProviderId(value);
    }
}
