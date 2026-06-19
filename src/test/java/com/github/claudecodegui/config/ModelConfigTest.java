package com.github.claudecodegui.config;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModelConfigTest {
    @Test
    public void nineArgConstructorDefaultsReadOnlyFalse() {
        ModelConfig model = new ModelConfig("mimo", "claude", "sonnet", "Mimo",
                "mimo", "", 200_000, true, true);
        assertFalse(model.readOnly());
    }

    @Test
    public void tenArgConstructorPreservesReadOnly() {
        ModelConfig model = new ModelConfig("mimo", "claude", "sonnet", "Mimo",
                "mimo", "", 200_000, true, true, true);
        assertTrue(model.readOnly());
    }

    @Test
    public void normalizedPreservesReadOnlyFlag() {
        ModelConfig model = new ModelConfig("mimo", "claude", "sonnet", "Mimo",
                "mimo", "", 200_000, true, true, true);
        assertTrue(model.normalized().readOnly());
    }
}
