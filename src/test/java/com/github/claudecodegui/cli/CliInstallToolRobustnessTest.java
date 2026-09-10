package com.github.claudecodegui.cli;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 源码字符串检查(对称 CliMcpGatewaySymmetryTest 范式):守护 npm 一键安装链路的
 * 三项健壮性约定,防回归到「120s 超时截断 + 管道满卡死 + 杀不干净 + 安装后仍 not found」。
 * Platform 耦合无法纯单测,以源码断言兜底。
 */
public class CliInstallToolRobustnessTest {

    private static String readSource() throws Exception {
        Path path = Path.of("src", "main", "java", "com", "github", "claudecodegui",
                "cli", "CliEnvironmentChecker.java");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String extractInstallCliToolMethod() throws Exception {
        String source = readSource();
        int start = source.indexOf("public CliEnvironmentStatus installCliTool");
        int end = source.indexOf("private static StringBuilder captureProcessOutput");
        assertTrue(start >= 0 && end > start);
        return source.substring(start, end);
    }

    @Test
    public void installTimeoutCoversLargeNativeBinaryPackages() throws Exception {
        String source = readSource();

        // claude-code 经 optionalDependencies 携带 ~200MB 平台原生二进制,首次安装
        // 从镜像下载远超 2 分钟;120s 会在下载中途截断安装(npm 缓存落 C 盘 + 半成品)。
        assertTrue(source.contains("INSTALL_TIMEOUT_SECONDS = 600"));
        assertTrue(source.contains("process.waitFor(INSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)"));
        assertFalse(source.contains("waitFor(120, TimeUnit.SECONDS)"));
    }

    @Test
    public void installOutputIsDrainedToAvoidPipeBackpressure() throws Exception {
        String source = readSource();

        // npm 输出可撑满 OS 管道缓冲,写阻塞会让安装进程卡死表现为必超时;
        // 等待期间必须有后台 drain(总则六:stdout 必须 drain)。
        assertTrue(source.contains("captureProcessOutput(process)"));
        assertTrue(source.contains("\"cli-install-output-drain\""));
    }

    @Test
    public void installCancellationTerminatesProcessTree() throws Exception {
        String installMethod = extractInstallCliToolMethod();

        // npm.cmd 在 Windows 是 cmd.exe 包装,destroyForcibly 只杀包装层,
        // node.exe 安装子进程残留;必须用 terminateProcessAndWait(taskkill /F /T)杀整树。
        // (getVersion/getLatestVersionFromNpm 的 10s 探测进程不在守护范围。)
        assertTrue(installMethod.contains("PlatformUtils.terminateProcessAndWait(process, 10, TimeUnit.SECONDS)"));
        assertFalse(installMethod.contains("process.destroyForcibly()"));
    }

    @Test
    public void installLocationIsLeftToNpmItself() throws Exception {
        String installMethod = extractInstallCliToolMethod();

        // 安装位置必须与用户终端行为一致:同一 npm.cmd + 同一用户级 ~/.npmrc,
        // prefix 交由 npm 决定;禁止插件传 --prefix 覆盖用户 npmrc 配置。
        assertFalse(installMethod.contains("\"--prefix\""));
        assertFalse(installMethod.contains("toolDef.npmPackage + \"@latest\","));
    }

    @Test
    public void successfulInstallInvalidatesResolverCaches() throws Exception {
        String source = readSource();

        // ClaudeCliDetector 失败结果会被缓存,安装成功后不清缓存则使用侧仍报
        // "Claude CLI not found" 直到重启 IDE;成功路径必须主动失效全部 provider 缓存。
        assertTrue(source.contains("invalidateResolverCaches();"));
        assertTrue(source.contains("ClaudeCliDetector.getInstance().clearCache()"));
        assertTrue(source.contains("CodexCliResolver.clearCache()"));
        assertTrue(source.contains("ProviderCliResolver.clearAllCaches()"));
    }

    @Test
    public void uninstallRunsNpmUninstallGlobalOnBarePackageName() throws Exception {
        String source = readSource();

        // 卸载 = npm uninstall -g <pkg>(裸包名,不带 @latest)。
        assertTrue(source.contains("\"uninstall\", \"-g\", toolDef.npmPackage)"));
    }

    @Test
    public void uninstallDrainsOutputAndTerminatesProcessTree() throws Exception {
        String source = readSource();
        int start = source.indexOf("public CliEnvironmentStatus uninstallCliTool");
        int end = source.indexOf("private static StringBuilder captureProcessOutput");
        assertTrue(start >= 0 && end > start);
        String uninstallMethod = source.substring(start, end);

        // 与安装同源的健壮性:drain 防管道卡死 + 杀整树防 node.exe 残留。
        assertTrue(uninstallMethod.contains("captureProcessOutput(process)"));
        assertTrue(uninstallMethod.contains("PlatformUtils.terminateProcessAndWait(process, 10, TimeUnit.SECONDS)"));
        assertFalse(uninstallMethod.contains("process.destroyForcibly()"));
        // 卸载成功必须失效探测缓存:成功缓存指向已删除路径,不清则下轮 spawn 不存在的文件。
        assertTrue(uninstallMethod.contains("invalidateResolverCaches();"));
        // 卸载成功(installed=false)是预期终态,error 必须清空避免 handler 误判失败。
        assertTrue(uninstallMethod.contains("status.setError(null)"));
    }
}
