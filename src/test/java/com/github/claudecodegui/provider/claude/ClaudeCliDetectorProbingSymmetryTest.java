package com.github.claudecodegui.provider.claude;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 源码字符串检查(对称 CliMcpGatewaySymmetryTest 范式):守护 ClaudeCliDetector 探测链路
 * 与其余 provider resolver(CodexCliResolver / ProviderCliResolver)的对称性,防回归到
 * 「永久负缓存 + 裸 IDE PATH + Windows 不查 npm prefix」。
 */
public class ClaudeCliDetectorProbingSymmetryTest {

    private static String readSource() throws Exception {
        Path path = Path.of("src", "main", "java", "com", "github", "claudecodegui",
                "provider", "claude", "ClaudeCliDetector.java");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    @Test
    public void negativeDetectionCacheExpiresInsteadOfLastingForever() throws Exception {
        String source = readSource();

        // 永久负缓存(detectionAttempted 布尔)会在用户安装 CLI 后仍返回 null 直到重启 IDE;
        // 对称 CodexCliResolver/ProviderCliResolver 的 "never cache a failed probe",
        // 至少要有 TTL 自愈(终端手动安装场景)。
        assertTrue(source.contains("NEGATIVE_CACHE_TTL_MS"));
        assertTrue(source.contains("isNegativeCacheFresh()"));
        assertFalse(source.contains("detectionAttempted;"));
    }

    @Test
    public void pathProbingResolvesUserPathNotBareIdePath() throws Exception {
        String source = readSource();

        // where/prefix 子进程与 PATH 遍历都必须用 UserPathResolver(IDE PATH + npm/scoop/
        // volta 等 shim 目录),对称 ProviderCliResolver.searchInPath;裸 IDE PATH 会漏检
        // 仅存在于登录 shell PATH 的 npm 全局安装。
        assertTrue(source.contains("UserPathResolver.resolveUserPath()"));
        assertTrue(countOccurrences(source, "UserPathResolver.resolveUserPath()") >= 3);
        assertFalse(source.contains("PlatformUtils.getEnvIgnoreCase(\"PATH\")"));
    }

    @Test
    public void windowsKnownPathsCoverLocalAppDataNpmAndNpmGlobalPrefix() throws Exception {
        String source = readSource();

        // %LOCALAPPDATA%\npm(部分 node 发行版/用户级安装的 npm 默认全局目录)与
        // npm prefix -g 反查(用户 npmrc 自定义 prefix)都必须在 Windows 候选路径内,
        // 对齐 CliEnvironmentChecker.getSearchDirectories 的目录表。
        assertTrue(source.contains("localAppData + \"\\\\npm\\\\claude.cmd\""));
        assertTrue(source.contains("npmGlobalPrefix + \"\\\\claude.cmd\""));
    }

    @Test
    public void npmPrefixProbeUsesNpmCmdOnWindows() throws Exception {
        String source = readSource();

        // Windows 下 ProcessBuilder 不按 PATHEXT 解析,裸 "npm" 会 IOException 被吞,
        // prefix 探测永远失败;必须与安装/版本查询一致使用 npm.cmd。
        int prefixIndex = source.indexOf("private String detectNpmGlobalPrefix()");
        assertTrue(prefixIndex > 0);
        String prefixMethod = source.substring(prefixIndex, Math.min(source.length(), prefixIndex + 1200));
        assertTrue(prefixMethod.contains("PlatformUtils.isWindows() ? \"npm.cmd\" : \"npm\""));
        assertFalse(prefixMethod.contains("ProcessBuilder(\"npm\", \"prefix\""));
    }
}
