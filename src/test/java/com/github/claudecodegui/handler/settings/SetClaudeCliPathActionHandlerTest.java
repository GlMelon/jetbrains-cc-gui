package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 契约单测 + 纯路径校验分支单测。
 *
 * <p>契约部分断言 typed handler 绑定 SET_CLAUDE_CLI_PATH + String payload。
 *
 * <p>校验部分覆盖 {@link SetClaudeCliPathActionHandler#validateCliPath(File, String)} 的四条
 * 分支(不存在 / 目录 / 非可执行 / 合法可执行),从旧 ClaudeCliPathHandlerTest 平移而来。
 * 这些用例守护"非法路径在持久化与重启 daemon 之前被拒绝"的安全/UX 不变量。
 * handle() 的异步持久化 + daemon 重启行为靠源码对照 + wiring 守门保证,不在此单测内。
 *
 * <p>本项目 testImplementation 仅声明 JUnit 4(build.gradle),沿用同目录既有 JUnit 4 风格。
 */
public class SetClaudeCliPathActionHandlerTest {

    @Test
    public void bindsSetClaudeCliPathUpstreamActionWithRawStringPayload() {
        SetClaudeCliPathActionHandler handler = new SetClaudeCliPathActionHandler();

        assertEquals(UpstreamAction.SET_CLAUDE_CLI_PATH, handler.action());
        assertEquals(String.class, handler.payloadType());
    }

    @Test
    public void validateRejectsNonExistentFile() {
        File missing = new File(System.getProperty("java.io.tmpdir"), "cc-gui-claude-cli-missing-zzz");
        String reason = SetClaudeCliPathActionHandler.validateCliPath(missing, missing.getPath());
        assertNotNull("A non-existent path must be rejected", reason);
        assertTrue("Reason should explain the file is missing: " + reason,
                reason.startsWith("File does not exist"));
    }

    @Test
    public void validateRejectsDirectory() throws IOException {
        File dir = Files.createTempDirectory("cc-gui-claude-cli-dir").toFile();
        dir.deleteOnExit();
        String reason = SetClaudeCliPathActionHandler.validateCliPath(dir, dir.getPath());
        assertNotNull("A directory must be rejected", reason);
        assertTrue("Reason should explain the path is a directory: " + reason,
                reason.startsWith("Path is a directory"));
    }

    @Test
    public void validateRejectsNonExecutableFile() throws IOException {
        File file = Files.createTempFile("cc-gui-claude-cli-noexec", ".bin").toFile();
        file.deleteOnExit();
        file.setExecutable(false, false);
        // Some filesystems / privileged users cannot represent a non-executable regular
        // file (canExecute stays true); skip rather than fail spuriously in that case.
        Assume.assumeFalse("Filesystem cannot strip the execute bit", file.canExecute());

        String reason = SetClaudeCliPathActionHandler.validateCliPath(file, file.getPath());
        assertNotNull("A non-executable file must be rejected", reason);
        assertTrue("Reason should explain the file is not executable: " + reason,
                reason.startsWith("File is not executable"));
    }

    @Test
    public void validateAcceptsExecutableFile() throws IOException {
        File file = Files.createTempFile("cc-gui-claude-cli-ok", ".sh").toFile();
        file.deleteOnExit();
        assertTrue("Test precondition: set the execute bit", file.setExecutable(true, false));

        String reason = SetClaudeCliPathActionHandler.validateCliPath(file, file.getPath());
        assertNull("A usable executable file must pass validation, got: " + reason, reason);
    }
}
