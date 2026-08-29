package com.github.claudecodegui.cli.omp;

import com.github.claudecodegui.bridge.NodeService;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionFactory;
import com.github.claudecodegui.cli.common.ChannelCliSession;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.session.runtime.ProviderType;

/**
 * OMP CLI 会话工厂(E1·开闭路由化)。
 * <p>
 * OMP 是 pi 的 fork,但输出为 NDJSON 而非 marker,故 Java 侧不直 spawn omp 二进制,
 * 而是经 {@link ChannelCliSession} spawn ai-bridge channel-manager.js omp send,
 * 由 ai-bridge {@code services/omp/message-service.js} 完成 NDJSON→marker 转换。
 * 声明 provider 路由键 {@link CommonConstants#PROVIDER_OMP},由 CliSessionManager 注册表查表调用。
 */
public class OmpCliSessionFactory implements CliSessionFactory {
    // ⚠️ 构造期不可解析 NodeService(内部 new EnvironmentConfigurator 触碰 IntelliJ 平台
    // Application 单例):CliSessionManager 装配发生在 ClaudeSession 构造链上,纯 JUnit
    // 环境(无 Application)会 NPE。惰性到 create() 首次调用。
    private volatile NodeService nodeService;

    public OmpCliSessionFactory() {
    }

    public OmpCliSessionFactory(NodeService nodeService) {
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
        return CommonConstants.PROVIDER_OMP;
    }

    @Override
    public CliSession create(String tabId) {
        return new ChannelCliSession(tabId, ProviderType.OMP, nodeService());
    }
}
