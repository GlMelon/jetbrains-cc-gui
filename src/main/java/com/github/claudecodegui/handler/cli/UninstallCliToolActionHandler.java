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
 * 处理前端CLI工具卸载请求(设置页「卸载」按钮)。
 * <p>
 * 卸载走 {@link CliEnvironmentChecker#uninstallCliTool}(npm uninstall -g),
 * 复用 INSTALL 下行事件 {@link DownstreamEvent#CLI_INSTALL_RESULT}(对称
 * UpdateCliToolActionHandler 先例):前端(CliEnvironmentSection 卡片 +
 * cliEnvironmentStatus 全局快照)已有该事件的订阅链,卸载成功后 installed=false
 * 状态自动合并,卡片转「未安装」、供应商下拉门控同步生效,无需新增协议。
 */
public class UninstallCliToolActionHandler implements FrontendActionHandler<JsonObject> {

    private static final Logger LOG = Logger.getInstance(UninstallCliToolActionHandler.class);

    @Override
    public UpstreamAction action() {
        return UpstreamAction.UNINSTALL_CLI_TOOL;
    }

    @Override
    public Class<JsonObject> payloadType() {
        return JsonObject.class;
    }

    @Override
    public void handle(JsonObject payload, FrontendActionContext context) {
        String toolId = payload.has("toolId") ? payload.get("toolId").getAsString() : null;
        LOG.info("[CliEnvironment] Handling UNINSTALL_CLI_TOOL request for tool: " + toolId);

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
                CliEnvironmentStatus result = checker.uninstallCliTool(toolId);

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

                LOG.info("[CliEnvironment] Uninstall completed for tool: " + toolId);
            } catch (Exception e) {
                LOG.error("[CliEnvironment] Failed to uninstall CLI tool: " + toolId, e);

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
