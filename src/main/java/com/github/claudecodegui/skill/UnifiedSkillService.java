package com.github.claudecodegui.skill;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Unified CRUD surface for skills across all providers (Claude / Codex / OpenCode).
 * <p>
 * The truth source of enabled state remains each provider's native mechanism:
 * <ul>
 *   <li>Claude — directory move ({@code ~/.claude/skills} ↔ {@code ~/.codemoss/skills}).</li>
 *   <li>Codex — {@code ~/.codex/config.toml} {@code [[skills.config]]} entries.</li>
 *   <li>OpenCode — {@code opencode.json} {@code permission.skill} patterns.</li>
 * </ul>
 * This interface is the management/view layer the UI and handlers talk to;
 * it does <b>not</b> centralize runtime state. Routed per-provider by
 * {@link UnifiedSkillServiceRegistry} keyed on {@link ProviderType}.
 */
public interface UnifiedSkillService {

    /** The provider this implementation manages. */
    ProviderType provider();

    /** All skills (enabled + disabled) for the given working directory. */
    JsonObject getAllSkills(String cwd);

    /** Imports skill sources into the given scope (provider-specific scope vocabulary). */
    JsonObject importSkills(List<String> sourcePaths, String scope, String cwd);

    /**
     * Deletes a skill identified by {@code id}.
     *
     * @param enabled current enabled state (Claude uses it to pick active vs managed dir)
     */
    JsonObject deleteSkill(SkillId id, boolean enabled, String cwd);

    /**
     * Toggles a skill's enabled state.
     *
     * @param currentEnabled current enabled state (implementation flips to the opposite)
     */
    JsonObject toggleSkill(SkillId id, boolean currentEnabled, String cwd);
}
