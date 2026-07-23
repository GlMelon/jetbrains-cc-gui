package com.github.claudecodegui.handler.skill;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Saves a backend-validated editable SKILL.md document. */
public final class SaveSkillDocumentActionHandler
        implements FrontendActionHandler<SaveSkillDocumentRequest> {

    private final SkillActionHandlers handlers;

    public SaveSkillDocumentActionHandler(SkillActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.SAVE_SKILL_DOCUMENT;
    }

    @Override
    public Class<SaveSkillDocumentRequest> payloadType() {
        return SaveSkillDocumentRequest.class;
    }

    @Override
    public void handle(SaveSkillDocumentRequest payload, FrontendActionContext context) {
        handlers.handleSaveSkillDocument(payload);
    }
}
