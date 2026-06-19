package com.github.claudecodegui.handler.core;

import com.github.claudecodegui.protocol.UpstreamAction;

import java.util.ArrayList;
import java.util.List;

public final class LegacyMessageHandlerAdapter {
    private LegacyMessageHandlerAdapter() {
    }

    public static List<FrontendActionHandler<?>> from(MessageHandler legacyHandler) {
        List<FrontendActionHandler<?>> handlers = new ArrayList<>();
        for (String supportedType : legacyHandler.getSupportedTypes()) {
            UpstreamAction.fromValue(supportedType)
                    .ifPresent(action -> handlers.add(new LegacyActionHandler(action, legacyHandler)));
        }
        return handlers;
    }

    private static final class LegacyActionHandler implements FrontendActionHandler<String> {
        private final UpstreamAction action;
        private final MessageHandler legacyHandler;

        private LegacyActionHandler(UpstreamAction action, MessageHandler legacyHandler) {
            this.action = action;
            this.legacyHandler = legacyHandler;
        }

        @Override
        public UpstreamAction action() {
            return action;
        }

        @Override
        public Class<String> payloadType() {
            return String.class;
        }

        @Override
        public void handle(String payload, FrontendActionContext context) {
            legacyHandler.handle(action.value(), payload);
        }
    }
}
