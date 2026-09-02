package com.github.claudecodegui.cli.kimi.acp;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.startup.ProviderPrewarmRegistry;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Kimi ACP 暖连接池接线的源码守卫(Platform 耦合部分无法纯单测,按
 * CliMcpGatewaySymmetryTest 范式用源码字符串检查兜底)+ 预热策略窗口断言。
 */
public class KimiAcpWarmPoolTest {

    @Test
    public void kimiPrewarmStrategyWarmsAcpConnectionAfterResolverProbe() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/github/claudecodegui/startup/ProviderPrewarmRegistry.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("kimiAcpWarm()"));
        assertTrue(source.contains("KimiAcpWarmPool.getInstance(project).warm(cancelled)"));
        // 暖连接依赖版本缓存(门禁判定),必须先 resolver 探测后 warm
        int probe = source.indexOf("new ProviderCliResolver(ProviderType.KIMI, ProviderType.KIMI.cliCommand()).findExecutable()");
        int warm = source.indexOf("KimiAcpWarmPool.getInstance(project).warm(cancelled)");
        assertTrue(probe >= 0 && warm > probe);
        // 门禁不过(版本不支持 ACP)时不暖
        assertTrue(source.contains("KimiAcpChannelGate.isAcpEligible()"));
    }

    @Test
    public void kimiPrewarmPolicyGetsExtendedWindowForWarmUp() {
        ProviderPrewarmRegistry registry = ProviderPrewarmRegistry.defaultRegistry();

        assertEquals(Duration.ofSeconds(20),
                registry.strategy(ProviderType.KIMI).policy().timeout());
    }

    @Test
    public void prewarmWindowIsPerStrategyAnchoredAtSubmission() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/github/claudecodegui/startup/BridgePreloader.java"), StandardCharsets.UTF_8);

        // 每策略独立窗口:从提交时刻起算,而非全部策略共享一个 deadline
        assertTrue(source.contains("startNanos + strategy.policy().timeout().toNanos()"));
        assertFalse(source.contains("maxTimeoutNanos"));
    }

    @Test
    public void sessionAdoptsWarmConnectionAndRebindsTurnScopedHandlers() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/github/claudecodegui/cli/kimi/acp/KimiAcpCliSession.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("warmPool.take()"));
        assertTrue(source.contains("conn.rebindLineSink(parser::parseLine)"));
        assertTrue(source.contains("conn.rebindResponder(this::handleServerRequest)"));
        // 暖连接跳过 initialize(预热已完成握手)
        assertTrue(source.contains("if (warm == null)"));
        // 进程环境构建与暖池共用同一入口(总则六对称)
        assertTrue(source.contains("buildAcpProcessBuilder(executable, gatewayConfig,"));
    }

    @Test
    public void connectionSupportsResponderRebindForWarmAdoption() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/github/claudecodegui/cli/kimi/acp/KimiAcpConnection.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("void rebindResponder(ServerRequestResponder newResponder)"));
        assertTrue(source.contains("private volatile ServerRequestResponder responder;"));
    }

    @Test
    public void warmPoolGuardsAgainstUncontrolledRebuild() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/github/claudecodegui/cli/kimi/acp/KimiAcpWarmPool.java"),
                StandardCharsets.UTF_8);

        // 总则六健壮性:进程注册 ProcessManager、空闲自动关闭、dispose 关闭
        assertTrue(source.contains("registerAuxiliaryProcess(process)"));
        assertTrue(source.contains("unregisterAuxiliaryProcess"));
        assertTrue(source.contains("IDLE_CLOSE_MS"));
        assertTrue(source.contains("implements Disposable"));
    }
}
