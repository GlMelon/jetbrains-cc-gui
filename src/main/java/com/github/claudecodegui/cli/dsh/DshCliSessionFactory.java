package com.github.claudecodegui.cli.dsh;

import com.github.claudecodegui.bridge.NodeService;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionFactory;
import com.github.claudecodegui.cli.common.ChannelCliSession;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.session.runtime.ProviderType;

/**
 * DSH(DeepSeek Harness) CLI 会话工厂(E1·开闭路由化)。
 * <p>
 * DSH 非简单 CLI marker provider:它对着一个持久本地 {@code dsh web} host 说 Host RPC/WebSocket。
 * 该 host 监护与事件流转换逻辑由 ai-bridge {@code services/dsh/*}(supervisor/message-service/events/ws-client)
 * 实现。Java 侧经 {@link ChannelCliSession} spawn ai-bridge channel-manager.js dsh send 触发该流程,
 * DSH host 生命周期(启停/状态)另由 typed DshHostHandler 管理(待批次 D 子项)。
 * <p>
 * 声明 provider 路由键 {@link CommonConstants#PROVIDER_DSH},由 CliSessionManager 注册表查表调用。
 */
public class DshCliSessionFactory implements CliSessionFactory {
    // ⚠️ 构造期不可解析 NodeService(内部 new EnvironmentConfigurator 触碰 IntelliJ 平台
    // Application 单例):CliSessionManager 装配发生在 ClaudeSession 构造链上,纯 JUnit
    // 环境(无 Application)会 NPE。惰性到 create() 首次调用(与 OmpCliSessionFactory 同款)。
    private volatile NodeService nodeService;

    public DshCliSessionFactory() {
    }

    public DshCliSessionFactory(NodeService nodeService) {
        this.nodeService = nodeService;
    }

    private NodeService nodeService() {
        if (nodeService == null) {
            nodeService = NodeService.getInstance();
        }
        return nodeService;
    }

    @Override
    public String provider() {
        return CommonConstants.PROVIDER_DSH;
    }

    @Override
    public CliSession create(String tabId) {
        return new ChannelCliSession(tabId, ProviderType.DSH, nodeService());
    }
}
