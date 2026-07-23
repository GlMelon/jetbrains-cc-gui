package com.github.claudecodegui.skill;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.List;

/**
 * Codex provider adapter over the static {@link CodexSkillService}.
 * <p>
 * Codex keys skills by {@code skillPath} (absolute SKILL.md path) and toggles
 * enabled state via {@code ~/.codex/config.toml} {@code [[skills.config]]} entries.
 */
public final class CodexSkillProvider implements UnifiedSkillService {

    @Override
    public ProviderType provider() {
        return ProviderType.CODEX;
    }

    @Override
    public JsonObject getAllSkills(String cwd) {
        return CodexSkillService.getAllSkills(cwd);
    }

    @Override
    public SkillDocumentSchema skillDocumentSchema() {
        return SkillDocumentSchema.agentSkills();
    }

    @Override
    public SkillDocumentTarget resolveSkillDocument(SkillDocumentIdentity identity, String cwd)
            throws SkillDocumentAccessException {
        if (identity.skillPath() == null || identity.skillPath().isBlank()) {
            throw new SkillDocumentAccessException("Codex skillPath is required");
        }
        List<Path> roots = CodexSkillService.getSkillScanDirs(cwd).stream()
                .map(CodexSkillService.SkillScanDir::path)
                .map(Path::of)
                .toList();
        return SkillDocumentPathPolicy.resolve(Path.of(identity.skillPath()), roots);
    }

    @Override
    public JsonObject importSkills(List<String> sourcePaths, String scope, String cwd) {
        return CodexSkillService.importSkill(sourcePaths, scope, cwd);
    }

    @Override
    public JsonObject deleteSkill(SkillId id, boolean enabled, String cwd) {
        return CodexSkillService.deleteSkill(id.name(), id.scope(), id.skillPath(), cwd);
    }

    @Override
    public JsonObject toggleSkill(SkillId id, boolean currentEnabled, String cwd) {
        if (id.skillPath() == null || id.skillPath().trim().isEmpty()) {
            JsonObject result = new JsonObject();
            result.addProperty("success", false);
            result.addProperty("error", "skillPath is required for Codex skill toggle");
            return result;
        }
        return CodexSkillService.toggleSkill(id.skillPath(), currentEnabled, cwd);
    }
}
