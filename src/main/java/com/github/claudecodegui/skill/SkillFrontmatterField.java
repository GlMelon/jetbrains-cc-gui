package com.github.claudecodegui.skill;

/**
 * Single source of truth for editable SKILL.md frontmatter fields.
 */
public enum SkillFrontmatterField {
    NAME("name", "skills.name", SkillFieldControl.TEXT, true, 64),
    DESCRIPTION("description", "skills.description", SkillFieldControl.TEXTAREA, true, 1024),
    LICENSE("license", "skills.license", SkillFieldControl.TEXT, false, 1024),
    COMPATIBILITY("compatibility", "skills.compatibility", SkillFieldControl.TEXTAREA, false, 4096),
    ALLOWED_TOOLS("allowed-tools", "skills.allowedTools", SkillFieldControl.TEXTAREA, false, 4096),
    USER_INVOCABLE("user-invocable", "skills.userInvocable", SkillFieldControl.BOOLEAN, false, 0),
    PATHS("paths", "skills.paths", SkillFieldControl.STRING_LIST, false, 1024);

    private final String key;
    private final String labelKey;
    private final SkillFieldControl control;
    private final boolean required;
    private final int maxLength;

    SkillFrontmatterField(String key, String labelKey, SkillFieldControl control,
                          boolean required, int maxLength) {
        this.key = key;
        this.labelKey = labelKey;
        this.control = control;
        this.required = required;
        this.maxLength = maxLength;
    }

    public String key() {
        return key;
    }

    public String labelKey() {
        return labelKey;
    }

    public SkillFieldControl control() {
        return control;
    }

    public boolean required() {
        return required;
    }

    public int maxLength() {
        return maxLength;
    }
}
