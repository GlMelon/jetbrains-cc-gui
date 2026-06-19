package com.github.claudecodegui.protocol;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ProtocolEnumCoverageTest {

    @Test
    public void upstreamContainsActionsUsedBySettingsAndFrontend() {
        assertEquals("set_appearance_config", UpstreamAction.SET_APPEARANCE_CONFIG.value());
        assertEquals("get_model_registry", UpstreamAction.GET_MODEL_REGISTRY.value());
        assertEquals("set_model_registry", UpstreamAction.SET_MODEL_REGISTRY.value());
        assertEquals("reset_model_registry", UpstreamAction.RESET_MODEL_REGISTRY.value());
        assertEquals("get_model_registry_schema", UpstreamAction.GET_MODEL_REGISTRY_SCHEMA.value());
    }

    @Test
    public void downstreamContainsEventsUsedBySettingsAndFrontend() {
        assertEquals("appearance.apply", DownstreamEvent.APPEARANCE_APPLY.value());
        assertEquals("model_registry", DownstreamEvent.MODEL_REGISTRY.value());
        assertEquals("model_registry_updated", DownstreamEvent.MODEL_REGISTRY_UPDATED.value());
        assertEquals("model_registry_schema", DownstreamEvent.MODEL_REGISTRY_SCHEMA.value());
    }

    @Test
    public void protocolValuesAreUniqueWithinEachDirection() {
        assertUnique(Arrays.stream(UpstreamAction.values()).map(UpstreamAction::value).collect(Collectors.toList()));
        assertUnique(Arrays.stream(DownstreamEvent.values()).map(DownstreamEvent::value).collect(Collectors.toList()));
    }

    private static void assertUnique(List<String> values) {
        assertTrue("duplicate values: " + values, new HashSet<>(values).size() == values.size());
    }
}
