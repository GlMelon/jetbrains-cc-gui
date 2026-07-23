package com.github.claudecodegui.skill;

/** Raised when a requested skill document fails provider or filesystem safety checks. */
public class SkillDocumentAccessException extends Exception {

    public SkillDocumentAccessException(String message) {
        super(message);
    }

    public SkillDocumentAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
