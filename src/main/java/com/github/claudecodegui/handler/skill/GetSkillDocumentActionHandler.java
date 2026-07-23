package com.github.claudecodegui.handler.skill;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Loads a backend-validated editable SKILL.md document. */
public final class GetSkillDocumentActionHandler
        implements FrontendActionHandler<GetSkillDocumentRequest> {

    private final SkillActionHandlers handlers;

    public GetSkillDocumentActionHandler(SkillActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_SKILL_DOCUMENT;
    }

    @Override
    public Class<GetSkillDocumentRequest> payloadType() {
        return GetSkillDocumentRequest.class;
    }

    @Override
    public void handle(GetSkillDocumentRequest payload, FrontendActionContext context) {
        handlers.handleGetSkillDocument(payload);
    }
}
