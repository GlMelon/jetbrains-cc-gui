package com.github.claudecodegui.protocol;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.mcp.McpCommandRiskEvaluator;
import com.github.claudecodegui.mcp.McpGatewayConstants;
import com.github.claudecodegui.service.MarketFetchException;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * MCP 协议词表对称性守门(P3 防漂移)。
 *
 * <p>{@code McpTransportType} / {@code McpServerStatus} / {@code McpGatewayState} /
 * {@code McpMarketErrorCode} 是各自词表的 SSOT(前端类型经 generate-protocol-types.mjs
 * 从枚举生成);{@code CommonConstants.MCP_*}、{@code McpGatewayConstants.TRANSPORT_/STATE_*}
 * 与 {@code MarketFetchException} 常量因 switch-case 需要编译期常量而保留字面量。
 * 本测试钉住「字面量别名 ↔ SSOT 枚举」逐值一致——任一侧增删改值不另一侧同步即红。
 */
public class McpProtocolEnumSymmetryTest {

    @Test
    public void transportTypeMatchesCommonConstantsAndGatewayConstants() {
        assertEquals(McpTransportType.STDIO.value(), CommonConstants.MCP_TRANSPORT_STDIO);
        assertEquals(McpTransportType.HTTP.value(), CommonConstants.MCP_TRANSPORT_HTTP);
        assertEquals(McpTransportType.SSE.value(), CommonConstants.MCP_TRANSPORT_SSE);
        assertEquals(McpTransportType.STDIO.value(), McpGatewayConstants.TRANSPORT_STDIO);
        assertEquals(McpTransportType.HTTP.value(), McpGatewayConstants.TRANSPORT_HTTP);
        assertEquals(McpTransportType.SSE.value(), McpGatewayConstants.TRANSPORT_SSE);
    }

    @Test
    public void serverStatusMatchesCommonConstants() {
        assertEquals(McpServerStatus.CONNECTED.value(), CommonConstants.MCP_STATUS_CONNECTED);
        assertEquals(McpServerStatus.FAILED.value(), CommonConstants.MCP_STATUS_FAILED);
        assertEquals(McpServerStatus.PENDING.value(), CommonConstants.MCP_STATUS_PENDING);
        assertEquals(McpServerStatus.DISABLED.value(), CommonConstants.MCP_STATUS_DISABLED);
    }

    @Test
    public void gatewayStateMatchesGatewayConstants() {
        assertEquals(McpGatewayState.READY.value(), McpGatewayConstants.STATE_READY);
        assertEquals(McpGatewayState.DEGRADED.value(), McpGatewayConstants.STATE_DEGRADED);
        assertEquals(McpGatewayState.STARTING.value(), McpGatewayConstants.STATE_STARTING);
        assertEquals(McpGatewayState.BACKOFF.value(), McpGatewayConstants.STATE_BACKOFF);
        assertEquals(McpGatewayState.STOPPED.value(), McpGatewayConstants.STATE_STOPPED);
    }

    @Test
    public void marketErrorCodeMatchesMarketFetchExceptionConstants() {
        assertEquals(McpMarketErrorCode.MISSING_API_KEY.value(), MarketFetchException.MISSING_API_KEY);
        assertEquals(McpMarketErrorCode.INVALID_API_KEY.value(), MarketFetchException.INVALID_API_KEY);
        assertEquals(McpMarketErrorCode.NETWORK_ERROR.value(), MarketFetchException.NETWORK_ERROR);
        assertEquals(McpMarketErrorCode.TIMEOUT.value(), MarketFetchException.TIMEOUT);
        assertEquals(McpMarketErrorCode.PARSE_ERROR.value(), MarketFetchException.PARSE_ERROR);
    }

    @Test
    public void knownRunnersCoverAllPackageAndContainerRunners() {
        Set<String> expected = new HashSet<>();
        for (McpPackageRunner runner : McpPackageRunner.values()) {
            expected.add(runner.value());
        }
        for (McpContainerRunner runner : McpContainerRunner.values()) {
            expected.add(runner.value());
        }
        assertTrue("KNOWN_RUNNERS must contain every package/container runner: missing "
                        + expected.stream().filter(r -> !McpCommandRiskEvaluator.KNOWN_RUNNERS.contains(r)).toList(),
                McpCommandRiskEvaluator.KNOWN_RUNNERS.containsAll(expected));
    }
}
