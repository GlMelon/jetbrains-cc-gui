package com.github.claudecodegui.cli.common;

import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.session.runtime.ProviderType;

/**
 * marker 协议一次性 CLI 会话(grok/kimi/pi 共用)。
 * <p>
 * 合并自原 GrokCliSession/KimiCliSession/PiCliSession 三胞胎(归一化后仅 ProviderType
 * 一行之差)。全部行为逻辑在 {@link AbstractRunOnceCliSession},本类只绑定
 * {@link MarkerCliStreamParser}(marker 协议,协议定义见 ai-bridge/utils/marker-protocol.js)。
 */
public class MarkerRunOnceCliSession extends AbstractRunOnceCliSession {

    public MarkerRunOnceCliSession(ProviderType providerType, String tabId) {
        this(providerType, tabId, null);
    }

    public MarkerRunOnceCliSession(ProviderType providerType, String tabId, McpGatewayService gatewayService) {
        super(providerType, tabId, gatewayService);
    }

    @Override
    protected CliStreamParser createParser(CliSessionCallback callback) {
        return new MarkerCliStreamParser(callback);
    }
}
