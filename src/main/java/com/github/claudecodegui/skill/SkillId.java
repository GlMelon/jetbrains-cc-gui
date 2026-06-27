package com.github.claudecodegui.skill;

/**
 * Provider-agnostic identity of a skill across the unified skill service.
 * <p>
 * Different providers key skills differently:
 * <ul>
 *   <li>Claude: by {@code scope} + {@code name} (file-move enable/disable).</li>
 *   <li>Codex: by {@code skillPath} (absolute SKILL.md path; config.toml toggle).</li>
 *   <li>OpenCode: by {@code name} (opencode.json permission pattern).</li>
 * </ul>
 * A single carrier type lets {@link UnifiedSkillService} expose one CRUD surface
 * while each implementation reads whichever field(s) it needs; unused fields are null.
 */
public record SkillId(String scope, String name, String skillPath) {
}
