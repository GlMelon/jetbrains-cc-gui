package com.github.claudecodegui.cli.common;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * one-shot CLI 等待路径的源码契约检查(总则六:平台耦合、无法纯单测的处理用源码检查兜底)。
 *
 * <p>空闲感知超时(awaitIdleAware)替代绝对总时长上限(对齐上游 32a2ad50:仅连续无输出
 * 才判卡死,长重构/完整测试套件不再被中途腰斩)。三条 one-shot 等待路径——共享
 * {@code AbstractRunOnceCliSession}、自持读循环的 {@code ClaudeCliSession} 与
 * {@code CodexCliSession}——都必须:① drain 循环刷新活动时间戳;② 使用
 * {@code awaitIdleAware} 而非绝对超时的 {@code await}。</p>
 */
public class CliIdleTimeoutSymmetryTest {

    private static final Path CLI_ROOT = Path.of("src/main/java/com/github/claudecodegui/cli");

    private static final List<String> ONE_SHOT_SESSION_SOURCES = List.of(
            "common/AbstractRunOnceCliSession.java",
            "claude/ClaudeCliSession.java",
            "codex/CodexCliSession.java"
    );

    private static String readCliSource(String relative) throws IOException {
        Path path = CLI_ROOT.resolve(relative);
        assertTrue("源文件必须存在: " + path.toAbsolutePath(), Files.isRegularFile(path));
        return Files.readString(path);
    }

    @Test
    public void allOneShotWaitPathsUseIdleAwareAwaitWithActivityFeed() throws IOException {
        for (String relative : ONE_SHOT_SESSION_SOURCES) {
            String source = readCliSource(relative);
            assertTrue(relative + " 必须刷新空闲时钟(lastOutputNanos.set)",
                    source.contains("lastOutputNanos.set(System.nanoTime())"));
            assertTrue(relative + " 必须使用 awaitIdleAware 等待",
                    source.contains("awaitIdleAware("));
            assertTrue(relative + " 必须以 CLI_IDLE_TIMEOUT_MS 作为空闲阈值",
                    source.contains("CliConstants.CLI_IDLE_TIMEOUT_MS"));
        }
    }

    @Test
    public void idleAwareAwaitKeepsDeterministicTerminationOnHang() throws IOException {
        String lifecycle = readCliSource("common/CliProcessLifecycle.java");
        assertTrue("空闲判定后必须确定性终止进程树(PlatformUtils.terminateProcess)",
                lifecycle.contains("terminate(process);"));
        assertTrue("终止后必须强杀兜底(destroyForcibly)",
                lifecycle.contains("destroyForcibly()"));
        assertTrue("终止后必须等待 drain 收尾(awaitDrainBestEffort)",
                lifecycle.contains("awaitDrainBestEffort(drainFuture);"));
        assertTrue("卡死判定必须置 timedOut=true(new Outcome(exitCode, true))",
                lifecycle.contains("new Outcome(exitCode, true);"));
    }
}
