package com.github.claudecodegui.handler.core;

import com.github.claudecodegui.protocol.UpstreamAction;

public interface FrontendActionHandler<T> {
    UpstreamAction action();

    Class<T> payloadType();

    void handle(T payload, FrontendActionContext context);
}
