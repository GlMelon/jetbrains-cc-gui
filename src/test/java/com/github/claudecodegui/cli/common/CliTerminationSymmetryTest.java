package com.github.claudecodegui.cli.common;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * 8 Provider CLI 终止路径的源码契约检查。
 *
 * <p>进程树中断和 stdout EOF 均依赖真实 OS/IntelliJ 进程设施，不适合在单元测试中
 * 启动真实 provider；按架构总则六使用源码字符串检查，防止新增/重构 provider 时遗漏
 * 确定性 interrupt、非零退出回调或持久连接的短时强杀兜底。</p>
 */
public class CliTerminationSymmetryTest {

    private static final Path CLI_ROOT = Path.of("src/main/java/com/github/claudecodegui/cli");

    private static final List<String> FACTORIES = List.of(
            "ClaudeCliSessionFactory", "CodexCliSessionFactory", "OpenCodeCliSessionFactory",
            "GrokCliSessionFactory", "KimiCliSessionFactory", "PiCliSessionFactory",
            "OmpCliSessionFactory", "DshCliSessionFactory"
    );

    private static String readCliSource(String relative) throws IOException {
        Path path = CLI_ROOT.resolve(relative);
        assertTrue("源文件必须存在: " + path.toAbsolutePath(), Files.isRegularFile(path));
        return Files.readString(path);
    }

    private static void assertContains(String label, String source, String... fragments) {
        for (String fragment : fragments) {
            assertTrue(label + " 必须包含终止契约: " + fragment, source.contains(fragment));
        }
    }

    @Test
    public void allProviderFactoriesRemainWiredToCoveredSessionFamilies() throws IOException {
        String manager = readCliSource("CliSessionManager.java");
        for (String factory : FACTORIES) {
            assertTrue("CliSessionManager 必须装配 " + factory, manager.contains(factory));
        }

        assertContains("Claude factory", readCliSource("claude/ClaudeCliSessionFactory.java"),
                "new ClaudeCliSession(");
        assertContains("Codex factory", readCliSource("codex/CodexCliSessionFactory.java"),
                "new CodexCliSession(");
        assertContains("OpenCode factory", readCliSource("opencode/OpenCodeCliSessionFactory.java"),
                "new OpenCodeCliSession(");
        assertContains("Grok factory", readCliSource("grok/GrokCliSessionFactory.java"),
                "new GrokRunOnceCliSession(");
        assertContains("Kimi factory", readCliSource("kimi/KimiCliSessionFactory.java"),
                "new KimiAcpCliSession(", "new KimiRunOnceCliSession(");
        assertContains("Pi factory", readCliSource("pi/PiCliSessionFactory.java"),
                "new PiRunOnceCliSession(");
        assertContains("OMP factory", readCliSource("omp/OmpCliSessionFactory.java"),
                "new ChannelCliSession(tabId, ProviderType.OMP");
        assertContains("DSH factory", readCliSource("dsh/DshCliSessionFactory.java"),
                "new ChannelCliSession(tabId, ProviderType.DSH");
    }

    @Test
    public void oneShotAndChannelInterruptsTerminateThroughSharedProcessHandle() throws IOException {
        assertContains("CliProcessHandle", readCliSource("common/CliProcessHandle.java"),
                "public void interrupt()", "PlatformUtils.terminateProcess(process)");

        assertContains("Claude one-shot", readCliSource("claude/ClaudeCliSession.java"),
                "public void interrupt()", "h.interrupt();");
        assertContains("Codex", readCliSource("codex/CodexCliSession.java"),
                "public void interrupt()", "h.interrupt();");
        assertContains("OpenCode/Grok/Kimi legacy/Pi run-once", readCliSource("common/AbstractRunOnceCliSession.java"),
                "public void interrupt()", "h.interrupt();");
        assertContains("OMP/DSH channel", readCliSource("common/ChannelCliSession.java"),
                "public void interrupt()", "h.interrupt();");
    }

    @Test
    public void persistentInterruptsHaveBoundedProcessTreeFallbacks() throws IOException {
        assertContains("Claude persistent", readCliSource("common/CliPersistentProcess.java"),
                "public void interruptTurn()", "CliConstants.CLI_INTERRUPT_FALLBACK_MS",
                "killForcibly(\"interrupt fallback timeout\")", "PlatformUtils.terminateProcess(target)");

        String kimi = readCliSource("kimi/acp/KimiAcpCliSession.java");
        assertContains("Kimi ACP", kimi,
                "conn.sendSessionCancel(sid)", "scheduleCancelFallback(activeTurnId, conn, activeHandle)",
                "CliConstants.CLI_INTERRUPT_FALLBACK_MS", "activeTurnId != turnId", "handle.interrupt();");
    }

    @Test
    public void eofAndNonZeroExitPathsAlwaysFinishCallbacks() throws IOException {
        for (String sourcePath : List.of(
                "claude/ClaudeCliSession.java",
                "codex/CodexCliSession.java",
                "common/AbstractRunOnceCliSession.java",
                "common/ChannelCliSession.java")) {
            String source = readCliSource(sourcePath);
            assertContains(sourcePath, source,
                    "CliProcessLifecycle.await(process, outputDrain)",
                    "callback.onError(err)",
                    "callback.onComplete(false");
        }

        assertContains("Kimi ACP stdout", readCliSource("kimi/acp/KimiAcpConnection.java"),
                "while ((line = reader.readLine()) != null)", "rejectAllPending(\"stdout closed\")");
        assertContains("Kimi ACP completion", readCliSource("kimi/acp/KimiAcpCliSession.java"),
                "callback.onError(err)", "callback.onComplete(false");
    }
}
