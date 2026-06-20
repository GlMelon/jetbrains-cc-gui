package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.concurrent.CompletableFuture;

/**
 * OCP typed handler:取代旧 SettingsHandler 对 get_claude_cli_path 的字符串派发
 * + ClaudeCliPathHandler.handleGetClaudeCliPath 委托(AGENTS.md §2 开闭原则)。
 *
 * <p>从 {@link PropertiesComponent} 读取已保存的 Claude CLI 路径(空串表示未设置),
 * 异步经单一 {@code config.claude_cli_path} 事件回传前端,与旧实现逐字等价。
 */
public final class GetClaudeCliPathActionHandler implements FrontendActionHandler<String> {

    private static final Logger LOG = Logger.getInstance(GetClaudeCliPathActionHandler.class);
    private static final Gson GSON = new Gson();
    private static final String CLAUDE_CLI_PATH_PROPERTY_KEY = "claude.code.cli.path";

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_CLAUDE_CLI_PATH;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        CompletableFuture.runAsync(() -> {
            try {
                String saved = PropertiesComponent.getInstance().getValue(CLAUDE_CLI_PATH_PROPERTY_KEY);
                String pathToSend = (saved != null) ? saved.trim() : "";

                ApplicationManager.getApplication().invokeLater(() -> {
                    JsonObject response = new JsonObject();
                    response.addProperty("path", pathToSend);
                    ctx.dispatchEvent("config.claude_cli_path", ctx.escapeJs(GSON.toJson(response)));
                });
            } catch (Exception e) {
                LOG.error("[GetClaudeCliPathActionHandler] Failed to get Claude CLI path: " + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() ->
                        ctx.dispatchEvent("toast.error", ctx.escapeJs("Failed to load Claude CLI path: " + e.getMessage()))
                );
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[GetClaudeCliPathActionHandler] Unexpected error in handle: " + ex.getMessage(), ex);
            return null;
        });
    }
}
