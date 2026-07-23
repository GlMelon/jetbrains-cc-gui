package com.github.claudecodegui.skill;

/** Stable backend-validated identity submitted by the webview skill editor. */
public record SkillDocumentIdentity(
        String scope,
        String name,
        String directoryPath,
        String skillPath,
        boolean enabled
) {
}
