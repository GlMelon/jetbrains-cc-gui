package com.github.claudecodegui.cli.kimi;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionFactory;
import com.github.claudecodegui.cli.kimi.acp.KimiAcpChannelGate;
import com.github.claudecodegui.cli.kimi.acp.KimiAcpCliSession;
import com.github.claudecodegui.mcp.McpGatewayService;

/**
 * Kimi CLI 会话工厂(E1·开闭路由化)。
 * <p>
 * 声明 provider 路由键 {@link CommonConstants#PROVIDER_KIMI},
 * 由 {@link com.github.claudecodegui.cli.CliSessionManager} 注册表查表调用。
 * <p>
 * 双通道路由:启用条件满足时走 {@link KimiAcpCliSession}({@code kimi acp} 通道,
 * 思考区一等公民透出 agent_thought_chunk),否则回退 {@link KimiRunOnceCliSession}
 * (legacy stream-json 通道,无思考区)。门禁见 {@link KimiAcpChannelGate}。
 */
public class KimiCliSessionFactory implements CliSessionFactory {
    private final McpGatewayService gatewayService;

    public KimiCliSessionFactory() {
        this(null);
    }

    public KimiCliSessionFactory(McpGatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @Override
    public String provider() {
        return CommonConstants.PROVIDER_KIMI;
    }

    @Override
    public CliSession create(String tabId) {
        if (KimiAcpChannelGate.isAcpEligible()) {
            return new KimiAcpCliSession(tabId, gatewayService);
        }
        return new KimiRunOnceCliSession(tabId, gatewayService);
    }
}
