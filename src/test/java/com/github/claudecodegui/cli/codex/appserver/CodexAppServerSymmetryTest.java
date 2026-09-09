package com.github.claudecodegui.cli.codex.appserver;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * codex app-server 通道的总则六横切对称性源码检查(Platform 耦合、无法纯单测的部分兜底,
 * 对称 OpenCodeServeSymmetryTest / CliMcpGatewaySymmetryTest 范式)。
 */
public class CodexAppServerSymmetryTest {

    private static final String APP_SERVER_DIR =
            "src/main/java/com/github/claudecodegui/cli/codex/appserver/";

    private static String read(String file) throws Exception {
        return Files.readString(Path.of(file));
    }

    @Test
    public void managerImplementsPersistentCrossCuttingItems() throws Exception {
        String source = read(APP_SERVER_DIR + "CodexAppServerManager.java");
        // stderr 永久 drain(stdout 是协议通道由 client reader 消费;防管道满阻塞)
        assertTrue(source.contains("startStderrDrain"));
        // env:buildBaseEnvironment + readCodexCliEnvironment + applyExtraEnv + gateway 注入
        // (对称 one-shot CodexCliSession 的 env 组装)
        assertTrue(source.contains("CliEnvironmentBuilder.buildBaseEnvironment"));
        assertTrue(source.contains("CliSettings.readCodexCliEnvironment()"));
        assertTrue(source.contains("CliEnvironmentBuilder.applyExtraEnv"));
        assertTrue(source.contains("CodexCliCommandUtils.sanitizeEnv"));
        assertTrue(source.contains("buildCliConfig"));
        // cwd null → home 回退(spawn 侧)
        assertTrue(source.contains("resolveSpawnCwd"));
        // 指纹含 CLI 版本 + gateway endpoint + 注入 env 内容 + gateway -c 覆盖参数(漂移重建)
        assertTrue(source.contains("getCachedVersion"));
        assertTrue(source.contains("gatewayConfig.endpoint()"));
        assertTrue(source.contains("gatewayConfig.overrideArgs()"));
        // ProcessManager 注册 / 注销 + terminateProcess 兜底
        assertTrue(source.contains("registerAuxiliaryProcess"));
        assertTrue(source.contains("unregisterAuxiliaryProcess"));
        assertTrue(source.contains("PlatformUtils.terminateProcess"));
        // 连续失败熔断(防无限重启)
        assertTrue(source.contains("MAX_CONSECUTIVE_SPAWN_FAILURES"));
        // provider 引用走枚举 SSOT,禁 provider 字面量
        assertTrue(source.contains("ProviderType.CODEX"));
        assertFalse(source.contains("\"codex\""));
        // 子命令与超时走 CliConstants,禁硬编码字面量
        assertTrue(source.contains("CliConstants.CODEX_ARG_APP_SERVER"));
        assertTrue(source.contains("CliConstants.CODEX_APPSERVER_READY_TIMEOUT_MS"));
        assertFalse(source.contains("\"app-server\""));
        // 可执行文件解析复用 codex SSOT resolver(含原生 .exe 布局知识)
        assertTrue(source.contains("CodexCliResolver.findExecutable"));
        // 重建 / terminate 的旧句柄 teardown 异步化,dispose 保持同步
        assertTrue(source.contains("closeHandleAsync"));
        assertTrue(source.contains("AppExecutorUtil.getAppExecutorService()"));
    }

    @Test
    public void acquireSpawnsOutsideLockWithFutureDeduplication() throws Exception {
        String source = read(APP_SERVER_DIR + "CodexAppServerManager.java");
        // acquire 不整方法 synchronized,spawn + initialize 握手移出锁,
        // 并发 acquire 经 spawnFuture 去重共享同一 spawn(对称 OpenCodeServeManager §5.4)
        assertFalse(source.contains("public synchronized @Nullable CodexAppServerClient acquire"));
        assertTrue(source.contains("spawnFuture"));
        assertTrue(source.contains("CompletableFuture"));
        assertTrue(source.contains("future.join()"));
        // terminate 与 in-flight spawn 竞态:代际防护,终止期间的 spawn 结果不安装
        assertTrue(source.contains("teardownGeneration"));
    }

    @Test
    public void clientDrainsStdioAndAnswersServerRequests() throws Exception {
        String source = read(APP_SERVER_DIR + "CodexAppServerClient.java");
        // 专用 reader 守护线程消费 stdout(JSON-RPC 通道,防管道满阻塞)
        assertTrue(source.contains("AICG-Codex-AppServer-Reader"));
        // 无 handler 的 server 请求必须应答(-32601),否则 turn 因等待审批应答挂起
        assertTrue(source.contains("respondErrorToServer"));
        // 进程死亡:pending 全部异常完成 + 通知 handler + 触发 manager 摘句柄
        assertTrue(source.contains("onReaderEof"));
        assertTrue(source.contains("failPending"));
        // 写入串行化(多线程请求 / 审批应答共用 stdin)
        assertTrue(source.contains("synchronized void writeLine"));
    }

    @Test
    public void sessionImplementsInterruptAndDegradationSemantics() throws Exception {
        String source = read(APP_SERVER_DIR + "CodexAppServerSession.java");
        // interrupt = turn/interrupt 确定性取消 + 杀树兜底(turnId 双来源:响应 + turn/started 通知)
        assertTrue(source.contains("CODEX_APPSERVER_METHOD_TURN_INTERRUPT"));
        assertTrue(source.contains("CLI_INTERRUPT_FALLBACK_MS"));
        assertTrue(source.contains("terminateAppServer"));
        assertTrue(source.contains("captureTurnIdFromNotification"));
        // B13 等价:thread resume 失效 → 清 threadId 建新线程重试一次
        assertTrue(source.contains("CODEX_APPSERVER_METHOD_THREAD_RESUME"));
        assertTrue(source.contains("starting fresh thread"));
        // turn 递交语义:确定性错误(递交前)当轮降级 one-shot;超时(递交结果未知)不降级防双发
        assertTrue(source.contains("turnSubmitted"));
        assertTrue(source.contains("degrading to one-shot"));
        assertTrue(source.contains("turn/start"));
        assertTrue(source.contains("fallbackSession()"));
        // 权限:bypass→acceptForSession 直通;非 bypass 经 AppServerPermissionGate
        // 交互式授权(异常兜底 decline);非审批请求 -32601 应答
        assertTrue(source.contains("PERMISSION_MODE_BYPASS"));
        assertTrue(source.contains("AppServerPermissionGate"));
        assertTrue(source.contains("respondDecisionQuietly"));
        assertTrue(source.contains("respondErrorToServer"));
        // 逐轮覆盖复用 one-shot 的 SSOT:权限选择 / reasoning 钳制 / service tier 策略
        assertTrue(source.contains("CodexCliCommandUtils.selectPermission"));
        assertTrue(source.contains("ReasoningEffortResolver.clamp"));
        assertTrue(source.contains("CodexServiceTierPolicy"));
        // prompt 文本组合复用共享构建(SSOT,总则四)
        assertTrue(source.contains("AbstractRunOnceCliSession.buildPromptText"));
        // capabilities 对齐 one-shot
        assertTrue(source.contains("SessionNegotiatedCapabilities.cli(true, true, false)"));
    }

    @Test
    public void turnMapperStreamsDeltasImmediately() throws Exception {
        String source = read(APP_SERVER_DIR + "CodexAppServerTurn.java");
        // 核心价值:agent 正文与推理摘要 token 级增量,立即下发不缓冲到 completed
        assertTrue(source.contains("CODEX_APPSERVER_NOTIFY_AGENT_MESSAGE_DELTA"));
        assertTrue(source.contains("CODEX_APPSERVER_NOTIFY_REASONING_SUMMARY_DELTA"));
        assertTrue(source.contains("CODEX_APPSERVER_NOTIFY_REASONING_TEXT_DELTA"));
        // completed 全文对账只补差(delta 优先,防丢失截尾 / 防重复)
        assertTrue(source.contains("reconcileAgentMessage"));
        // 工具块 / usage 映射的 MSG_* 出口统一经 CliSectionEmitter
        assertTrue(source.contains("CliSectionEmitter"));
        // usage 映射到 Claude schema(对称 one-shot buildUsageResultMessage 字段名)
        assertTrue(source.contains("input_tokens"));
        assertTrue(source.contains("cache_read_input_tokens"));
        // 协议常量引用 CliConstants,禁字面量
        assertFalse(source.contains("\"item/agentMessage/delta\""));
        assertFalse(source.contains("\"turn/completed\""));
    }

    @Test
    public void factoryRoutesByFeatureFlagAndManagerAvailability() throws Exception {
        String source = read("src/main/java/com/github/claudecodegui/cli/codex/CodexCliSessionFactory.java");
        assertTrue(source.contains("CliPersistentFeatureFlags.isCodexAppServerEnabled()"));
        assertTrue(source.contains("appServerManager != null"));
        assertTrue(source.contains("new CodexAppServerSession("));
        assertTrue(source.contains("new CodexCliSession("));
        // app-server 交互式权限闸口接线:经 PermissionService 决策,保守 decline 兜底
        assertTrue(source.contains("buildAppServerPermissionGate"));
        assertTrue(source.contains("PermissionService.getInstance"));

        String manager = read("src/main/java/com/github/claudecodegui/cli/CliSessionManager.java");
        assertTrue(manager.contains("CodexAppServerManager.getInstance(project)"));
    }

    @Test
    public void featureFlagFollowsThreeLayerGate() throws Exception {
        String source = read("src/main/java/com/github/claudecodegui/cli/common/CliPersistentFeatureFlags.java");
        assertTrue(source.contains("FEATURE_CODEX_APPSERVER_ENABLED_KEY"));
        assertTrue(source.contains("isCodexAppServerEnabled"));
        // 第三层子开关与 opencode serve / claude 子开关同构(总开关 AND user 开关 AND 子开关)
        assertTrue(source.contains("isSystemEnabled()"));
        assertTrue(source.contains("isUserEnabled()"));
    }

    @Test
    public void protocolConstantsCentralizedInCliConstants() throws Exception {
        String source = read("src/main/java/com/github/claudecodegui/cli/common/CliConstants.java");
        // app-server 协议常量 SSOT(方法 / 通知 / 审批请求 / 决策 / item type / turn status)
        assertTrue(source.contains("CODEX_APPSERVER_METHOD_TURN_START"));
        assertTrue(source.contains("CODEX_APPSERVER_METHOD_TURN_INTERRUPT"));
        assertTrue(source.contains("CODEX_APPSERVER_NOTIFY_AGENT_MESSAGE_DELTA"));
        assertTrue(source.contains("CODEX_APPSERVER_REQUEST_COMMAND_APPROVAL"));
        assertTrue(source.contains("CODEX_APPSERVER_DECISION_ACCEPT_FOR_SESSION"));
        assertTrue(source.contains("CODEX_APPSERVER_ITEM_COMMAND_EXECUTION"));
        assertTrue(source.contains("CODEX_APPSERVER_TURN_STATUS_INTERRUPTED"));
        // app-server item type(camelCase)与 exec item type(snake_case)区分共存,不混用
        assertTrue(source.contains("CODEX_ITEM_COMMAND_EXECUTION = \"command_execution\""));
        assertTrue(source.contains("CODEX_APPSERVER_ITEM_COMMAND_EXECUTION = \"commandExecution\""));
    }
}
