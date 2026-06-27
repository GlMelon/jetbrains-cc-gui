package com.github.claudecodegui.skill;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Claude provider adapter over the static {@link SkillService}.
 * <p>
 * Claude keys skills by {@code scope} + {@code name} and toggles enabled state
 * via directory move ({@code ~/.claude/skills} ↔ {@code ~/.codemoss/skills}).
 * The SDK auto-discovers skills in those directories, so no runtime injection is
 * needed. {@code cwd} is treated as the workspace root.
 */
public final class ClaudeSkillProvider implements UnifiedSkillService {

    @Override
    public ProviderType provider() {
        return ProviderType.CLAUDE;
    }

    @Override
    public JsonObject getAllSkills(String cwd) {
        return SkillService.getAllSkills(cwd);
    }

    @Override
    public JsonObject importSkills(List<String> sourcePaths, String scope, String cwd) {
        return SkillService.importSkills(sourcePaths, scope, cwd);
    }

    @Override
    public JsonObject deleteSkill(SkillId id, boolean enabled, String cwd) {
        return SkillService.deleteSkill(id.name(), id.scope(), enabled, cwd);
    }

    @Override
    public JsonObject toggleSkill(SkillId id, boolean currentEnabled, String cwd) {
        return SkillService.toggleSkill(id.name(), id.scope(), currentEnabled, cwd);
    }
}
