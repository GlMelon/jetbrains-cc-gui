package com.github.claudecodegui.session;

import com.github.claudecodegui.protocol.ProtocolValue;

/** Backend-owned state values used by the session capability snapshot. */
public enum SessionCapabilityState implements ProtocolValue {
    UNKNOWN("unknown"),
    DISCOVERED("discovered"),
    NEGOTIATED("negotiated"),
    DEGRADED("degraded");

    private final String value;

    SessionCapabilityState(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
