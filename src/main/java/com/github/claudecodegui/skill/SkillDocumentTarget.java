package com.github.claudecodegui.skill;

import java.nio.file.Path;

/** Validated SKILL.md file and the provider-owned root that contains it. */
public record SkillDocumentTarget(Path file, Path root) {
}
