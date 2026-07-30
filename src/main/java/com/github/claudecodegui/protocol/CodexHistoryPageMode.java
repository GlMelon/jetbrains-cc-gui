package com.github.claudecodegui.protocol;

import java.util.Arrays;
import java.util.Optional;

/** Codex history page merge mode shared with the Webview protocol generator. */
public enum CodexHistoryPageMode implements ProtocolValue {
    REPLACE("replace"),
    PREPEND("prepend");

    private final String value;

    CodexHistoryPageMode(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static Optional<CodexHistoryPageMode> fromValue(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(mode -> mode.value.equals(value)).findFirst();
    }
}
