package com.github.claudecodegui.cli.common;

/**
 * MCP Gateway 不可用识别器(三 provider 共享,纯函数)。
 * <p>
 * gateway-stdio-client.js 在 gateway 不可达(state file 缺失 / HTTP 超时 / tools/list 降级返空工具)时,
 * 向 stderr 写 {@code [melon-gateway-down]} 标记。若 provider CLI 透传 MCP server stderr 到 Java 可读流,
 * 本识别器命中后降级为非阻塞 status 提示(见 {@link #GATEWAY_DOWN_NOTICE}),让用户感知
 * "MCP 工具本轮不可用"而非莫名等待。
 * <p>
 * <b>best-effort 通道:</b>provider 是否透传 MCP server stderr 取决于各 CLI 实现(Go/Rust/Node)。
 * 不透传时不 toast,但功能正确性不依赖 toast——stdio-client 侧 5s 超时 + 降级返空工具已保证对话继续
 * (不再挂 30s)。toast 是 UX 增强,gateway 崩溃场景另由 Java 侧 onExit 自愈兜底。
 * <p>
 * 与 {@link McpErrorMatcher} 区别:McpErrorMatcher 识别"单个本地 MCP server 未启动"(provider 自身
 * MCP client 报错,如 rmcp/mcp_servers_failed);GatewayDownMatcher 识别"全局 gateway 不可达"
 * (stdio-client 包装层降级标记)。前者跳过单个 server,后者整轮 gateway 工具降级。文案不同,
 * 接入时 GatewayDownMatcher 应<b>先判</b>(标记前缀更明确,不会被 McpErrorMatcher 的保守匹配误归类)。
 *
 * <p>调用点:三 provider CLI 的 MCP 错误降级分支,
 * 对称复用 McpErrorMatcher.MCP_SKIPPED_NOTICE 的降级语义。
 */
public final class GatewayDownMatcher {

    /** gateway 不可用降级提示文案(经 status 非阻塞 toast,各 provider 共用)。 */
    public static final String GATEWAY_DOWN_NOTICE =
            "MCP Gateway 暂不可用,本轮已降级为无工具直连(对话不受影响)";

    /** stdio-client 写入 stderr 的标记前缀(见 gateway-stdio-client.js / gateway-http-client.js)。 */
    private static final String MARKER = "[melon-gateway-down]";

    private GatewayDownMatcher() {
    }

    /**
     * 判定文本是否为 gateway 不可用降级标记。
     *
     * @param text 错误 / 诊断 / stderr 行,可为 null
     * @return true 表示命中 {@code [melon-gateway-down]} 标记
     */
    public static boolean isGatewayDown(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains(MARKER);
    }
}
