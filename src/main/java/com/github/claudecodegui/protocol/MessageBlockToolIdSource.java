package com.github.claudecodegui.protocol;

/** Backend-owned source classification for normalized tool call identities. */
public enum MessageBlockToolIdSource implements ProtocolValue {
    EXPLICIT("explicit"),
    GENERATED("generated");

    private final String value;

    MessageBlockToolIdSource(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
