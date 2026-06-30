package com.github.claudecodegui.mcp;

import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.settings.ConfigPathManager;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.startup.BridgePreloader;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Project-scoped facade for the CLI MCP Gateway.
 */
@Service(Service.Level.PROJECT)
public final class McpGatewayService implements Disposable {
    private static final Logger LOG = Logger.getInstance(McpGatewayService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Project project;
    private final Object lock = new Object();
    private final McpGatewayConfigCollector collector;
    private final McpGatewayConfigWriter configWriter;
    private final Path gatewayDir;
    private final Path stateFile;
    private final String token;

    private McpGatewayProcessHandle processHandle;
    private McpGatewayBridgeClient bridgeClient;
    private McpGatewayConfigSnapshot currentSnapshot;
    private long currentRevision;

    public McpGatewayService(@NotNull Project project) {
        this.project = project;
        this.collector = new McpGatewayConfigCollector(CodemossSettingsService.getInstance());
        this.gatewayDir = new ConfigPathManager().getConfigDir()
                .resolve(McpGatewayConstants.DIRECTORY_NAME)
                .resolve(safeProjectPart(project.getBasePath()));
        this.stateFile = gatewayDir.resolve(McpGatewayConstants.STATE_FILE_NAME);
        this.configWriter = new McpGatewayConfigWriter(gatewayDir.resolve(McpGatewayConstants.CONFIG_DIRECTORY_NAME));
        this.token = generateToken();
    }

    public static McpGatewayService getInstance(@NotNull Project project) {
        return project.getService(McpGatewayService.class);
    }

    public McpGatewayCliConfig buildCliConfig(ProviderType provider, String tabId, String projectPath) {
        if (!McpGatewayFeatureFlags.isCliEnabled()) {
            return McpGatewayCliConfig.disabled("MCP Gateway CLI feature disabled");
        }
        synchronized (lock) {
            try {
                ensureStarted(projectPath);
                refreshConfig(projectPath);
                File bridgeDir = BridgePreloader.getSharedResolver().findSdkDir();
                if (bridgeDir == null) {
                    return McpGatewayCliConfig.disabled("ai-bridge directory unavailable");
                }
                String node = NodeDetector.getInstance().findNodeExecutable();
                Path stdioClient = bridgeDir.toPath().resolve(McpGatewayConstants.STDIO_CLIENT_SCRIPT_PATH);
                List<String> command = NodeDetector.buildNodeScriptCommand(node, stdioClient.toString());
                return configWriter.write(provider, tabId, currentRevision, stateFile, command);
            } catch (Exception e) {
                LOG.warn("[McpGateway] Falling back to direct MCP config: " + e.getMessage(), e);
                return McpGatewayCliConfig.disabled(e.getMessage());
            }
        }
    }

    /**
     * Builds the SDK binding for one SDK turn. Unlike {@link #buildCliConfig} no temp
     * config file is written: the stdio-client command is handed to the Node side
     * (mcp-gateway-binding.js) so the SDK spawns the single aggregated melon_gateway
     * server directly. Supports CLAUDE(per-call mcpServers option)and CODEX
     * (Node 翻译成 codexOptions.config 的 mcp_servers.melon_gateway overlay);其它 provider
     * 返回 disabled(OpenCode 的 SDK gateway 是 serve 启动期注入,不走此 stdio-client 路径)。
     */
    public McpGatewaySdkBinding buildSdkMcpServers(ProviderType provider, String projectPath) {
        if (!McpGatewayFeatureFlags.isSdkEnabled()) {
            return McpGatewaySdkBinding.disabled("MCP Gateway SDK feature disabled");
        }
        if (provider != ProviderType.CLAUDE && provider != ProviderType.CODEX) {
            return McpGatewaySdkBinding.disabled(
                    "MCP Gateway SDK not yet supported for " + provider.value());
        }
        synchronized (lock) {
            try {
                ensureStarted(projectPath);
                applySnapshot(projectPath);
                File bridgeDir = BridgePreloader.getSharedResolver().findSdkDir();
                if (bridgeDir == null) {
                    return McpGatewaySdkBinding.disabled("ai-bridge directory unavailable");
                }
                String node = NodeDetector.getInstance().findNodeExecutable();
                Path stdioClient = bridgeDir.toPath().resolve(McpGatewayConstants.STDIO_CLIENT_SCRIPT_PATH);
                List<String> command = new java.util.ArrayList<>(
                        NodeDetector.buildNodeScriptCommand(node, stdioClient.toString()));
                command.add(McpGatewayConstants.ARG_STATE_FILE);
                command.add(stateFile.toAbsolutePath().toString());
                command.add(McpGatewayConstants.ARG_REVISION);
                command.add(Long.toString(currentRevision));
                return new McpGatewaySdkBinding(true, true, currentRevision, command, null);
            } catch (Exception e) {
                LOG.warn("[McpGateway] Falling back to direct MCP for SDK: " + e.getMessage(), e);
                return McpGatewaySdkBinding.disabled(e.getMessage());
            }
        }
    }

    /**
     * SDK 调用模式下为 OpenCode {@code serve} 守护进程构建 gateway 配置(env-based)。
     * <p>
     * 与 {@link #buildSdkMcpServers}(Claude/Codex 的 per-query stdio 绑定)对称,但 OpenCode
     * serve 是长驻进程、MCP 固化于启动期(从 opencode.json 经 XDG_CONFIG_HOME 读取),无 per-turn
     * 注入通道。故返回 CLI 风格的 {@link McpGatewayCliConfig}:其 {@code environment()} 含
     * HOME/XDG_CONFIG_HOME 等指向临时 opencode.json(含 melon_gateway + 稳定段)的环境变量,
     * 由 {@code OpenCodeDaemonCoordinator} 在启动 serve 时注入 ProcessBuilder。仅 OPENCODE 走此
     * env 路径;CLAUDE/CODEX 用 {@link #buildSdkMcpServers}(per-call mcpServers/config overlay)。
     * revision 经 {@code applySnapshot} 与 CLI/SDK 路径共享,serve 据其检测漂移并重启。
     */
    public McpGatewayCliConfig buildSdkServeConfig(ProviderType provider, String tabId, String projectPath) {
        if (!McpGatewayFeatureFlags.isSdkEnabled()) {
            return McpGatewayCliConfig.disabled("MCP Gateway SDK feature disabled");
        }
        if (provider != ProviderType.OPENCODE) {
            return McpGatewayCliConfig.disabled(
                    "SDK serve config only for " + ProviderType.OPENCODE.value());
        }
        synchronized (lock) {
            try {
                ensureStarted(projectPath);
                applySnapshot(projectPath);
                File bridgeDir = BridgePreloader.getSharedResolver().findSdkDir();
                if (bridgeDir == null) {
                    return McpGatewayCliConfig.disabled("ai-bridge directory unavailable");
                }
                String node = NodeDetector.getInstance().findNodeExecutable();
                Path stdioClient = bridgeDir.toPath().resolve(McpGatewayConstants.STDIO_CLIENT_SCRIPT_PATH);
                List<String> command = NodeDetector.buildNodeScriptCommand(node, stdioClient.toString());
                return configWriter.write(provider, tabId, currentRevision, stateFile, command);
            } catch (Exception e) {
                LOG.warn("[McpGateway] Falling back to direct MCP for OpenCode serve: " + e.getMessage(), e);
                return McpGatewayCliConfig.disabled(e.getMessage());
            }
        }
    }

    public void refreshConfig(String projectPath) {
        if (!McpGatewayFeatureFlags.isCliEnabled()) {
            return;
        }
        synchronized (lock) {
            try {
                ensureStarted(projectPath);
                applySnapshot(projectPath);
            } catch (Exception e) {
                LOG.warn("[McpGateway] Failed to refresh Gateway config: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Collects the latest snapshot and pushes it to the Gateway process, bumping the
     * revision only when the config hash actually changes. Shared by the CLI refresh
     * path (CLI-gated) and the SDK binding path (SDK-gated) so both runtimes see the
     * same fixed revision for a given turn.
     */
    private void applySnapshot(String projectPath) throws Exception {
        long candidateRevision = currentRevision == 0L ? 1L : currentRevision + 1L;
        McpGatewayConfigSnapshot candidate = collector.collect(candidateRevision, projectPath);
        if (currentSnapshot != null && currentSnapshot.configHash().equals(candidate.configHash())) {
            return;
        }
        currentRevision = candidateRevision;
        currentSnapshot = candidate;
        bridgeClient.postSnapshot(candidate);
    }

    public String statusJson() {
        synchronized (lock) {
            try {
                if (bridgeClient == null) {
                    return "{}";
                }
                return bridgeClient.status().toString();
            } catch (Exception e) {
                LOG.warn("[McpGateway] Failed to query status: " + e.getMessage());
                return "{}";
            }
        }
    }

    private void ensureStarted(String projectPath) throws Exception {
        if (processHandle != null && processHandle.isAlive() && bridgeClient != null
                && bridgeClient.waitUntilReady(Duration.ofMillis(10))) {
            return;
        }
        Files.createDirectories(gatewayDir);
        Files.deleteIfExists(stateFile);

        File bridgeDir = BridgePreloader.getSharedResolver().findSdkDir();
        if (bridgeDir == null) {
            throw new IllegalStateException("ai-bridge directory unavailable");
        }
        String node = NodeDetector.getInstance().findNodeExecutable();
        Path serverScript = bridgeDir.toPath().resolve(McpGatewayConstants.SERVER_SCRIPT_NAME);
        List<String> command = new java.util.ArrayList<>(NodeDetector.buildNodeScriptCommand(node, serverScript.toString()));
        command.add(McpGatewayConstants.ARG_STATE_FILE);
        command.add(stateFile.toAbsolutePath().toString());
        command.add(McpGatewayConstants.ARG_TOKEN);
        command.add(token);
        command.add(McpGatewayConstants.ARG_PROJECT_PATH);
        command.add(projectPath != null ? projectPath : "");

        processHandle = McpGatewayProcessHandle.start(command);
        bridgeClient = new McpGatewayBridgeClient(stateFile, token);
        if (!bridgeClient.waitUntilReady(Duration.ofSeconds(10))) {
            throw new IllegalStateException("MCP Gateway did not become ready");
        }
    }

    @Override
    public void dispose() {
        synchronized (lock) {
            try {
                if (bridgeClient != null) {
                    bridgeClient.stop();
                }
            } catch (Exception e) {
                LOG.debug("[McpGateway] Stop API failed: " + e.getMessage());
            }
            if (processHandle != null) {
                processHandle.stop();
            }
            try {
                Files.deleteIfExists(stateFile);
            } catch (Exception ignored) {
            }
        }
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String safeProjectPart(String projectPath) {
        String raw = projectPath == null || projectPath.isBlank()
                ? "default"
                : Integer.toHexString(projectPath.hashCode());
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
