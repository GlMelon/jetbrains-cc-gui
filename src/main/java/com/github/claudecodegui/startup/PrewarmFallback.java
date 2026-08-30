package com.github.claudecodegui.startup;

/** Startup fallback used when a provider prewarm probe is skipped or fails. */
public enum PrewarmFallback {
    RETRY_ON_FIRST_USE,
    LEGACY_CHANNEL,
    DIRECT_CHANNEL,
    HOST_CHANNEL
}
