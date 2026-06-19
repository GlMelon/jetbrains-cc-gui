package com.github.claudecodegui.handler.core;

public class FrontendActionContext {
    private final HandlerContext handlerContext;

    public FrontendActionContext(HandlerContext handlerContext) {
        this.handlerContext = handlerContext;
    }

    public HandlerContext handlerContext() {
        return handlerContext;
    }
}
