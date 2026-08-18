package com.github.claudecodegui.cli.claude;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionFactory;
import com.github.claudecodegui.cli.common.CliPersistentProcessRegistry;
import com.github.claudecodegui.handler.history.ClaudeSessionEntrypointRewriter;
import com.github.claudecodegui.mcp.McpGatewayService;

/**
 * Claude CLI 会话工厂(E1·开闭路由化)。
 * <p>
 * 声明 provider 路由键 {@link CommonConstants#PROVIDER_CLAUDE},
 * 由 {@link com.github.claudecodegui.cli.CliSessionManager} 注册表查表调用。
 */
public class ClaudeCliSessionFactory implements CliSessionFactory {
    private final McpGatewayService gatewayService;
    private final CliPersistentProcessRegistry persistentRegistry;

    public ClaudeCliSessionFactory() {
        this(null, null);
    }

    public ClaudeCliSessionFactory(McpGatewayService gatewayService) {
        this(gatewayService, null);
    }

    /**
     * Project-aware 构造:注入长驻进程注册表(设计文档 §4.4)。
     * registry 为 null(测试/无 Project 路径)时 ClaudeCliSession 永远走 one-shot,自然降级。
     */
    public ClaudeCliSessionFactory(McpGatewayService gatewayService, CliPersistentProcessRegistry persistentRegistry) {
        this.gatewayService = gatewayService;
        this.persistentRegistry = persistentRegistry;
    }

    @Override
    public String provider() {
        return CommonConstants.PROVIDER_CLAUDE;
    }

    @Override
    public CliSession create(String tabId) {
        return new ClaudeCliSession(tabId, gatewayService, new ClaudeSessionEntrypointRewriter(), persistentRegistry);
    }
}
