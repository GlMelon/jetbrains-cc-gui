package com.github.claudecodegui.cli.common;

import com.github.claudecodegui.util.PlatformUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 解析「用户真实 PATH」供 CLI 子进程 / serve 守护进程使用。
 *
 * <p><b>问题:</b>IDE 进程 PATH ≠ 登录 shell PATH。Windows 下经 npm 全局 / scoop / volta / bun
 * 安装的二进制(codex、opencode 等)只在登录 shell 的 PATH 中,IDE 继承的 PATH 缺失这些目录,
 * 导致 CLI 模式找不到二进制、OpenCode serve 启动失败(异常被吞 → fetch 失败)。
 *
 * <p><b>解法:</b>在 IDE 进程 PATH 基础上追加常见 shim 目录,合并去重保序(IDE PATH 在前,
 * 避免覆盖用户已有配置)。刻意不读注册表(reg query 的编码 / REG_EXPANDSZ 未展开变量处理复杂、
 * 且收益主要覆盖「用户在 HKCU 自定义 PATH」的少数场景);常见 npm/scoop/volta/nodejs/bun 安装
 * 已被 {@link #commonWindowsShimDirs()} 覆盖。fnm 的 multishells 路径含动态 hash,不在覆盖范围
 * (用户可改用 volta/scoop 或手动配置 PATH)。
 *
 * <p>Unix 侧 IDE 通常从登录 shell 启动并继承完整 PATH,直接透传,不做追加。
 */
public final class UserPathResolver {

    /**
     * 成功路径永久缓存。PATH 在进程内不变(由 env 变量决定),{@code resolveUserPath()} 经
     * {@code CliEnvironmentBuilder.copyPath} / {@code OpenCodeCliResolver.searchInPath} /
     * {@code OpenCodeDaemonCoordinator} 每轮 send 多次调用,缓存后跳过重复 split/merge/dedup。
     * 范式:{@code OpenCodeCliResolver.cachedExecutable}(double-check + synchronized,只缓存成功)。
     * 只缓存非 null:Unix 下 idePath 可能 null,让失败每轮重试(罕见,无优化必要)。
     */
    private static volatile String cachedUserPath;
    private static final Object CACHE_LOCK = new Object();

    private UserPathResolver() {
    }

    /**
     * 返回完整用户 PATH:IDE 进程 PATH + 常见 shim 目录(去重保序)。
     * 用于 CLI 子进程环境({@code CliEnvironmentBuilder})与 serve 守护进程({@code OpenCodeDaemonCoordinator})。
     */
    public static String resolveUserPath() {
        String cached = cachedUserPath;
        if (cached != null) {
            return cached;
        }
        synchronized (CACHE_LOCK) {
            if (cachedUserPath != null) {
                return cachedUserPath;
            }
            String result = doResolveUserPath();
            if (result != null) {
                cachedUserPath = result;
            }
            return result;
        }
    }

    private static String doResolveUserPath() {
        boolean windows = PlatformUtils.isWindows();
        String idePath = windows ? PlatformUtils.getEnvIgnoreCase("PATH") : System.getenv("PATH");
        if (!windows) {
            return idePath;
        }
        return mergePath(idePath, commonWindowsShimDirs(), ";");
    }

    /** 测试钩子:直接注入缓存路径(验证缓存命中语义,跳过重算)。 */
    static void __setCachedUserPathForTests(String path) {
        synchronized (CACHE_LOCK) {
            cachedUserPath = path;
        }
    }

    /** 测试钩子:清空缓存,强制下次 resolveUserPath 重新计算。 */
    static void __clearCacheForTests() {
        synchronized (CACHE_LOCK) {
            cachedUserPath = null;
        }
    }

    /**
     * Windows 常见二进制 shim 目录(基于 env 变量构造绝对路径)。
     * 供 {@link com.github.claudecodegui.bridge.EnvironmentConfigurator} 复用(其自带 nodeDir 前置逻辑)。
     */
    public static List<String> commonWindowsShimDirs() {
        return commonWindowsShimDirs(
                PlatformUtils.getEnvIgnoreCase("LOCALAPPDATA"),
                PlatformUtils.getEnvIgnoreCase("APPDATA"),
                PlatformUtils.getHomeDirectory(),
                PlatformUtils.getEnvIgnoreCase("ProgramFiles"));
    }

    /**
     * 纯函数:基于已知 env 变量值构造 shim 目录列表(无 Platform 依赖,便于单测)。
     */
    static List<String> commonWindowsShimDirs(String localAppData, String appData, String userProfile, String programFiles) {
        List<String> dirs = new ArrayList<>();
        if (appData != null && !appData.isBlank()) {
            dirs.add(appData + "\\npm"); // npm 全局二进制(codex/opencode 等经 npm i -g 安装)
        }
        if (userProfile != null && !userProfile.isBlank()) {
            dirs.add(userProfile + "\\scoop\\shims"); // scoop 全局 shim
            dirs.add(userProfile + "\\.bun\\bin"); // bun 全局
        }
        if (localAppData != null && !localAppData.isBlank()) {
            dirs.add(localAppData + "\\Volta\\bin"); // volta shim(node/binary manager)
            dirs.add(localAppData + "\\Programs\\nodejs"); // nodejs 官方安装器(per-user)
        }
        if (programFiles != null && !programFiles.isBlank()) {
            dirs.add(programFiles + "\\nodejs"); // nodejs 官方安装器(machine)
        }
        return dirs;
    }

    /**
     * 合并基础 PATH 与额外目录,去重保序(Windows 大小写不敏感)。
     *
     * @param basePath  原始 PATH(可能为 null)
     * @param extra     追加目录列表
     * @param separator 路径分隔符(Windows ";",Unix ":")
     * @return 合并去重后的 PATH;basePath 与 extra 均空时返回 basePath 原值
     */
    static String mergePath(String basePath, List<String> extra, String separator) {
        List<String> entries = new ArrayList<>();
        if (basePath != null && !basePath.isBlank()) {
            for (String e : basePath.split(Pattern.quote(separator))) {
                if (!e.isBlank()) {
                    entries.add(e.trim());
                }
            }
        }
        if (extra != null) {
            for (String e : extra) {
                if (e != null && !e.isBlank()) {
                    entries.add(e.trim());
                }
            }
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> deduped = new ArrayList<>();
        for (String e : entries) {
            if (seen.add(e.toLowerCase())) {
                deduped.add(e);
            }
        }
        return deduped.isEmpty() ? basePath : String.join(separator, deduped);
    }
}
