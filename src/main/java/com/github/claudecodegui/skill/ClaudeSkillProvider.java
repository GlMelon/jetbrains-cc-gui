package com.github.claudecodegui.skill;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.List;

/**
 * Claude provider adapter over the static {@link SkillService}.
 * <p>
 * Claude keys skills by {@code scope} + {@code name} and toggles enabled state
 * via directory move ({@code ~/.claude/skills} ↔ {@code ~/.codemoss/skills}).
 * The Claude CLI auto-discovers skills in those directories, so no runtime injection is
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
    public SkillDocumentSchema skillDocumentSchema() {
        return SkillDocumentSchema.full();
    }

    @Override
    public SkillDocumentTarget resolveSkillDocument(SkillDocumentIdentity identity, String cwd)
            throws SkillDocumentAccessException {
        SkillScopeType scope = SkillScopeType.fromValue(identity.scope())
                .orElseThrow(() -> new SkillDocumentAccessException("Invalid Claude skill scope"));
        String rootValue;
        if (scope == SkillScopeType.GLOBAL) {
            rootValue = identity.enabled()
                    ? SkillService.getGlobalSkillsDir()
                    : SkillService.getGlobalManagementDir();
        } else if (scope == SkillScopeType.LOCAL) {
            rootValue = identity.enabled()
                    ? SkillService.getLocalSkillsDir(cwd)
                    : SkillService.getLocalManagementDir(cwd);
        } else {
            throw new SkillDocumentAccessException("Invalid Claude skill scope");
        }
        if (rootValue == null) {
            throw new SkillDocumentAccessException("Working directory is required for local skills");
        }
        Path root = Path.of(rootValue);
        Path requested = identity.directoryPath() == null || identity.directoryPath().isBlank()
                ? root.resolve(identity.name()) : Path.of(identity.directoryPath());
        return SkillDocumentPathPolicy.resolve(requested, List.of(root));
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
