package com.github.claudecodegui.cli.common;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link McpErrorMatcher} 纯函数单测。
 * <p>
 * MCP 连接失败(本地 server 未启动 / 连接被拒 / 传输关闭等)应识别为非致命降级信号,
 * 降级为非阻塞 status 提示而非回合失败。这里覆盖三 provider 的真实错误签名。
 */
public class McpErrorMatcherTest {

    // ===== 正向:三 provider 的 MCP 连接失败签名 =====

    @Test
    public void matchesCodexRmcpTransportClosed() {
        // 用户截图原样(Codex Rust MCP 客户端 rmcp)
        String rmcp = "ERROR rmcp::transport::worker: worker quit with fatal: "
                + "Transport channel closed, when Client(HttpRequest(HttpRequest("
                + "\"http/request failed: error sending request for url "
                + "(http://127.0.0.1:64343/stream)\")))";
        assertTrue(McpErrorMatcher.isMcpConnectionFailure(rmcp));
    }

    @Test
    public void matchesBareRmcpKeyword() {
        assertTrue(McpErrorMatcher.isMcpConnectionFailure("rmcp: connection failed"));
    }

    @Test
    public void matchesClaudeMcpServersFailedToConnect() {
        assertTrue(McpErrorMatcher.isMcpConnectionFailure(
                "mcp_servers_failed_to_connect: filesystem"));
    }

    @Test
    public void matchesGenericMcpConnectFailure() {
        assertTrue(McpErrorMatcher.isMcpConnectionFailure(
                "mcp server 'weather' failed to connect"));
        assertTrue(McpErrorMatcher.isMcpConnectionFailure(
                "MCP failed to start: command not found"));
        assertTrue(McpErrorMatcher.isMcpConnectionFailure(
                "mcp: connection refused on port 8080"));
        assertTrue(McpErrorMatcher.isMcpConnectionFailure(
                "mcp tool server unreachable"));
        assertTrue(McpErrorMatcher.isMcpConnectionFailure(
                "mcp connect timed out"));
    }

    @Test
    public void matchesCaseInsensitive() {
        assertTrue(McpErrorMatcher.isMcpConnectionFailure("RMCP TRANSPORT CHANNEL CLOSED"));
    }

    // ===== 负向:非 MCP 错误不得误伤(避免把普通错误也降级)=====

    @Test
    public void doesNotMatchGenericError() {
        assertFalse(McpErrorMatcher.isMcpConnectionFailure("Error: command failed"));
        assertFalse(McpErrorMatcher.isMcpConnectionFailure("failed to fetch user data"));
        assertFalse(McpErrorMatcher.isMcpConnectionFailure("timeout: database query"));
    }

    @Test
    public void doesNotMatchNormalLogOrEmpty() {
        assertFalse(McpErrorMatcher.isMcpConnectionFailure(""));
        assertFalse(McpErrorMatcher.isMcpConnectionFailure(null));
        assertFalse(McpErrorMatcher.isMcpConnectionFailure("正在执行命令: git status"));
        assertFalse(McpErrorMatcher.isMcpConnectionFailure("Approval denied: abort requested"));
    }

    @Test
    public void noticeConstantPresent() {
        // 降级提示文案常量存在且非空(各 provider 共用)
        assertNotNull(McpErrorMatcher.MCP_SKIPPED_NOTICE);
        assertFalse(McpErrorMatcher.MCP_SKIPPED_NOTICE.isBlank());
    }
}
