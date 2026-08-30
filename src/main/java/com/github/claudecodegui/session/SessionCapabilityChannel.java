package com.github.claudecodegui.session;

import com.github.claudecodegui.protocol.ProtocolValue;

/** Runtime channel selected for the current provider session. */
public enum SessionCapabilityChannel implements ProtocolValue {
    UNKNOWN("unknown"),
    CLI("cli"),
    KIMI_ACP("kimi_acp"),
    KIMI_LEGACY_STREAM_JSON("kimi_legacy_stream_json");

    private final String value;

    SessionCapabilityChannel(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
