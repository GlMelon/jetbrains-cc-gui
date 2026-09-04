package com.github.claudecodegui.skill;

import com.github.claudecodegui.session.runtime.ProviderType;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SlashCommandKimiRoutingTest {

    @Test
    public void kimiCommandsUseKimiBuiltinsAndAgentSkillDirs() throws IOException {
        Path root = Files.createTempDirectory("slash-command-kimi-routing");
        Path home = Files.createDirectories(root.resolve("home"));
        Path cwd = Files.createDirectories(root.resolve("workspace"));

        writeSkill(home.resolve(".agents").resolve("skills").resolve("user-skill"),
                "user-skill", "User scope skill");
        writeSkill(cwd.resolve(".agents").resolve("skills").resolve("repo-skill"),
                "repo-skill", "Repo scope skill");

        List<SlashCommandRegistry.SlashCommand> commands = SlashCommandRegistry.getCommands(
                ProviderType.KIMI.value(), cwd.toString(), null, home.toString());
        Map<String, SlashCommandRegistry.SlashCommand> byName = commands.stream()
                .collect(Collectors.toMap(SlashCommandRegistry.SlashCommand::name, Function.identity()));

        // Kimi bundled skills are present
        assertTrue(byName.containsKey("/check-kimi-code-docs"));
        assertTrue(byName.containsKey("/update-config"));
        assertTrue(byName.containsKey("/write-goal"));
        assertEquals("bundled", byName.get("/check-kimi-code-docs").source());

        // Claude-ecosystem builtins and directories are not scanned for kimi
        assertFalse(byName.containsKey("/batch"));
        assertFalse(byName.containsKey("/claude-api"));

        // .agents/skills dirs are scanned with their scopes
        SlashCommandRegistry.SlashCommand userSkill = byName.get("/user-skill");
        assertNotNull(userSkill);
        assertEquals("user", userSkill.scope());
        assertEquals("User scope skill", userSkill.description());
        SlashCommandRegistry.SlashCommand repoSkill = byName.get("/repo-skill");
        assertNotNull(repoSkill);
        assertEquals("repo", repoSkill.scope());
        assertEquals("Repo scope skill", repoSkill.description());
    }

    private void writeSkill(Path skillDir, String name, String description) throws IOException {
        Files.createDirectories(skillDir);
        Files.writeString(
                skillDir.resolve("SKILL.md"),
                """
                ---
                name: %s
                description: %s
                ---

                Skill body.
                """.formatted(name, description)
        );
    }
}
