package com.github.claudecodegui.cli.compatibility;

import com.github.claudecodegui.session.runtime.ProviderType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public class CliVersionParserRegistryTest {

    private final CliVersionParserRegistry registry = CliVersionParserRegistry.defaults();

    @Test
    public void parsesProviderSpecificAndNonSemverOutputs() {
        assertEquals("2.1.88", registry.parse(ProviderType.CLAUDE, "Claude Code v2.1.88 (stable)").orElse(null));
        assertEquals("0.117.0-beta.2",
                registry.parse(ProviderType.CODEX, "codex-cli 0.117.0-beta.2").orElse(null));
        assertEquals("1.17.11", registry.parse(ProviderType.OPENCODE, "OpenCode version: 1.17.11").orElse(null));
    }

    @Test
    public void acceptsNumericFallbackWhenProviderLabelChanges() {
        assertEquals("2026.7.22+build.4", registry.parse(ProviderType.CLAUDE, "release 2026.7.22+build.4").orElse(null));
    }

    @Test
    public void returnsEmptyForUnknownOutput() {
        assertFalse(registry.parse(ProviderType.CODEX, "development snapshot").isPresent());
        assertFalse(registry.parse(ProviderType.OPENCODE, null).isPresent());
    }

    @Test
    public void rejectsDuplicateAndMissingParsers() {
        assertThrows(IllegalArgumentException.class,
                () -> new CliVersionParserRegistry(java.util.Arrays.asList(
                        new ClaudeCliVersionParser(), new ClaudeCliVersionParser())));
    }
}
