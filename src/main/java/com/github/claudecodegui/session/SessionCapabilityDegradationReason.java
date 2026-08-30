package com.github.claudecodegui.session;

import com.github.claudecodegui.protocol.ProtocolValue;

/** Stable wire values explaining why a session does not expose static capabilities. */
public enum SessionCapabilityDegradationReason implements ProtocolValue {
    VERSION_PROBE_FAILED("version_probe_failed"),
    VERSION_UNSUPPORTED("version_unsupported"),
    ACP_UNAVAILABLE("acp_unavailable"),
    ACP_NEGOTIATION_FAILED("acp_negotiation_failed"),
    ACP_RUNTIME_FAILED("acp_runtime_failed"),
    LEGACY_FALLBACK("legacy_fallback");

    private final String value;

    SessionCapabilityDegradationReason(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
