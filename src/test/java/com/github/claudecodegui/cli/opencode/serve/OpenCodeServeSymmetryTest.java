package com.github.claudecodegui.cli.opencode.serve;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * opencode serve 通道的总则六横切对称性源码检查(Platform 耦合、无法纯单测的部分兜底,
 * 对称 CliMcpGatewaySymmetryTest / CliTerminationSymmetryTest 范式)。
 */
public class OpenCodeServeSymmetryTest {

    private static final String SERVE_DIR = "src/main/java/com/github/claudecodegui/cli/opencode/serve/";

    private static String read(String file) throws Exception {
        return Files.readString(Path.of(file));
    }

    @Test
    public void managerImplementsPersistentCrossCuttingItems() throws Exception {
        String source = read(SERVE_DIR + "OpenCodeServeManager.java");
        // stdout drain(防管道满阻塞)
        assertTrue(source.contains("startStdoutDrain"));
        // env:buildBaseEnvironment + applyExtraEnv + gateway 注入(三 provider 对称)
        assertTrue(source.contains("CliEnvironmentBuilder.buildBaseEnvironment"));
        assertTrue(source.contains("CliEnvironmentBuilder.applyExtraEnv"));
        assertTrue(source.contains("buildCliConfig"));
        // 指纹含 CLI 版本 + gateway endpoint + 注入 env 内容(漂移重建)
        assertTrue(source.contains("getCachedVersion"));
        assertTrue(source.contains("gatewayConfig.endpoint()"));
        // ProcessManager 注册 / 注销 + terminateProcess 兜底
        assertTrue(source.contains("registerAuxiliaryProcess"));
        assertTrue(source.contains("unregisterAuxiliaryProcess"));
        assertTrue(source.contains("PlatformUtils.terminateProcess"));
        // 连续失败熔断(防无限重启)
        assertTrue(source.contains("MAX_CONSECUTIVE_SPAWN_FAILURES"));
        // provider 引用走枚举 SSOT,禁 provider 字面量 / 重复 npm 目录常量
        assertTrue(source.contains("ProviderType.OPENCODE"));
        assertTrue(source.contains("CliConstants.OPENCODE_NPM_DIR"));
        assertFalse(source.contains("\"opencode-ai\""));
        // serve 命令行参数走 CliConstants,禁硬编码 flag 字面量
        assertTrue(source.contains("CliConstants.OPENCODE_ARG_SERVE"));
        assertTrue(source.contains("CliConstants.OPENCODE_SERVE_HOSTNAME"));
        assertFalse(source.contains("\"serve\""));
        assertFalse(source.contains("\"127.0.0.1\""));
        // 重建 / terminateServe 的旧句柄 teardown 异步化(调用线程不阻塞 waitFor),
        // dispose 保持同步(平台共享执行器,对齐同仓 AppExecutorUtil 异步清理惯例)
        assertTrue(source.contains("closeHandleAsync"));
        assertTrue(source.contains("AppExecutorUtil.getAppExecutorService()"));
    }

    @Test
    public void acquireSpawnsOutsideLockWithFutureDeduplication() throws Exception {
        String source = read(SERVE_DIR + "OpenCodeServeManager.java");
        // §5.4:acquire 不再整方法 synchronized,spawn + TCP 轮询移出锁(共享执行器),
        // 并发 acquire 经 spawnFuture 去重共享同一 spawn,多 tab 首轮不互相阻塞
        assertFalse(source.contains("public synchronized @Nullable OpenCodeServeClient acquire"));
        assertTrue(source.contains("spawnFuture"));
        assertTrue(source.contains("CompletableFuture"));
        assertTrue(source.contains("future.join()"));
        // terminate 与 in-flight spawn 竞态:代际防护,终止期间的 spawn 结果不安装
        assertTrue(source.contains("teardownGeneration"));
    }

    @Test
    public void clientSharesStaticHttpClient() throws Exception {
        String source = read(SERVE_DIR + "OpenCodeServeClient.java");
        // HttpClient 静态共享:Java 17 无 close(),每实例自带 SelectorManager 守护线程,
        // 反复重建场景线程短暂堆积;baseUrl 按请求拼接,builder 配置无 per-实例状态
        assertTrue(source.contains("static final HttpClient HTTP_CLIENT"));
        assertFalse(source.contains("this.httpClient ="));
    }

    @Test
    public void clientDetectsHalfOpenStreamViaHeartbeatWatchdog() throws Exception {
        String source = read(SERVE_DIR + "OpenCodeServeClient.java");
        // SSE 半开活性探测:server.heartbeat(~30s 一帧)超时看门狗关流,
        // 走既有 notifyStreamClosed → 摘句柄 → 下次 acquire 重建路径
        assertTrue(source.contains("lastActivityAt"));
        assertTrue(source.contains("OPENCODE_SERVE_SSE_STALE_TIMEOUT_MS"));
        assertTrue(source.contains("OPENCODE_SERVE_SSE_WATCHDOG_INTERVAL_MS"));
    }

    @Test
    public void sessionImplementsInterruptAndDegradationSemantics() throws Exception {
        String source = read(SERVE_DIR + "OpenCodeServeSession.java");
        // interrupt = abort API 确定性取消 + 杀树兜底
        assertTrue(source.contains(".abort("));
        assertTrue(source.contains("CLI_INTERRUPT_FALLBACK_MS"));
        assertTrue(source.contains("terminateServe"));
        // B13 等价:续接 4xx → 清 sessionId 建新会话重试一次
        assertTrue(source.contains("isClientError()"));
        assertTrue(source.contains("sessionId = null"));
        // prompt 递交前失败当轮降级 one-shot(按「是否递交」分派,不按异常类型)
        assertTrue(source.contains("promptSubmitted"));
        assertTrue(source.contains("fallbackSession()"));
        // 权限:bypass→always 直通;非 bypass 经 ServePermissionGate 交互式授权(异常兜底 reject)
        assertTrue(source.contains("PERMISSION_MODE_BYPASS"));
        assertTrue(source.contains("ServePermissionGate"));
        assertTrue(source.contains("respondPermissionQuietly"));
        // reasoningEffort→variant 复用 one-shot 映射(总则四,不双写)
        assertTrue(source.contains("AbstractRunOnceCliSession.mapReasoningVariant"));
        // model 无 '/' 拆分失败时显式告警(不再静默丢弃用户模型选择)
        assertTrue(source.contains("splitModelRef"));
        assertTrue(source.contains("model selection dropped"));
        // capabilities 对齐 one-shot
        assertTrue(source.contains("SessionNegotiatedCapabilities.cli(true, true, false)"));
    }

    @Test
    public void turnMapperReusesOneShotPureFunctions() throws Exception {
        String source = read(SERVE_DIR + "OpenCodeServeTurn.java");
        // 纯函数映射复用 OpenCodeEventMapper(总则四,禁复制粘贴两套)
        assertTrue(source.contains("OpenCodeEventMapper"));
        assertTrue(source.contains("deltaOf"));
        assertTrue(source.contains("buildUsage"));
        assertTrue(source.contains("buildToolUseBlock"));
        assertTrue(source.contains("buildToolResultBlock"));
    }

    @Test
    public void clientPinsHttp11ToAvoidH2cUpgradeHang() throws Exception {
        String source = read(SERVE_DIR + "OpenCodeServeClient.java");
        // 防回归:默认 HTTP_2 偏好对明文 http 走 h2c upgrade,bun serve 对带 body 的
        // upgrade POST 不应答 → 挂死超时(已实测),必须固定 HTTP/1.1
        assertTrue(source.contains("HttpClient.Version.HTTP_1_1"));
    }

    @Test
    public void factoryRoutesByFeatureFlagAndManagerAvailability() throws Exception {
        String source = read("src/main/java/com/github/claudecodegui/cli/opencode/OpenCodeCliSessionFactory.java");
        assertTrue(source.contains("CliPersistentFeatureFlags.isOpenCodeServeEnabled()"));
        assertTrue(source.contains("serveManager != null"));
        assertTrue(source.contains("new OpenCodeServeSession("));
        assertTrue(source.contains("new OpenCodeCliSession("));
        // serve 交互式权限闸口接线:经 PermissionService 决策,保守 reject 兜底
        assertTrue(source.contains("buildServePermissionGate"));
        assertTrue(source.contains("PermissionService.getInstance"));

        String manager = read("src/main/java/com/github/claudecodegui/cli/CliSessionManager.java");
        assertTrue(manager.contains("OpenCodeServeManager.getInstance(project)"));
    }

    @Test
    public void featureFlagFollowsThreeLayerGate() throws Exception {
        String source = read("src/main/java/com/github/claudecodegui/cli/common/CliPersistentFeatureFlags.java");
        assertTrue(source.contains("FEATURE_OPENCODE_SERVE_ENABLED_KEY"));
        assertTrue(source.contains("isOpenCodeServeEnabled"));
        // 第三层子开关与 claude 子开关同构(总开关 AND user 开关 AND 子开关)
        assertTrue(source.contains("isSystemEnabled()"));
        assertTrue(source.contains("isUserEnabled()"));
    }

    @Test
    public void oneShotParserDelegatesToSharedMapper() throws Exception {
        String source = read("src/main/java/com/github/claudecodegui/cli/opencode/OpenCodeCliStreamParser.java");
        // parser 不再自带映射实现,统一委托 OpenCodeEventMapper
        assertTrue(source.contains("OpenCodeEventMapper"));
        assertFalse(source.contains("private static String deltaOf"));
        assertFalse(source.contains("private JsonObject buildUsage"));
        assertFalse(source.contains("private static boolean isErrorState"));
        assertFalse(source.contains("private static String getString"));
    }
}
