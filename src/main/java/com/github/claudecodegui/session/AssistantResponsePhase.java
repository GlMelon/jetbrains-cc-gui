package com.github.claudecodegui.session;

/**
 * Backend-owned response phase values for assistant turn status updates.
 */
public enum AssistantResponsePhase {
    QUEUED(
            "queued",
            "assistant.response.phase.queued.title",
            "assistant.response.phase.queued.description",
            true
    ),
    CONNECTING(
            "connecting",
            "assistant.response.phase.connecting.title",
            "assistant.response.phase.connecting.description",
            true
    ),
    UNDERSTANDING(
            "understanding",
            "assistant.response.phase.understanding.title",
            "assistant.response.phase.understanding.description",
            true
    ),
    THINKING(
            "thinking",
            "assistant.response.phase.thinking.title",
            "assistant.response.phase.thinking.description",
            true
    ),
    TOOLING(
            "tooling",
            "assistant.response.phase.tooling.title",
            "assistant.response.phase.tooling.description",
            true
    ),
    RESPONDING(
            "responding",
            "assistant.response.phase.responding.title",
            "assistant.response.phase.responding.description",
            true
    ),
    DONE(
            "done",
            "assistant.response.phase.done.title",
            "assistant.response.phase.done.description",
            false
    ),
    ERROR(
            "error",
            "assistant.response.phase.error.title",
            "assistant.response.phase.error.description",
            false
    );

    private final String value;
    private final String titleKey;
    private final String descriptionKey;
    private final boolean active;

    AssistantResponsePhase(String value, String titleKey, String descriptionKey, boolean active) {
        this.value = value;
        this.titleKey = titleKey;
        this.descriptionKey = descriptionKey;
        this.active = active;
    }

    public String value() {
        return value;
    }

    public String titleKey() {
        return titleKey;
    }

    public String descriptionKey() {
        return descriptionKey;
    }

    public boolean active() {
        return active;
    }
}
