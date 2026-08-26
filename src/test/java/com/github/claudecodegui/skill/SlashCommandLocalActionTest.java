package com.github.claudecodegui.skill;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.skill.SlashCommandRegistry.SlashCommand;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Tests for the local slash command metadata (localAction) annotated by
 * {@link SlashCommandRegistry} — the SSOT for which commands the plugin handles
 * locally instead of forwarding to the CLI.
 */
public class SlashCommandLocalActionTest {

    private static SlashCommand find(List<SlashCommand> commands, String name) {
        return commands.stream().filter(c -> c.name().equals(name)).findFirst().orElse(null);
    }

    @Test
    public void claudeCommandsAnnotateAllLocalActions() {
        List<SlashCommand> commands = SlashCommandRegistry.getCommands(CommonConstants.PROVIDER_CLAUDE, null);

        assertEquals(LocalSlashAction.NEW_SESSION.value(), find(commands, "/clear").localAction());
        assertEquals(LocalSlashAction.NEW_SESSION.value(), find(commands, "/new").localAction());
        assertEquals(LocalSlashAction.NEW_SESSION.value(), find(commands, "/reset").localAction());
        assertEquals(LocalSlashAction.OPEN_HISTORY.value(), find(commands, "/resume").localAction());
        assertEquals(LocalSlashAction.OPEN_HISTORY.value(), find(commands, "/continue").localAction());
        assertEquals(LocalSlashAction.PLAN_MODE.value(), find(commands, "/plan").localAction());
        assertEquals(LocalSlashAction.CONTEXT_USAGE.value(), find(commands, "/context").localAction());
        assertEquals(LocalSlashAction.MODEL_PICKER.value(), find(commands, "/model").localAction());
        assertEquals(LocalSlashAction.HELP.value(), find(commands, "/help").localAction());

        // Pass-through commands stay unannotated (forwarded to the CLI)
        assertNull(find(commands, "/compact").localAction());
        assertNull(find(commands, "/init").localAction());
        assertNull(find(commands, "/review").localAction());
    }

    @Test
    public void codexCommandsAnnotateOnlyProviderAgnosticLocalActions() {
        List<SlashCommand> commands = SlashCommandRegistry.getCommands(CommonConstants.PROVIDER_CODEX, null);

        assertEquals(LocalSlashAction.NEW_SESSION.value(), find(commands, "/clear").localAction());
        assertEquals(LocalSlashAction.OPEN_HISTORY.value(), find(commands, "/resume").localAction());
        assertEquals(LocalSlashAction.MODEL_PICKER.value(), find(commands, "/model").localAction());
        assertEquals(LocalSlashAction.HELP.value(), find(commands, "/help").localAction());

        // /plan on Codex forwards to the CLI as plain text (intentional difference, mirrors the CLI)
        assertNull(find(commands, "/plan").localAction());
        // /context is not a Codex command at all
        assertNull(find(commands, "/context"));
    }

    @Test
    public void openCodeSharesClaudeBuiltinsButNotClaudeOnlyLocalActions() {
        List<SlashCommand> commands = SlashCommandRegistry.getCommands(CommonConstants.PROVIDER_OPENCODE, null);

        assertEquals(LocalSlashAction.NEW_SESSION.value(), find(commands, "/clear").localAction());
        assertEquals(LocalSlashAction.MODEL_PICKER.value(), find(commands, "/model").localAction());
        assertEquals(LocalSlashAction.HELP.value(), find(commands, "/help").localAction());

        // Claude-only local actions must not leak to other providers sharing CLAUDE_BUILTIN
        assertNotNull(find(commands, "/plan"));
        assertNull(find(commands, "/plan").localAction());
        assertNotNull(find(commands, "/context"));
        assertNull(find(commands, "/context").localAction());
    }

    @Test
    public void toJsonIncludesLocalActionOnlyWhenPresent() {
        String json = SlashCommandRegistry.toJson(List.of(
                new SlashCommand("/clear", "Clear conversation", "builtin")
                        .withLocalAction(LocalSlashAction.NEW_SESSION.value()),
                new SlashCommand("/compact", "Summarize conversation", "builtin")
        ));

        JsonArray array = JsonParser.parseString(json).getAsJsonArray();
        JsonObject clear = array.get(0).getAsJsonObject();
        assertEquals(LocalSlashAction.NEW_SESSION.value(), clear.get("localAction").getAsString());

        JsonObject compact = array.get(1).getAsJsonObject();
        assertFalse(compact.has("localAction"));
    }
}
