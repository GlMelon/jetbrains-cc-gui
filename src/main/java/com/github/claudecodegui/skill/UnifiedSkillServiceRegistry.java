package com.github.claudecodegui.skill;

import com.github.claudecodegui.session.runtime.ProviderType;

import java.util.Map;

/**
 * Routes a provider string/enum to its {@link UnifiedSkillService} implementation.
 * <p>
 * The single entry point the skill handlers and UI talk to; selecting an implementation
 * by provider centralizes the Claude/Codex/OpenCode dispatch that was previously scattered
 * as {@code isCodex} branches in {@code SkillActionHandlers}. Unknown or null providers
 * fall back to Claude, mirroring {@link ProviderType#fromString(String)}.
 * <p>
 * Implementations are singletons held in a lookup map (no per-call allocation).
 */
public final class UnifiedSkillServiceRegistry {

    private static final UnifiedSkillService CLAUDE = new ClaudeSkillProvider();
    private static final UnifiedSkillService CODEX = new CodexSkillProvider();
    private static final UnifiedSkillService OPENCODE = new OpenCodeSkillProvider();

    private static final Map<ProviderType, UnifiedSkillService> BY_PROVIDER = Map.of(
            ProviderType.CLAUDE, CLAUDE,
            ProviderType.CODEX, CODEX,
            ProviderType.OPENCODE, OPENCODE);

    private UnifiedSkillServiceRegistry() {
    }

    /** Routes a provider string (tolerant: unknown/null → Claude). */
    public static UnifiedSkillService forProvider(String provider) {
        return BY_PROVIDER.getOrDefault(ProviderType.fromString(provider), CLAUDE);
    }

    /** Routes a {@link ProviderType} (null → Claude). */
    public static UnifiedSkillService forProvider(ProviderType provider) {
        return provider == null ? CLAUDE : BY_PROVIDER.getOrDefault(provider, CLAUDE);
    }
}
