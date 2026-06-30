package com.github.claudecodegui.cli.opencode;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 修复⑤:OpenCodeCliResolver 优先解析 npm 全局结构下的原生二进制 opencode.exe,
 * 绕过 .cmd 批处理包装(避免多行 prompt 位置参数被 cmd.exe 截断 + stdin EOF 不可靠)。
 */
public class OpenCodeCliResolverTest {

    @Test
    public void inferNativeExecutablePathResolvesExeBesideShim() throws IOException {
        Path tmp = Files.createTempDirectory("opencode-resolver-test");
        Path exe = tmp.resolve("node_modules/opencode-ai/bin/opencode.exe");
        Files.createDirectories(exe.getParent());
        Files.createFile(exe);
        String shim = tmp.resolve("opencode.cmd").toString();

        String result = OpenCodeCliResolver.inferNativeExecutablePath(shim);

        // npm 全局结构:<shim-dir>/opencode.cmd + <shim-dir>/node_modules/opencode-ai/bin/opencode.exe
        assertEquals(exe.toFile().getAbsolutePath(), result);
    }

    @Test
    public void inferNativeExecutablePathReturnsNullWhenExeMissing() throws IOException {
        Path tmp = Files.createTempDirectory("opencode-resolver-test");
        String shim = tmp.resolve("opencode.cmd").toString();

        // shim 存在但无原生 .exe → 返回 null(回退 .cmd)
        assertNull(OpenCodeCliResolver.inferNativeExecutablePath(shim));
    }

    @Test
    public void inferNativeExecutablePathHandlesNullOrBlankShim() {
        assertNull(OpenCodeCliResolver.inferNativeExecutablePath(null));
        assertNull(OpenCodeCliResolver.inferNativeExecutablePath(""));
        assertNull(OpenCodeCliResolver.inferNativeExecutablePath("   "));
    }
}
