package com.github.claudecodegui.handler.skill;

import com.github.claudecodegui.skill.SkillDocumentIdentity;
import com.google.gson.JsonObject;

/** Typed payload for saving an editable SKILL.md document. */
public record SaveSkillDocumentRequest(
        String requestId,
        String scope,
        String name,
        String directoryPath,
        String skillPath,
        boolean enabled,
        String revision,
        JsonObject changes,
        String body
) {
    SkillDocumentIdentity toIdentity() {
        return new SkillDocumentIdentity(scope, name, directoryPath, skillPath, enabled);
    }
}
