package com.github.claudecodegui.skill;

import com.github.claudecodegui.session.runtime.ProviderType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * Verifies {@link UnifiedSkillServiceRegistry} routes each provider string to the correct
 * {@link UnifiedSkillService} implementation, falling back to Claude for unknown/null values
 * (consistent with {@link ProviderType#fromString(String)}).
 */
public class UnifiedSkillServiceRegistryTest {

    @Test
    public void routesClaudeProvider() {
        assertEquals(ProviderType.CLAUDE, UnifiedSkillServiceRegistry.forProvider("claude").provider());
    }

    @Test
    public void routesCodexProvider() {
        assertEquals(ProviderType.CODEX, UnifiedSkillServiceRegistry.forProvider("codex").provider());
    }

    @Test
    public void routesOpenCodeProvider() {
        assertEquals(ProviderType.OPENCODE, UnifiedSkillServiceRegistry.forProvider("opencode").provider());
    }

    @Test
    public void unknownProviderFallsBackToClaude() {
        assertEquals(ProviderType.CLAUDE, UnifiedSkillServiceRegistry.forProvider("zzz-unknown").provider());
    }

    @Test
    public void nullProviderFallsBackToClaude() {
        assertEquals(ProviderType.CLAUDE, UnifiedSkillServiceRegistry.forProvider((String) null).provider());
    }

    @Test
    public void returnsSingletonInstances() {
        // Same provider string yields the same instance (no per-call allocation).
        assertSame(UnifiedSkillServiceRegistry.forProvider("claude"),
                UnifiedSkillServiceRegistry.forProvider("claude"));
        assertSame(UnifiedSkillServiceRegistry.forProvider("opencode"),
                UnifiedSkillServiceRegistry.forProvider("opencode"));
    }
}
