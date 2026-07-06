package com.github.claudecodegui.cli.common;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * GatewayDownMatcher 纯函数测试。
 * <p>验证 [melon-gateway-down] 标记匹配(覆盖 state file 不可读 / tools/list 降级两类 stderr),
 * 且不误伤 MCP 连接失败 / 普通错误(留给 McpErrorMatcher 或正常错误路径)。
 */
public class GatewayDownMatcherTest {

    @Test
    public void matchesStateFileUnreadableMarker() {
        assertTrue(GatewayDownMatcher.isGatewayDown(
                "[melon-gateway-down] state file unreadable (/path/state.json): ENOENT"));
    }

    @Test
    public void matchesToolsListDegradedMarker() {
        assertTrue(GatewayDownMatcher.isGatewayDown(
                "[melon-gateway-down] tools/list degraded to empty (gateway unreachable): connect ECONNREFUSED"));
    }

    @Test
    public void matchesMarkerEmbeddedInLongerLine() {
        // provider 可能包行加时间戳前缀,标记在行中间也须命中
        assertTrue(GatewayDownMatcher.isGatewayDown(
                "2026-07-06T12:00:00Z stderr: [melon-gateway-down] tools/list degraded to empty"));
    }

    @Test
    public void rejectsNullAndBlank() {
        assertFalse(GatewayDownMatcher.isGatewayDown(null));
        assertFalse(GatewayDownMatcher.isGatewayDown(""));
        assertFalse(GatewayDownMatcher.isGatewayDown("   "));
    }

    @Test
    public void rejectsUnrelatedText() {
        // 这些归 McpErrorMatcher 或正常错误路径,GatewayDownMatcher 不应误伤
        assertFalse(GatewayDownMatcher.isGatewayDown("mcp_servers_failed_to_connect"));
        assertFalse(GatewayDownMatcher.isGatewayDown("rmcp transport channel closed"));
        assertFalse(GatewayDownMatcher.isGatewayDown("command failed"));
        assertFalse(GatewayDownMatcher.isGatewayDown("Error: connection refused"));
    }

    @Test
    public void noticeConstantPresent() {
        assertNotNull(GatewayDownMatcher.GATEWAY_DOWN_NOTICE);
        assertFalse(GatewayDownMatcher.GATEWAY_DOWN_NOTICE.isBlank());
    }
}
