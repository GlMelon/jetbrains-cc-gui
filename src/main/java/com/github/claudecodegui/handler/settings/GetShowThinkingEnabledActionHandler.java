package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.ProjectConfigHandler;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Typed handler for {@link UpstreamAction#GET_SHOW_THINKING_ENABLED} — 显示思考区开关的读取入口。
 * 与 GetStreamingEnabledActionHandler 对称:委托 ProjectConfigHandler 回读 project-specific 配置。
 */
public class GetShowThinkingEnabledActionHandler implements FrontendActionHandler<String> {
    private final ProjectConfigHandler delegate;
    public GetShowThinkingEnabledActionHandler(ProjectConfigHandler delegate) { this.delegate = delegate; }
    @Override public UpstreamAction action() { return UpstreamAction.GET_SHOW_THINKING_ENABLED; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { delegate.handleGetShowThinkingEnabled(); }
}
