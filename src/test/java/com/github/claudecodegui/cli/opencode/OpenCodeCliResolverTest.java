package com.github.claudecodegui.cli.opencode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

/**
 * 修复⑤:OpenCodeCliResolver 优先解析 npm 全局结构下的原生二进制 opencode.exe,
 * 绕过 .cmd 批处理包装(避免多行 prompt 位置参数被 cmd.exe 截断 + stdin EOF 不可靠)。
 */
public class OpenCodeCliResolverTest {

    @Before
    public void clearResolverCacheBeforeTest() {
        OpenCodeCliResolver.__clearCacheForTests();
    }

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

    // ============ 路径缓存(消除每次 send 重复 spawn 'opencode --version' 的 ~3s pre-spawn) ============

    @After
    public void resetResolverCache() {
        OpenCodeCliResolver.__clearCacheForTests();
    }

    @Test
    public void cachedExecutableIsReturnedWithoutReverification() {
        // 假路径 verify 必失败(不存在);若仍返回假路径,证明走了缓存而非 verify。
        String fakeCachedPath = "/definitely/not/real/opencode-" + "unique123.exe";

        OpenCodeCliResolver.__setCachedExecutableForTests(fakeCachedPath);

        assertEquals(fakeCachedPath, OpenCodeCliResolver.findExecutable());
    }

    @Test
    public void clearingCacheForcesRedetection() {
        String fakeCachedPath = "/definitely/not/real/opencode-" + "unique456.exe";
        OpenCodeCliResolver.__setCachedExecutableForTests(fakeCachedPath);
        assertEquals(fakeCachedPath, OpenCodeCliResolver.findExecutable());

        // 清缓存后重检测:走 verify → 不再返回旧缓存假路径
        OpenCodeCliResolver.__clearCacheForTests();
        String redetected = OpenCodeCliResolver.findExecutable();
        // 环境无关断言:无论返回真实路径(装了)还是裸名 "opencode"(没装),都绝不等于旧假路径
        assertNotEquals("清缓存后应重新检测,不返回旧缓存假路径", fakeCachedPath, redetected);
    }

    @Test
    public void cachedVersionIsNullAfterClear() {
        assertNull(OpenCodeCliResolver.getCachedVersion());
    }

    @Test
    public void setCachedExecutableDoesNotAffectCachedVersion() {
        assertNull(OpenCodeCliResolver.getCachedVersion());
        OpenCodeCliResolver.__setCachedExecutableForTests("/fake/path");
        assertNull(OpenCodeCliResolver.getCachedVersion());
    }
}
