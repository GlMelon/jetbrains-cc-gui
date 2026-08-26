package com.github.claudecodegui.cli.opencode;

import com.github.claudecodegui.cli.common.ProviderCliResolver;
import com.github.claudecodegui.session.runtime.ProviderType;

/**
 * Resolves the OpenCode CLI executable.
 * <p>
 * 薄委托 {@link ProviderCliResolver}(合并自原 Grok/Kimi/Pi/OpenCode 四个同构 Resolver,
 * 差异仅 ProviderType 与 npm 目录名)。保留本类静态门面:{@code BridgePreloader} 预热与
 * {@code OpenCodeCliResolverTest} 不动,且与 grok/kimi/pi 会话解析共享同一份
 * per-type 缓存(预热命中一致)。
 * <p>
 * OpenCode 的 npm 目录名是 {@code "opencode-ai"}(非裸名),经构造参数传入。
 */
public final class OpenCodeCliResolver {

    private static final ProviderCliResolver DELEGATE =
            new ProviderCliResolver(ProviderType.OPENCODE, "opencode-ai");

    private OpenCodeCliResolver() {
    }

    public static String findExecutable() {
        return DELEGATE.findExecutable();
    }

    /** 测试钩子:直接注入缓存路径,跳过 verify(验证缓存命中语义)。 */
    static void __setCachedExecutableForTests(String path) {
        ProviderCliResolver.__setCachedExecutableForTests(ProviderType.OPENCODE, path);
    }

    /** 测试钩子:清空缓存,强制下次 findExecutable 重新检测。 */
    static void __clearCacheForTests() {
        ProviderCliResolver.__clearCacheForTests(ProviderType.OPENCODE);
    }

    /**
     * 返回缓存的 CLI 版本字符串,或 null(未检测 / 检测失败)。
     * 对称 ClaudeCliDetector.getCachedCliVersion()。
     */
    public static String getCachedVersion() {
        return ProviderCliResolver.getCachedVersion(ProviderType.OPENCODE);
    }

    /**
     * 从 opencode shim 路径推断 npm 全局结构下的原生二进制入口
     * ({@code <shim-dir>/node_modules/opencode-ai/bin/opencode.exe})。纯路径逻辑,不验证可执行性。
     *
     * @param shimPath opencode shim(opencode.cmd/opencode)的绝对或相对路径
     * @return 原生 .exe 绝对路径;结构不存在或入参无效时返回 null
     */
    static String inferNativeExecutablePath(String shimPath) {
        return ProviderCliResolver.inferNativeExecutablePath(
                shimPath, "opencode-ai", ProviderType.OPENCODE.cliCommand());
    }
}
