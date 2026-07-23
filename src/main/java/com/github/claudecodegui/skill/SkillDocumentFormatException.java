package com.github.claudecodegui.skill;

/** Raised when SKILL.md frontmatter cannot be safely parsed or rendered. */
public class SkillDocumentFormatException extends Exception {

    public SkillDocumentFormatException(String message) {
        super(message);
    }

    public SkillDocumentFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
