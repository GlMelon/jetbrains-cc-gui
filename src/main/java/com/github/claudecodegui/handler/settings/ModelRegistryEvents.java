package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.settings.ModelRegistryResult;
import com.github.claudecodegui.ui.toolwindow.ClaudeChatToolWindow;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;

/**
 * 模型注册表下行事件的装配 + 跨 tab 广播(DRY)。
 *
 * <p>所有 registry 下发(get/set/reload)统一经 {@link ClaudeChatToolWindow#broadcastModelRegistry}
 * 广播到当前项目全部标签——registry 是应用级全局数据,任意 tab 触发的读取/变更都应同步到所有 tab 的
 * 前端 {@code currentRegistry} 单例,解决多标签 webview 单例隔离下的下拉不同步问题。
 */
final class ModelRegistryEvents {
    private ModelRegistryEvents() {
    }

    /** 广播 {@code model_registry_updated}(含 success/registry 或 errors)。 */
    static void dispatchUpdated(HandlerContext ctx, ModelRegistryResult result) {
        JsonObject response = new JsonObject();
        response.addProperty("success", result.success());
        if (result.success() && result.registry() != null) {
            response.add("registry", result.registry());
        }
        if (!result.success()) {
            JsonArray errors = new JsonArray();
            result.errors().forEach(errors::add);
            response.add("errors", errors);
        }
        dispatch(ctx, DownstreamEvent.MODEL_REGISTRY_UPDATED.value(), response.toString());
    }

    /** 广播完整 {@code model_registry} 快照。 */
    static void dispatchRegistry(HandlerContext ctx, ModelRegistryResult result) {
        if (result.registry() == null) {
            return;
        }
        dispatch(ctx, DownstreamEvent.MODEL_REGISTRY.value(), result.registry().toString());
    }

    private static void dispatch(HandlerContext ctx, String type, String payloadJson) {
        Project project = ctx.getProject();
        if (project == null) {
            ctx.dispatchEvent(type, payloadJson);
            return;
        }
        ClaudeChatToolWindow.broadcastModelRegistry(project, type, payloadJson);
    }
}
