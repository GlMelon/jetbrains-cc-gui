package com.github.claudecodegui.handler.skill;

import com.github.claudecodegui.skill.SkillDocumentIdentity;

/** Typed payload for loading an editable SKILL.md document. */
public record GetSkillDocumentRequest(
        String requestId,
        String scope,
        String name,
        String directoryPath,
        String skillPath,
        boolean enabled
) {
    SkillDocumentIdentity toIdentity() {
        return new SkillDocumentIdentity(scope, name, directoryPath, skillPath, enabled);
    }
}
