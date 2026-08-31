package com.github.claudecodegui.service.lifecycle;

/** Physical process domains used by lifecycle observability. */
public enum LifecycleProcessKind {
    CLI_ONE_SHOT,
    CLI_PERSISTENT,
    CHANNEL,
    MCP_GATEWAY,
    AUXILIARY
}
