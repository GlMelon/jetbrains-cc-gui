package com.github.claudecodegui.skill;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies provider-specific guard logic that the unified handler layer delegates down to
 * {@link CodexSkillProvider}. Codex toggles via config.toml keyed on the absolute SKILL.md
 * path, so a missing {@code skillPath} is a hard error returned (not thrown) to the caller.
 */
public class CodexSkillProviderTest {

    @Test
    public void toggleSkillReturnsErrorWhenSkillPathMissing() {
        CodexSkillProvider provider = new CodexSkillProvider();
        SkillId id = new SkillId("repo", "foo", null);

        JsonObject result = provider.toggleSkill(id, true, "/tmp/fake-cwd");

        assertFalse("must not succeed without skillPath", result.get("success").getAsBoolean());
        assertTrue("error must mention skillPath",
                result.get("error").getAsString().toLowerCase().contains("skillpath"));
    }

    @Test
    public void toggleSkillReturnsErrorWhenSkillPathBlank() {
        CodexSkillProvider provider = new CodexSkillProvider();
        SkillId id = new SkillId("repo", "foo", "  ");

        JsonObject result = provider.toggleSkill(id, false, "/tmp/fake-cwd");

        assertFalse(result.get("success").getAsBoolean());
    }
}
