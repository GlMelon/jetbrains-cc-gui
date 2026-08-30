package com.github.claudecodegui.session;

import com.github.claudecodegui.protocol.payload.SessionCapabilitiesPayloadField;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * Immutable capabilities negotiated by one concrete CLI session.
 *
 * <p>Provider descriptors remain static declarations. This value is the runtime
 * truth for the currently selected channel and is intentionally conservative when
 * a channel has not been created or has fallen back.</p>
 */
public record SessionNegotiatedCapabilities(
        SessionCapabilityState state,
        SessionCapabilityChannel channel,
        Boolean thinkingAvailable,
        Boolean toolsAvailable,
        Boolean mcpAvailable,
        boolean degraded,
        SessionCapabilityDegradationReason degradationReason
) {
    public SessionNegotiatedCapabilities {
        state = state == null ? SessionCapabilityState.UNKNOWN : state;
        channel = channel == null ? SessionCapabilityChannel.UNKNOWN : channel;
        boolean unknown = state == SessionCapabilityState.UNKNOWN || state == SessionCapabilityState.DISCOVERED;
        thinkingAvailable = normalize(thinkingAvailable, unknown);
        toolsAvailable = normalize(toolsAvailable, unknown);
        mcpAvailable = normalize(mcpAvailable, unknown);
    }

    public static SessionNegotiatedCapabilities unknown() {
        return new SessionNegotiatedCapabilities(
                SessionCapabilityState.UNKNOWN,
                SessionCapabilityChannel.UNKNOWN,
                null,
                null,
                null,
                false,
                null
        );
    }

    public static SessionNegotiatedCapabilities cli(boolean thinkingAvailable, boolean toolsAvailable,
                                                     boolean mcpAvailable) {
        return new SessionNegotiatedCapabilities(
                SessionCapabilityState.NEGOTIATED,
                SessionCapabilityChannel.CLI,
                thinkingAvailable,
                toolsAvailable,
                mcpAvailable,
                false,
                null
        );
    }

    public static SessionNegotiatedCapabilities kimiAcp(boolean thinkingAvailable, boolean mcpAvailable) {
        return new SessionNegotiatedCapabilities(
                SessionCapabilityState.NEGOTIATED,
                SessionCapabilityChannel.KIMI_ACP,
                thinkingAvailable,
                true,
                mcpAvailable,
                false,
                null
        );
    }

    public static SessionNegotiatedCapabilities kimiLegacy(
            SessionCapabilityDegradationReason reason,
            boolean mcpAvailable
    ) {
        return new SessionNegotiatedCapabilities(
                SessionCapabilityState.DEGRADED,
                SessionCapabilityChannel.KIMI_LEGACY_STREAM_JSON,
                false,
                true,
                mcpAvailable,
                true,
                reason == null ? SessionCapabilityDegradationReason.LEGACY_FALLBACK : reason
        );
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty(SessionCapabilitiesPayloadField.STATE.wireKey(), state.value());
        json.addProperty(SessionCapabilitiesPayloadField.CHANNEL.wireKey(), channel.value());
        addNullableBoolean(json, SessionCapabilitiesPayloadField.THINKING_AVAILABLE.wireKey(), thinkingAvailable);
        addNullableBoolean(json, SessionCapabilitiesPayloadField.TOOLS_AVAILABLE.wireKey(), toolsAvailable);
        addNullableBoolean(json, SessionCapabilitiesPayloadField.SESSION_MCP_AVAILABLE.wireKey(), mcpAvailable);
        json.addProperty(SessionCapabilitiesPayloadField.DEGRADED.wireKey(), degraded);
        if (degradationReason == null) {
            json.add(SessionCapabilitiesPayloadField.DEGRADATION_REASON.wireKey(), JsonNull.INSTANCE);
        } else {
            json.addProperty(SessionCapabilitiesPayloadField.DEGRADATION_REASON.wireKey(), degradationReason.value());
        }
        return json;
    }

    private static Boolean normalize(Boolean value, boolean unknown) {
        return unknown ? null : value != null && value;
    }

    private static void addNullableBoolean(JsonObject json, String key, Boolean value) {
        if (value == null) {
            json.add(key, JsonNull.INSTANCE);
        } else {
            json.addProperty(key, value);
        }
    }
}
