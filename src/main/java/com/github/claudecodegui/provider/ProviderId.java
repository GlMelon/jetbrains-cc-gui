package com.github.claudecodegui.provider;

import com.github.claudecodegui.common.CommonConstants;

import java.util.Locale;

public record ProviderId(String value) {
    public static final ProviderId CLAUDE = new ProviderId(CommonConstants.PROVIDER_CLAUDE);
    public static final ProviderId CODEX = new ProviderId(CommonConstants.PROVIDER_CODEX);
    public static final ProviderId OPENCODE = new ProviderId(CommonConstants.PROVIDER_OPENCODE);
    public static final ProviderId GROK = new ProviderId(CommonConstants.PROVIDER_GROK);
    public static final ProviderId KIMI = new ProviderId(CommonConstants.PROVIDER_KIMI);
    public static final ProviderId PI = new ProviderId(CommonConstants.PROVIDER_PI);
    public static final ProviderId OMP = new ProviderId(CommonConstants.PROVIDER_OMP);
    public static final ProviderId DSH = new ProviderId(CommonConstants.PROVIDER_DSH);

    public ProviderId {
        value = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public static ProviderId of(String value) {
        return new ProviderId(value);
    }
}
