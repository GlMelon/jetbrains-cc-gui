package com.github.claudecodegui.handler.opencode;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

/**
 * §15.8 §11:OpenCode 模型刷新动作共享逻辑容器(对称 Codex {@code CodexMcpServerActionHandlers})。
 * <p>
 * 调 {@code OpenCodeSDKBridge.listModels()}(走 channel {@code opencode listModels} →
 * {@code config.providers()}),结果 {success, models:[{provider,model,...}]} 下发
 * {@link DownstreamEvent#OPENCODE_MODELS_LIST}。前端 UI defer:能力可达即可,
 * 下拉接入由前端后续订阅 OPENCODE_MODELS_LIST 实现(三 provider 对称:OpenCode 模型
 * 静态部分已由 {@code OpenCodeConfigReader} → {@code ReadOnlyDefaultModels} 进 registry)。
 */
public class OpenCodeModelsActionHandlers {

    private static final Logger LOG = Logger.getInstance(OpenCodeModelsActionHandlers.class);

    private final HandlerContext context;

    public OpenCodeModelsActionHandlers(HandlerContext context) {
        this.context = context;
    }

    /**
     * 刷新 OpenCode 可用模型列表(动态 {@code config.providers()}),结果下发 OPENCODE_MODELS_LIST。
     * <p>
     * listModels 内部 channel-manager 对 opencode provider 已 force-exit(释放 SSE/HTTP 连接),
     * 此处仅消费其 CompletableFuture 结果。
     */
    void handleRefreshOpenCodeModels() {
        if (context.getOpenCodeSDKBridge() == null) {
            dispatchError("OpenCode bridge not ready");
            return;
        }
        context.getOpenCodeSDKBridge().listModels()
                .thenAccept(result -> {
                    String json = GsonHolder.GSON.toJson(result);
                    LOG.info("[OpenCodeModels] refreshed models list");
                    ApplicationManager.getApplication().invokeLater(() ->
                            context.dispatchEvent(DownstreamEvent.OPENCODE_MODELS_LIST.value(),
                                    context.escapeJs(json)));
                })
                .exceptionally(e -> {
                    LOG.error("[OpenCodeModels] failed to refresh models: " + e.getMessage(), e);
                    dispatchError(e.getMessage());
                    return null;
                });
    }

    /** 构造失败结果并下发 OPENCODE_MODELS_LIST(保持 success:false 语义与 ai-bridge 一致)。 */
    private void dispatchError(String message) {
        JsonObject err = new JsonObject();
        err.addProperty("success", false);
        err.addProperty("error", message != null ? message : "Unknown error");
        err.add("models", new JsonArray());
        String json = GsonHolder.GSON.toJson(err);
        ApplicationManager.getApplication().invokeLater(() ->
                context.dispatchEvent(DownstreamEvent.OPENCODE_MODELS_LIST.value(), context.escapeJs(json)));
    }
}
