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
 * 处理前端CLI工具安装请求。
 * 通过npm全局安装指定的CLI工具。
 */
public class InstallCliToolActionHandler implements FrontendActionHandler<JsonObject> {

    private static final Logger LOG = Logger.getInstance(InstallCliToolActionHandler.class);

    @Override
    public UpstreamAction action() {
        return UpstreamAction.INSTALL_CLI_TOOL;
    }

    @Override
    public Class<JsonObject> payloadType() {
        return JsonObject.class;
    }

    @Override
    public void handle(JsonObject payload, FrontendActionContext context) {
        String toolId = payload.has("toolId") ? payload.get("toolId").getAsString() : null;
        LOG.info("[CliEnvironment] Handling INSTALL_CLI_TOOL request for tool: " + toolId);

        if (toolId == null || toolId.isEmpty()) {
            JsonObject errorResponse = new JsonObject();
            errorResponse.addProperty("success", false);
            errorResponse.addProperty("error", "Missing toolId parameter");
            context.handlerContext().dispatchEvent(
                DownstreamEvent.CLI_INSTALL_RESULT.value(),
                GsonHolder.GSON.toJson(errorResponse)
            );
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
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
                    GsonHolder.GSON.toJson(response)
                );

                LOG.info("[CliEnvironment] Install completed for tool: " + toolId);
            } catch (Exception e) {
                LOG.error("[CliEnvironment] Failed to install CLI tool: " + toolId, e);

                JsonObject errorResponse = new JsonObject();
                errorResponse.addProperty("toolId", toolId);
                errorResponse.addProperty("success", false);
                errorResponse.addProperty("error", e.getMessage());

                context.handlerContext().dispatchEvent(
                    DownstreamEvent.CLI_INSTALL_RESULT.value(),
                    GsonHolder.GSON.toJson(errorResponse)
                );
            }
        }, AppExecutorUtil.getAppExecutorService());
    }
}
