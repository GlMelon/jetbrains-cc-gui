package com.github.claudecodegui.cli.common;

import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * UserPathResolver 进程生命周期永久缓存测试。
 * <p>resolveUserPath() 经 CliEnvironmentBuilder.copyPath / OpenCodeCliResolver.searchInPath /
 * OpenCode CLI per-process 每轮 send 多次调用。PATH 在进程内不变,永久缓存(范式 OpenCodeCliResolver)。
 * 只缓存成功(非 null),失败每轮重试。
 */
public class UserPathResolverCacheTest {

    @After
    public void tearDown() {
        UserPathResolver.__clearCacheForTests();
    }

    @Test
    public void resolveUserPathReturnsCachedValueWhenInjected() {
        UserPathResolver.__clearCacheForTests();
        UserPathResolver.__setCachedUserPathForTests("/sentinel/path");
        assertEquals("缓存命中应返回注入的 sentinel", "/sentinel/path", UserPathResolver.resolveUserPath());
    }

    @Test
    public void resolveUserPathPopulatesCacheOnFirstCall() throws Exception {
        UserPathResolver.__clearCacheForTests();
        String first = UserPathResolver.resolveUserPath();
        assertNotNull("首次调用应返回非 null(测试在桌面环境跑,PATH 非空)", first);

        // 反射读 cachedUserPath 字段,验证首次调用已把结果写入缓存(永久缓存填充语义)。
        // 不能用 __setCachedUserPathForTests 注入 sentinel 再调 resolveUserPath 验证——
        // 该钩子语义就是覆盖缓存,注入后自然返回注入值,无法证明"首次调用填充了缓存"。
        Field f = UserPathResolver.class.getDeclaredField("cachedUserPath");
        f.setAccessible(true);
        String cached = (String) f.get(null);
        assertEquals("首次调用应把结果写入 cachedUserPath 字段", first, cached);
    }

    @Test
    public void clearCacheForcesRefetch() {
        UserPathResolver.__clearCacheForTests();
        UserPathResolver.__setCachedUserPathForTests("/first");
        assertEquals("/first", UserPathResolver.resolveUserPath());

        UserPathResolver.__clearCacheForTests();
        UserPathResolver.__setCachedUserPathForTests("/second");
        assertEquals("清缓存后注入新值,应返回新值", "/second", UserPathResolver.resolveUserPath());
    }
}
