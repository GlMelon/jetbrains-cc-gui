package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * OCP typed handler:取代旧 SettingsHandler 对 set_claude_cli_path 的字符串派发
 * + ClaudeCliPathHandler.handleSetClaudeCliPath 委托(AGENTS.md §2 开闭原则)。
 *
 * <p>校验路径(存在/非目录/可执行)→ 持久化到 {@link PropertiesComponent} → 关闭 daemon
 * 使下次请求以新的 CLAUDE_CODE_PATH 重启 → 经 {@code config.claude_cli_path} + {@code toast}
 * 事件回传前端,与旧实现逐字等价。失败时回显用户输入而非清空。
 */
public final class SetClaudeCliPathActionHandler implements FrontendActionHandler<String> {

    private static final Logger LOG = Logger.getInstance(SetClaudeCliPathActionHandler.class);
    private static final Gson GSON = new Gson();
    private static final String CLAUDE_CLI_PATH_PROPERTY_KEY = "claude.code.cli.path";

    @Override
    public UpstreamAction action() {
        return UpstreamAction.SET_CLAUDE_CLI_PATH;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        // JSON 解析在调用线程(CEF IO)同步做,纯解析无 I/O;校验/写盘/进程派生放后台线程
        String parsedPath = null;
        try {
            JsonObject json = GSON.fromJson(payload, JsonObject.class);
            if (json != null && json.has("path") && !json.get("path").isJsonNull()) {
                parsedPath = json.get("path").getAsString();
            }
        } catch (Exception e) {
            LOG.error("[SetClaudeCliPathActionHandler] Failed to parse set_claude_cli_path content: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                    ctx.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), ctx.escapeJs("Failed to save Claude CLI path: " + e.getMessage()))
            );
            return;
        }
        final String pathArg = (parsedPath != null) ? parsedPath.trim() : null;

        CompletableFuture.runAsync(() -> {
            try {
                PropertiesComponent props = PropertiesComponent.getInstance();
                String finalPath = "";
                boolean success = false;
                String failureMsg = null;

                if (pathArg == null || pathArg.isEmpty()) {
                    props.unsetValue(CLAUDE_CLI_PATH_PROPERTY_KEY);
                    LOG.info("[SetClaudeCliPathActionHandler] Cleared custom Claude CLI path");
                    success = true;
                } else {
                    failureMsg = validateCliPath(new File(pathArg), pathArg);
                    if (failureMsg == null) {
                        props.setValue(CLAUDE_CLI_PATH_PROPERTY_KEY, pathArg);
                        finalPath = pathArg;
                        success = true;
                        LOG.info("[SetClaudeCliPathActionHandler] Saved custom Claude CLI path: " + pathArg);
                    }
                }

                // 重启 daemon 使 CLAUDE_CODE_PATH 在下次请求时重新注入(env 仅在 spawn 时读)。
                // shutdownDaemon 安全:下次请求经 ClaudeDaemonCoordinator 触发全新启动。
                if (success) {
                    try {
                        ctx.getClaudeSDKBridge().shutdownDaemon();
                    } catch (Exception e) {
                        LOG.warn("[SetClaudeCliPathActionHandler] Failed to shutdown daemon after path change: " + e.getMessage());
                    }
                }

                final boolean successFlag = success;
                final String failureMsgFinal = failureMsg;
                final String finalPathToSend = finalPath;
                // 失败时回显用户输入,避免输入框被清空;成功时回显持久化的值
                final String pathToEcho = successFlag
                        ? finalPathToSend
                        : (pathArg != null ? pathArg : "");

                ApplicationManager.getApplication().invokeLater(() -> {
                    JsonObject response = new JsonObject();
                    response.addProperty("path", pathToEcho);
                    ctx.dispatchEvent(DownstreamEvent.CONFIG_CLAUDE_CLI_PATH.value(), ctx.escapeJs(GSON.toJson(response)));

                    if (successFlag) {
                        String msg = finalPathToSend.isEmpty()
                                ? "Claude CLI path cleared, using bundled SDK"
                                : "Claude CLI path saved: " + finalPathToSend;
                        ctx.dispatchEvent(DownstreamEvent.TOAST_SWITCH_SUCCESS.value(), ctx.escapeJs(msg));
                    } else {
                        String msg = failureMsgFinal != null ? failureMsgFinal : "Invalid Claude CLI path";
                        ctx.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), ctx.escapeJs(msg));
                    }
                });
            } catch (Exception e) {
                LOG.error("[SetClaudeCliPathActionHandler] Failed to set Claude CLI path: " + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() ->
                        ctx.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), ctx.escapeJs("Failed to save Claude CLI path: " + e.getMessage()))
                );
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[SetClaudeCliPathActionHandler] Unexpected error in handle: " + ex.getMessage(), ex);
            return null;
        });
    }

    /**
     * Validates a candidate Claude CLI path. Returns {@code null} when the path is a
     * usable executable file, otherwise a human-readable reason. Extracted as a pure
     * static method so the validation branches can be unit-tested without booting the
     * IntelliJ platform (the handler itself depends on {@link PropertiesComponent}).
     */
    static String validateCliPath(File f, String rawPath) {
        if (!f.exists()) {
            return "File does not exist: " + rawPath;
        }
        if (f.isDirectory()) {
            return "Path is a directory, expected an executable file: " + rawPath;
        }
        if (!f.canExecute()) {
            return "File is not executable (check permissions): " + rawPath;
        }
        return null;
    }
}
