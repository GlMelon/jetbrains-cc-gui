package com.github.claudecodegui.skill;

import java.util.List;
import java.util.Set;

/** Provider-declared editable frontmatter schema. */
public record SkillDocumentSchema(List<SkillFrontmatterField> fields) {

    private static final SkillDocumentSchema FULL = new SkillDocumentSchema(List.of(
            SkillFrontmatterField.NAME,
            SkillFrontmatterField.DESCRIPTION,
            SkillFrontmatterField.LICENSE,
            SkillFrontmatterField.COMPATIBILITY,
            SkillFrontmatterField.ALLOWED_TOOLS,
            SkillFrontmatterField.USER_INVOCABLE,
            SkillFrontmatterField.PATHS));

    private static final SkillDocumentSchema AGENT_SKILLS = new SkillDocumentSchema(List.of(
            SkillFrontmatterField.NAME,
            SkillFrontmatterField.DESCRIPTION,
            SkillFrontmatterField.LICENSE,
            SkillFrontmatterField.COMPATIBILITY,
            SkillFrontmatterField.ALLOWED_TOOLS));

    public SkillDocumentSchema {
        fields = List.copyOf(fields);
    }

    public static SkillDocumentSchema full() {
        return FULL;
    }

    public static SkillDocumentSchema agentSkills() {
        return AGENT_SKILLS;
    }

    public Set<String> keys() {
        return fields.stream().map(SkillFrontmatterField::key).collect(java.util.stream.Collectors.toSet());
    }
}
