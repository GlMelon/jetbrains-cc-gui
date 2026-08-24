package com.github.claudecodegui.util;

import com.intellij.openapi.util.io.FileUtil;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

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

    /**
     * 逐个删除临时附件文件,忽略 null 与不存在项。
     * 使用平台 {@link FileUtil#delete}(处理 Windows 只读属性并带重试),
     * 比裸 {@code File.delete()} 删除成功率高。
     */
    public static void deleteFilesQuietly(List<File> files) {
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f != null && f.exists()) {
                FileUtil.delete(f);
            }
        }
    }
}
