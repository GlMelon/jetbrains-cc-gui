package com.github.claudecodegui.session;

import com.github.claudecodegui.protocol.payload.SessionSkillCapabilityPayloadField;
import com.github.claudecodegui.skill.SlashCommandKind;
import com.github.claudecodegui.skill.SlashCommandRegistry.SlashCommand;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, session-scoped view of skills discovered during slash-command scanning. */
public final class SessionSkillSnapshot {
    private static final String EMPTY_VALUE = "";
    private static final String SLASH_PREFIX = "/";
    private static final String DOLLAR_PREFIX = "$";
    private static final String ID_SEPARATOR = ":";
    private static final String DEFAULT_SOURCE = "session-slash-commands";
    private static final SessionSkillSnapshot EMPTY = new SessionSkillSnapshot(List.of());

    public record Skill(String id, String name, String scope, String source) {}

    private final List<Skill> skills;

    private SessionSkillSnapshot(List<Skill> skills) {
        this.skills = Collections.unmodifiableList(new ArrayList<>(skills));
    }

    public static SessionSkillSnapshot empty() {
        return EMPTY;
    }

    public static SessionSkillSnapshot fromCommands(List<SlashCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return EMPTY;
        }
        Map<String, Skill> unique = new LinkedHashMap<>();
        for (SlashCommand command : commands) {
            if (command == null || command.kind() != SlashCommandKind.SKILL) {
                continue;
            }
            String rawName = command.name() == null ? EMPTY_VALUE : command.name().trim();
            if (rawName.isEmpty()) {
                continue;
            }
            String name = rawName.startsWith(SLASH_PREFIX) || rawName.startsWith(DOLLAR_PREFIX)
                    ? rawName.substring(1) : rawName;
            String source = normalize(command.source(), DEFAULT_SOURCE);
            String scope = normalize(command.scope(), source);
            String id = scope + ID_SEPARATOR + name;
            unique.put(id, new Skill(id, name, scope, source));
        }
        return unique.isEmpty() ? EMPTY : new SessionSkillSnapshot(List.copyOf(unique.values()));
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    public List<Skill> skills() {
        return skills;
    }

    public JsonArray toJson() {
        JsonArray result = new JsonArray();
        for (Skill skill : skills) {
            JsonObject item = new JsonObject();
            item.addProperty(SessionSkillCapabilityPayloadField.ID.wireKey(), skill.id());
            item.addProperty(SessionSkillCapabilityPayloadField.NAME.wireKey(), skill.name());
            item.addProperty(SessionSkillCapabilityPayloadField.SCOPE.wireKey(), skill.scope());
            item.addProperty(
                    SessionSkillCapabilityPayloadField.STATE.wireKey(),
                    SessionCapabilityState.DISCOVERED.value()
            );
            item.addProperty(SessionSkillCapabilityPayloadField.OBSERVED.wireKey(), true);
            item.addProperty(SessionSkillCapabilityPayloadField.SOURCE.wireKey(), skill.source());
            result.add(item);
        }
        return result;
    }

    public String toJsonString() {
        return GsonHolder.GSON.toJson(toJson());
    }
}
