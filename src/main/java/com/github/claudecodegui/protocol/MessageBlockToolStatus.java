package com.github.claudecodegui.protocol;

/** Backend-owned lifecycle state for tool_use/tool_result message blocks. */
public enum MessageBlockToolStatus implements ProtocolValue {
    PENDING("pending"),
    COMPLETED("completed"),
    UNPAIRED("unpaired"),
    ORPHANED("orphaned"),
    DUPLICATE("duplicate");

    private final String value;

    MessageBlockToolStatus(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
