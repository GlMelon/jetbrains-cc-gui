package com.github.claudecodegui.util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CLI 专用临时目录工具,使 cli 包无需依赖 ai-bridge 的 {@code bridge.ProcessManager}。
 *
 * <p>复用 ProcessManager 的托管临时目录名("claude-agent-tmp"),确保既有清理 allow-list
 * 仍能覆盖 CLI 落盘的附件临时文件。</p>
 */
public final class CliTempDir {

    private static final String CLAUDE_TEMP_DIR_NAME = "claude-agent-tmp";

    private CliTempDir() {
    }

    /**
     * 返回受管理的临时目录(不存在则创建)。
     * 基础 tmpdir 缺失或目录创建失败时返回 null(调用方应回退到系统默认临时目录)。
     */
    public static File getManagedTempDir() {
        String baseTemp = System.getProperty("java.io.tmpdir");
        if (baseTemp == null || baseTemp.isEmpty()) {
            return null;
        }
        try {
            Path tempPath = Paths.get(baseTemp, CLAUDE_TEMP_DIR_NAME);
            Files.createDirectories(tempPath);
            return tempPath.toFile();
        } catch (Exception ignored) {
            return null;
        }
    }
}
