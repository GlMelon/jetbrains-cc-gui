package com.github.claudecodegui.skill;

/** Presentation-neutral control hints for the generic webview skill editor. */
public enum SkillFieldControl {
    TEXT("text"),
    TEXTAREA("textarea"),
    BOOLEAN("boolean"),
    STRING_LIST("string-list");

    private final String value;

    SkillFieldControl(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
