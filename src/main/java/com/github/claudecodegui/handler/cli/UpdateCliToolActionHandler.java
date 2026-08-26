package com.github.claudecodegui.handler.cli;

import com.github.claudecodegui.cli.CliEnvironmentChecker;
import com.github.claudecodegui.cli.CliEnvironmentStatus;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.concurrent.CompletableFuture;

/**
 * 处理前端CLI工具更新请求(设置页「更新到 vX.Y.Z」按钮)。
 *
 * 更新与安装走同一条 npm 命令(install -g pkg@latest),故复用
 * {@link CliEnvironmentChecker#installCliTool} 与 INSTALL 下行事件
 * {@link DownstreamEvent#CLI_INSTALL_RESULT}:前端(CliEnvironmentSection 卡片 +
 * cliEnvironmentStatus 全局快照)已有该事件的订阅链,更新成功后版本/hasUpdate
 * 自动刷新,无需新增协议。此前该 action 仅有枚举定义无 handler,按钮点击被
 * dispatcher 静默丢弃。
 */
public class UpdateCliToolActionHandler implements FrontendActionHandler<JsonObject> {

    private static final Logger LOG = Logger.getInstance(UpdateCliToolActionHandler.class);

    @Override
    public UpstreamAction action() {
        return UpstreamAction.UPDATE_CLI_TOOL;
    }

    @Override
    public Class<JsonObject> payloadType() {
        return JsonObject.class;
    }

    @Override
    public void handle(JsonObject payload, FrontendActionContext context) {
        String toolId = payload.has("toolId") ? payload.get("toolId").getAsString() : null;
        LOG.info("[CliEnvironment] Handling UPDATE_CLI_TOOL request for tool: " + toolId);

        if (toolId == null || toolId.isEmpty()) {
            JsonObject errorResponse = new JsonObject();
            errorResponse.addProperty("success", false);
            errorResponse.addProperty("error", "Missing toolId parameter");
            context.handlerContext().dispatchEvent(
                DownstreamEvent.CLI_INSTALL_RESULT.value(),
                context.handlerContext().escapeJs(GsonHolder.GSON.toJson(errorResponse))
            );
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                // 更新 = 重新安装 @latest(npm install -g 同一条命令)
                CliEnvironmentChecker checker = new CliEnvironmentChecker();
                CliEnvironmentStatus result = checker.installCliTool(toolId);

                JsonObject response = new JsonObject();
                response.addProperty("toolId", toolId);
                response.addProperty("success", result.getError() == null);
                if (result.getError() != null) {
                    response.addProperty("error", result.getError());
                } else {
                    response.add("status", GsonHolder.GSON.toJsonTree(result));
                }

                context.handlerContext().dispatchEvent(
                    DownstreamEvent.CLI_INSTALL_RESULT.value(),
                    context.handlerContext().escapeJs(GsonHolder.GSON.toJson(response))
                );

                LOG.info("[CliEnvironment] Update completed for tool: " + toolId);
            } catch (Exception e) {
                LOG.error("[CliEnvironment] Failed to update CLI tool: " + toolId, e);

                JsonObject errorResponse = new JsonObject();
                errorResponse.addProperty("toolId", toolId);
                errorResponse.addProperty("success", false);
                errorResponse.addProperty("error", e.getMessage());

                context.handlerContext().dispatchEvent(
                    DownstreamEvent.CLI_INSTALL_RESULT.value(),
                    context.handlerContext().escapeJs(GsonHolder.GSON.toJson(errorResponse))
                );
            }
        }, AppExecutorUtil.getAppExecutorService());
    }
}
