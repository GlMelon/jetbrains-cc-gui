package com.github.claudecodegui.mcp;

/**
 * MCP server 安装/更新被后端安全闸门拒绝时抛出。
 *
 * <p>message 为可读原因（来自 {@link McpCommandRiskEvaluator#explainRisk}），经 handler 的
 * i18n 模板（如 {@code mcp.addServerFailedWithReason} / {@code mcp.updateServerFailedWithReason}）
 * 作为 reason 参数显示给用户。
 *
 * <p>继承 {@link RuntimeException}：使两个 {@code upsertMcpServer}（签名仅 {@code throws IOException}）
 * 无需修改方法签名即可抛出，且不会被 {@code McpServerManager} 内部 {@code catch (Exception)}
 * 之后的 fallback 写盘路径吞掉（闸门在其 try 之外触发）。
 */
public class McpInstallRejectedException extends RuntimeException {

    public McpInstallRejectedException(String message) {
        super(message);
    }
}
