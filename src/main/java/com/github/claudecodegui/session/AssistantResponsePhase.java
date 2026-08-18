package com.github.claudecodegui.session;

/**
 * Backend-owned response phase values for assistant turn status updates.
 */
public enum AssistantResponsePhase {
    QUEUED(
            "queued",
            "assistant.response.phase.queued.title",
            "assistant.response.phase.queued.description",
            true
    ),
    /**
     * MCP 网关连接与工具加载阶段:buildGatewayConfig 期间(ensureStarted + refreshConfig +
     * 各 MCP server initialize/listTools)。慢 server 时可达 15~30s,此前被笼统的
     * {@link #CONNECTING} 掩盖。从 send 路径真实边界触发,无 MCP 配置时不上报。
     */
    MCP_SYNCING(
            "mcp_syncing",
            "assistant.response.phase.mcpSyncing.title",
            "assistant.response.phase.mcpSyncing.description",
            true
    ),
    CONNECTING(
            "connecting",
            "assistant.response.phase.connecting.title",
            "assistant.response.phase.connecting.description",
            true
    ),
    UNDERSTANDING(
            "understanding",
            "assistant.response.phase.understanding.title",
            "assistant.response.phase.understanding.description",
            true
    ),
    THINKING(
            "thinking",
            "assistant.response.phase.thinking.title",
            "assistant.response.phase.thinking.description",
            true
    ),
    TOOLING(
            "tooling",
            "assistant.response.phase.tooling.title",
            "assistant.response.phase.tooling.description",
            true
    ),
    RESPONDING(
            "responding",
            "assistant.response.phase.responding.title",
            "assistant.response.phase.responding.description",
            true
    ),
    DONE(
            "done",
            "assistant.response.phase.done.title",
            "assistant.response.phase.done.description",
            false
    ),
    ERROR(
            "error",
            "assistant.response.phase.error.title",
            "assistant.response.phase.error.description",
            false
    );

    private final String value;
    private final String titleKey;
    private final String descriptionKey;
    private final boolean active;

    AssistantResponsePhase(String value, String titleKey, String descriptionKey, boolean active) {
        this.value = value;
        this.titleKey = titleKey;
        this.descriptionKey = descriptionKey;
        this.active = active;
    }

    public String value() {
        return value;
    }

    public String titleKey() {
        return titleKey;
    }

    public String descriptionKey() {
        return descriptionKey;
    }

    public boolean active() {
        return active;
    }

    /**
     * 按 wire value 反查枚举,用于从 CLI 回调通道({@link com.github.claudecodegui.cli.common.CliConstants#MSG_RESPONSE_PHASE})
     * 传来的字符串还原 phase。未匹配返回 {@code null}(调用方按需兜底到 {@link #CONNECTING})。
     */
    public static AssistantResponsePhase fromValue(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        for (AssistantResponsePhase phase : values()) {
            if (phase.value.equals(value)) {
                return phase;
            }
        }
        return null;
    }
}
