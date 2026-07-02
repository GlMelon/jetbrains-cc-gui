package com.github.claudecodegui.session.runtime;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

/**
 * 验证 {@link CodexCliResolver} 的路径缓存:每次 CLI 调用(send)都重复 spawn
 * 'codex --version' 子进程做 verify,单次冷启动 ~3s(idea.log 实测 pre-spawn 3068ms,
 * 是 Claude 的 12 倍,因 ClaudeCliDetector 有单例路径缓存而 Codex/OpenCode resolver 零缓存)。
 *
 * <p>缓存策略:只缓存成功路径(命中即返回,跳过 verify);不缓存失败(无用户配置入口打破僵局,
 * 避免首次时序导致永久找不到)。对称 {@code ClaudeCliDetector} 的 cachedCliPath。
 */
public class CodexCliResolverCacheTest {

    @After
    public void resetCache() {
        CodexCliResolver.__clearCacheForTests();
    }

    @Test
    public void cachedExecutableIsReturnedWithoutReverification() {
        // 一个 verify 必然失败的假路径(不存在 → verify 返回 false → 无缓存时 findExecutable
        // 会回退到裸名 "codex")。若返回值仍是假路径,证明走了缓存而非 verify。
        String fakeCachedPath = "/definitely/not/real/codex-" + "unique123.cmd";

        CodexCliResolver.__setCachedExecutableForTests(fakeCachedPath);

        assertEquals(fakeCachedPath, CodexCliResolver.findExecutable());
    }

    // ============ inferCodexNativeExecutablePath(纯函数路径推断) ============

    @Test
    public void inferCodexNativeExecutablePathResolvesExeBesideShim() throws IOException {
        Path tmp = Files.createTempDirectory("codex-resolver-test");
        Path exe = tmp.resolve("node_modules/@openai/codex/bin/codex.exe");
        Files.createDirectories(exe.getParent());
        Files.createFile(exe);
        String shim = tmp.resolve("codex.cmd").toString();

        // npm 全局结构:<shim-dir>/codex.cmd + <shim-dir>/node_modules/@openai/codex/bin/codex.exe
        String result = CodexCliResolver.inferCodexNativeExecutablePath(shim);
        assertEquals(exe.toFile().getAbsolutePath(), result);
    }

    @Test
    public void inferCodexNativeExecutablePathReturnsNullWhenExeMissing() throws IOException {
        Path tmp = Files.createTempDirectory("codex-resolver-test");
        String shim = tmp.resolve("codex.cmd").toString();

        // shim 存在但无原生 .exe → 返回 null(回退 .cmd)
        assertNull(CodexCliResolver.inferCodexNativeExecutablePath(shim));
    }

    @Test
    public void inferCodexNativeExecutablePathHandlesNullOrBlankShim() {
        assertNull(CodexCliResolver.inferCodexNativeExecutablePath(null));
        assertNull(CodexCliResolver.inferCodexNativeExecutablePath(""));
        assertNull(CodexCliResolver.inferCodexNativeExecutablePath("   "));
    }

    // ============ 缓存命中/清空 ============

    @Test
    public void clearingCacheForcesRedetection() {
        String fakeCachedPath = "/definitely/not/real/codex-" + "unique456.cmd";
        CodexCliResolver.__setCachedExecutableForTests(fakeCachedPath);
        assertEquals(fakeCachedPath, CodexCliResolver.findExecutable());

        // 清缓存后,findExecutable 应重新走 verify → 不再返回旧缓存假路径
        CodexCliResolver.__clearCacheForTests();
        String redetected = CodexCliResolver.findExecutable();
        // 环境无关断言:无论返回真实 codex 路径(装了)还是裸名 "codex"(没装),都绝不等于旧假路径
        assertNotEquals("清缓存后应重新检测,不返回旧缓存假路径", fakeCachedPath, redetected);
    }
}
