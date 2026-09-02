package com.github.claudecodegui.cli.kimi;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionFactory;
import com.github.claudecodegui.cli.common.ProviderCliResolver;
import com.github.claudecodegui.cli.kimi.acp.KimiAcpChannelGate;
import com.github.claudecodegui.cli.kimi.acp.KimiAcpCliSession;
import com.github.claudecodegui.cli.kimi.acp.KimiAcpWarmPool;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.service.lifecycle.LifecycleObservabilityService;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.intellij.openapi.diagnostic.Logger;

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

    private static final Logger LOG = Logger.getInstance(KimiCliSessionFactory.class);

    private final McpGatewayService gatewayService;
    private final LifecycleObservabilityService lifecycleService;
    private final KimiAcpWarmPool warmPool;

    public KimiCliSessionFactory() {
        this(null, null, null);
    }

    public KimiCliSessionFactory(McpGatewayService gatewayService) {
        this(gatewayService, null, null);
    }

    public KimiCliSessionFactory(McpGatewayService gatewayService,
                                 LifecycleObservabilityService lifecycleService) {
        this(gatewayService, lifecycleService, null);
    }

    public KimiCliSessionFactory(McpGatewayService gatewayService,
                                 LifecycleObservabilityService lifecycleService,
                                 KimiAcpWarmPool warmPool) {
        this.gatewayService = gatewayService;
        this.lifecycleService = lifecycleService;
        this.warmPool = warmPool;
    }

    @Override
    public String provider() {
        return CommonConstants.PROVIDER_KIMI;
    }

    @Override
    public CliSession create(String tabId) {
        // 版本未缓存时先同步触发一次检测(findExecutable 首次会 spawn '<kimi> --version'
        // 验证可执行性并填充 executable+version 双缓存)。gate 依赖版本缓存判定 ACP 资格,
        // 而本工厂只在 session 首次创建时被调用(CliSessionManager 按 (tabId, provider)
        // computeIfAbsent 缓存实例)——若此处不补检测,首条消息必然因「版本未缓存」回退
        // legacy,且该 tab 永久困在 legacy(无思考区/非流式),gate 侧「下一轮 send 即可
        // 命中」的假设不成立。检测成本与 legacy 路径首次 send 的 findExecutable 相同,只是提前。
        ensureVersionCached();
        if (KimiAcpChannelGate.isAcpEligible()) {
            return new KimiAcpCliSession(tabId, gatewayService, lifecycleService, warmPool);
        }
        return new KimiRunOnceCliSession(tabId, gatewayService,
                KimiAcpChannelGate.degradationReason(), lifecycleService);
    }

    /**
     * 确保 kimi 版本缓存已填充。-D 总开关关闭时跳过(gate 必为 false,无需检测成本)。
     * 检测失败(kimi 未安装/版本不被 accept)静默走 legacy,不影响 send 链。
     */
    private void ensureVersionCached() {
        if (!KimiAcpChannelGate.isSystemEnabled()) {
            return;
        }
        if (ProviderCliResolver.getCachedVersion(ProviderType.KIMI) != null) {
            return;
        }
        try {
            long startMs = System.currentTimeMillis();
            new ProviderCliResolver(ProviderType.KIMI, "kimi").findExecutable();
            LOG.info("[CliTurnPerf][KimiFactory] version pre-detect elapsedMs="
                    + (System.currentTimeMillis() - startMs) + " (prewarm cache miss)");
        } catch (Exception | LinkageError e) {
            // LinkageError(NoClassDefFoundError 等)同 gate 立场:降级 legacy,绝不抛穿 send 链
            LOG.warn("[KimiCliSessionFactory] version pre-detect failed; falling back to legacy: " + e.getMessage());
        }
    }
}
