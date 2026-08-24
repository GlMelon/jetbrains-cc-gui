package com.github.claudecodegui.handler.dsh;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.concurrent.CompletableFuture;

/**
 * DSH host 生命周期 4 个 typed ActionHandler 容器(get_dsh_status/start_dsh_host/
 * stop_dsh_host/save_dsh_settings),各自异步 spawn channel-manager.js dsh <command>
 * 并经 {@code window.updateDshStatus} 下行结果。spawn 逻辑见 {@link DshHostRunner}。
 * <p>
 * 前端 DSH 连接卡(启停/状态/自动启动开关)发送这 4 个 action,本 handler 返回 host 状态 payload。
 * 连接卡 UI 按本地 settings 结构重做见批次 D 剩余 UI 子项。
 */
public final class DshHostActionHandlers {

    private DshHostActionHandlers() {
    }

    /** 共享下行:把 host 状态 payload 推到 window.updateDshStatus。 */
    private static void pushStatus(FrontendActionContext context, JsonObject payload) {
        context.handlerContext().callJavaScript("updateDshStatus",
                context.handlerContext().escapeJs(GsonHolder.GSON.toJson(payload)));
    }

    private static void runAsync(FrontendActionContext context, java.util.function.Supplier<JsonObject> task) {
        CompletableFuture.runAsync(() -> pushStatus(context, task.get()), AppExecutorUtil.getAppExecutorService());
    }

    public static final class GetDshStatusActionHandler implements FrontendActionHandler<String> {
        @Override public UpstreamAction action() { return UpstreamAction.GET_DSH_STATUS; }
        @Override public Class<String> payloadType() { return String.class; }
        @Override public void handle(String payload, FrontendActionContext context) {
            runAsync(context, DshHostRunner::getStatus);
        }
    }

    public static final class StartDshHostActionHandler implements FrontendActionHandler<String> {
        @Override public UpstreamAction action() { return UpstreamAction.START_DSH_HOST; }
        @Override public Class<String> payloadType() { return String.class; }
        @Override public void handle(String payload, FrontendActionContext context) {
            runAsync(context, DshHostRunner::startHost);
        }
    }

    public static final class StopDshHostActionHandler implements FrontendActionHandler<String> {
        @Override public UpstreamAction action() { return UpstreamAction.STOP_DSH_HOST; }
        @Override public Class<String> payloadType() { return String.class; }
        @Override public void handle(String payload, FrontendActionContext context) {
            runAsync(context, DshHostRunner::stopHost);
        }
    }

    public static final class SaveDshSettingsActionHandler implements FrontendActionHandler<String> {
        @Override public UpstreamAction action() { return UpstreamAction.SAVE_DSH_SETTINGS; }
        @Override public Class<String> payloadType() { return String.class; }
        @Override public void handle(String payload, FrontendActionContext context) {
            runAsync(context, () -> DshHostRunner.saveSettings(payload));
        }
    }
}
