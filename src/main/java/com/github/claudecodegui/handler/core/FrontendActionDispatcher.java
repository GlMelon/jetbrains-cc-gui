package com.github.claudecodegui.handler.core;

import com.github.claudecodegui.util.GsonHolder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FrontendActionDispatcher {
    private final Map<String, FrontendActionHandler<?>> handlers;
    private final FrontendActionContext context;

    public FrontendActionDispatcher(List<FrontendActionHandler<?>> handlers, HandlerContext handlerContext) {
        this.handlers = new LinkedHashMap<>();
        this.context = new FrontendActionContext(handlerContext);
        for (FrontendActionHandler<?> handler : handlers) {
            String action = handler.action().value();
            if (this.handlers.putIfAbsent(action, handler) != null) {
                throw new IllegalArgumentException("Duplicate frontend action handler: " + action);
            }
        }
    }

    public boolean dispatch(String type, String content) {
        FrontendActionHandler<?> handler = handlers.get(type);
        if (handler == null) {
            return false;
        }
        dispatchTyped(handler, content);
        return true;
    }

    /**
     * 查询某 action 是否已注册进路由表(供装配自检与运行时诊断使用)。
     */
    public boolean isRegistered(String action) {
        return handlers.containsKey(action);
    }

    /**
     * 装配自检:断言 {@code handlers} 中每个 handler 的 action 都已注册进 {@code dispatcher}。
     *
     * <p>防止"dispatcher 在 list 未填满时构造"的装配时机回归 —— B2/B4 迁移曾因此把
     * permission/history handler 漏在路由表外,前端发出的 action 落到 unknown-type 分支被
     * 静默丢弃。在所有 typedHandlers.add 完成后调用本方法即可结构性兜底。
     */
    public static void verifyAllRegistered(FrontendActionDispatcher dispatcher,
                                           List<FrontendActionHandler<?>> handlers) {
        for (FrontendActionHandler<?> handler : handlers) {
            String action = handler.action().value();
            if (!dispatcher.isRegistered(action)) {
                throw new IllegalStateException(
                        "Typed handler not registered in dispatcher (assembly-order bug): " + action);
            }
        }
    }

    private <T> void dispatchTyped(FrontendActionHandler<T> handler, String content) {
        T payload = parsePayload(content, handler.payloadType());
        handler.handle(payload, context);
    }

    private <T> T parsePayload(String content, Class<T> payloadType) {
        if (String.class.equals(payloadType)) {
            return payloadType.cast(content);
        }
        return GsonHolder.GSON.fromJson(content, payloadType);
    }
}
