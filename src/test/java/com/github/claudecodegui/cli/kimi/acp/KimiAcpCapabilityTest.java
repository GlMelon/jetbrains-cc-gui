package com.github.claudecodegui.cli.kimi.acp;

import com.github.claudecodegui.cli.kimi.KimiRunOnceCliSession;
import com.github.claudecodegui.session.SessionCapabilityChannel;
import com.github.claudecodegui.session.SessionCapabilityDegradationReason;
import com.github.claudecodegui.session.SessionCapabilityState;
import com.github.claudecodegui.session.SessionNegotiatedCapabilities;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Runtime capability contract for the ACP and legacy Kimi channels. */
public class KimiAcpCapabilityTest {

    @Test
    public void newAcpSessionStartsUnknownUntilHandshakeCompletes() {
        SessionNegotiatedCapabilities capabilities = new KimiAcpCliSession("tab", null).capabilities();

        assertEquals(SessionCapabilityState.UNKNOWN, capabilities.state());
        assertEquals(SessionCapabilityChannel.UNKNOWN, capabilities.channel());
        assertNull(capabilities.thinkingAvailable());
        assertNull(capabilities.toolsAvailable());
        assertNull(capabilities.mcpAvailable());
    }

    @Test
    public void negotiatedAcpCapabilitiesReflectThinkingCatalog() {
        SessionNegotiatedCapabilities capabilities = KimiAcpCliSession.negotiatedCapabilities(
                new KimiAcpCliSession.ThinkingOptions(List.of("off", "low", "high"), "off"));

        assertEquals(SessionCapabilityState.NEGOTIATED, capabilities.state());
        assertEquals(SessionCapabilityChannel.KIMI_ACP, capabilities.channel());
        assertTrue(capabilities.thinkingAvailable());
        assertTrue(capabilities.toolsAvailable());
        assertFalse(capabilities.mcpAvailable());
        assertFalse(capabilities.degraded());
        assertNull(capabilities.degradationReason());
    }

    @Test
    public void negotiatedAcpCapabilitiesDisableThinkingWhenCatalogOnlyHasOff() {
        SessionNegotiatedCapabilities capabilities = KimiAcpCliSession.negotiatedCapabilities(
                new KimiAcpCliSession.ThinkingOptions(List.of("off"), "off"));

        assertEquals(SessionCapabilityState.NEGOTIATED, capabilities.state());
        assertFalse(capabilities.thinkingAvailable());
        assertTrue(capabilities.toolsAvailable());
    }

    @Test
    public void acpNegotiationFailureIsConservative() {
        SessionNegotiatedCapabilities capabilities = KimiAcpCliSession.degradedCapabilities(
                SessionCapabilityDegradationReason.ACP_NEGOTIATION_FAILED);

        assertEquals(SessionCapabilityState.DEGRADED, capabilities.state());
        assertEquals(SessionCapabilityChannel.KIMI_ACP, capabilities.channel());
        assertFalse(capabilities.thinkingAvailable());
        assertFalse(capabilities.toolsAvailable());
        assertFalse(capabilities.mcpAvailable());
        assertTrue(capabilities.degraded());
        assertEquals(SessionCapabilityDegradationReason.ACP_NEGOTIATION_FAILED,
                capabilities.degradationReason());
    }

    @Test
    public void acpRuntimeFailureIsConservative() {
        SessionNegotiatedCapabilities capabilities = KimiAcpCliSession.degradedCapabilities(
                SessionCapabilityDegradationReason.ACP_RUNTIME_FAILED);

        assertEquals(SessionCapabilityState.DEGRADED, capabilities.state());
        assertFalse(capabilities.thinkingAvailable());
        assertFalse(capabilities.toolsAvailable());
        assertEquals(SessionCapabilityDegradationReason.ACP_RUNTIME_FAILED,
                capabilities.degradationReason());
    }

    @Test
    public void legacyFallbackExposesDegradedLegacyChannel() {
        SessionNegotiatedCapabilities unsupported = new KimiRunOnceCliSession(
                "tab", null, SessionCapabilityDegradationReason.VERSION_UNSUPPORTED).capabilities();
        SessionNegotiatedCapabilities probeFailed = new KimiRunOnceCliSession(
                "tab", null, SessionCapabilityDegradationReason.VERSION_PROBE_FAILED).capabilities();

        assertEquals(SessionCapabilityState.DEGRADED, unsupported.state());
        assertEquals(SessionCapabilityChannel.KIMI_LEGACY_STREAM_JSON, unsupported.channel());
        assertEquals(SessionCapabilityDegradationReason.VERSION_UNSUPPORTED,
                unsupported.degradationReason());
        assertFalse(unsupported.thinkingAvailable());
        assertTrue(unsupported.toolsAvailable());

        assertEquals(SessionCapabilityDegradationReason.VERSION_PROBE_FAILED,
                probeFailed.degradationReason());
    }
}
