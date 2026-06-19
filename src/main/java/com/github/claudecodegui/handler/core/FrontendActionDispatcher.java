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
