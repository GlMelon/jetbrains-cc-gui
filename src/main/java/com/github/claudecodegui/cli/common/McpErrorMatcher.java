package com.github.claudecodegui.cli.common;

import java.util.Locale;

/**
 * MCP 连接失败识别器(三 provider 共享,纯函数)。
 * <p>
 * 当本地 MCP server 未启动 / 连接被拒 / 传输关闭时,各 provider 会输出不同签名的错误
 * (Codex 的 Rust rmcp 客户端、Claude 的 {@code mcp_servers_failed_to_connect}、OpenCode 的
 * Go mcp 错误)。这类错误<strong>不应</strong>让当前回合失败,而应降级为非阻塞 status 提示
 * (见 {@link #MCP_SKIPPED_NOTICE}),仅"跳过"该未启动的 MCP server。
 * <p>
 * 匹配保持保守:必须含明确的 MCP 失败信号,避免误伤普通 error/command failed。
 * <p>
 * 调用点:各 provider CLI 的诊断分支与 ERROR/TURN_FAILED/handleError 事件。
 */
public final class McpErrorMatcher {

    /** 降级提示文案(各 provider 共用,经 notifyStatusMessage→updateStatus 非阻塞 toast)。 */
    public static final String MCP_SKIPPED_NOTICE =
            "MCP 服务器未启动或连接失败,已跳过(不影响本次回答)";

    private McpErrorMatcher() {
    }

    /**
     * 判定文本是否为 MCP 连接/启动失败(应降级为非阻塞提示,而非回合失败)。
     *
     * @param text 原始错误/诊断文本(CLI 行或事件 message),可为 null
     * @return true 表示命中 MCP 连接失败签名
     */
    public static boolean isMcpConnectionFailure(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String l = text.toLowerCase(Locale.ROOT);
        // Codex Rust MCP 客户端(rmcp)及其签名
        if (l.contains("rmcp")) {
            return true;
        }
        if (l.contains("transport channel closed")) {
            return true;
        }
        // Claude
        if (l.contains("mcp_servers_failed")) {
            return true;
        }
        // 通用 MCP 启动/连接失败(需同时含 "mcp" 关键字,防误伤普通错误)
        if (l.contains("mcp")) {
            return l.contains("failed to connect")
                    || l.contains("failed to start")
                    || l.contains("failed to load")
                    || l.contains("connection refused")
                    || l.contains("could not connect")
                    || l.contains("unable to connect")
                    || l.contains("unable to start")
                    || l.contains("not running")
                    || l.contains("unreachable")
                    || l.contains("timed out")
                    || l.contains("timeout");
        }
        return false;
    }
}
