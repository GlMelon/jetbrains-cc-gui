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
    /**
     * 请求已发给模型、等待模型首个输出的阶段:覆盖 codex exec 的长等待窗口
     * (turn.started 之后、item.completed(agent_message) 之前,实测 30-56s)与
     * opencode run 等一次性 CLI 的启动静默窗口(spawn 到首个协议事件约 3s)。
     * 由 CLI send 路径在真实边界上报;首个 thinking/content delta 仍由
     * {@link SessionCallbackAdapter} 自动推进到 THINKING/RESPONDING。
     */
    AWAITING_MODEL(
            "awaiting_model",
            "assistant.response.phase.awaitingModel.title",
            "assistant.response.phase.awaitingModel.description",
            true
    ),
    UNDERSTANDING(
            "understanding",
            "assistant.response.phase.understanding.title",
            "assistant.response.phase.understanding.description",
            true
    ),
    /**
     * API 端点 5xx/529 过载重试阶段:CLI 静默指数退避重试中(最多 N 次,总挂起可达数分钟)。
     * 视觉上仍属"等待模型响应"态(title 复用 understanding),但 description 由
     * {@link AssistantResponseStatusPayload#forApiRetry} 注入重试计数(attempt/max),
     * 前端据此 phase 值切琥珀警示色,使 init 后静默挂起可感知。见 CliConstants.PHASE_API_RETRY。
     */
    API_RETRY(
            "api_retry",
            "assistant.response.phase.understanding.title",
            "assistant.response.phase.apiRetry.description",
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
