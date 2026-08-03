package com.github.claudecodegui.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Backend authority for reading and safely editing provider-owned SKILL.md files. */
public final class SkillDocumentService {

    private static final SkillDocumentService INSTANCE =
            new SkillDocumentService(new AtomicSkillDocumentWriter());
    private static final int MAX_BODY_LENGTH = 1_048_576;
    private static final int MAX_PATHS = 256;
    // FIX: Use ConcurrentHashMap to prevent memory leaks. Old HashMap never evicted entries,
    // causing slow memory growth in long-running IDE sessions.
    private static final Map<Path, Object> FILE_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    private final SkillDocumentCodec codec = new SkillDocumentCodec();
    private final SkillDocumentWriter writer;

    SkillDocumentService(SkillDocumentWriter writer) {
        this.writer = writer;
    }

    public static SkillDocumentService getInstance() {
        return INSTANCE;
    }

    public JsonObject read(String provider, SkillDocumentIdentity identity, String cwd) {
        return read(UnifiedSkillServiceRegistry.forProvider(provider), identity, cwd);
    }

    JsonObject read(UnifiedSkillService provider, SkillDocumentIdentity identity, String cwd) {
        try {
            SkillDocumentTarget target = provider.resolveSkillDocument(identity, cwd);
            // SKILL-01: 读取前校验大小,防超大文件 OOM。
            if (Files.size(target.file()) > SkillFrontmatterParser.MAX_SKILL_FILE_SIZE) {
                return failure("SKILL.md exceeds the maximum allowed size", false, false);
            }
            String content = Files.readString(target.file(), StandardCharsets.UTF_8);
            SkillDocumentCodec.ParsedDocument document = codec.parse(content);
            SkillFrontmatterParser.SkillMetadata metadata =
                    SkillFrontmatterParser.parse(target.file().getParent());
            if (metadata == null) {
                throw new SkillDocumentFormatException("SKILL.md metadata could not be parsed");
            }
            return readSuccess(provider, target, document, metadata);
        } catch (SkillDocumentFormatException e) {
            return failure(e.getMessage(), true, false);
        } catch (SkillDocumentAccessException e) {
            return failure(e.getMessage(), false, false);
        } catch (IOException e) {
            return failure("Failed to read SKILL.md: " + e.getMessage(), false, false);
        }
    }

    public JsonObject save(String provider, SkillDocumentIdentity identity, String cwd,
                           String revision, JsonObject changes, String body) {
        return save(UnifiedSkillServiceRegistry.forProvider(provider), identity, cwd,
                revision, changes, body);
    }

    JsonObject save(UnifiedSkillService provider, SkillDocumentIdentity identity, String cwd,
                    String revision, JsonObject changes, String body) {
        SkillDocumentTarget target;
        try {
            target = provider.resolveSkillDocument(identity, cwd);
        } catch (SkillDocumentAccessException e) {
            return failure(e.getMessage(), false, false);
        }

        Object lock = lockFor(target.file());
        synchronized (lock) {
            try {
                // SKILL-01: 读取前校验大小,防超大文件 OOM。
                if (Files.size(target.file()) > SkillFrontmatterParser.MAX_SKILL_FILE_SIZE) {
                    return failure("SKILL.md exceeds the maximum allowed size", false, false);
                }
                String current = Files.readString(target.file(), StandardCharsets.UTF_8);
                String currentRevision = revision(current);
                if (revision == null || !currentRevision.equals(revision)) {
                    JsonObject conflict = failure(
                            "SKILL.md changed outside Codemoss; reload before saving", false, true);
                    conflict.addProperty("revision", currentRevision);
                    return conflict;
                }

                SkillDocumentCodec.ParsedDocument parsed = codec.parse(current);
                Map<SkillFrontmatterField, Object> validatedChanges =
                        validateChanges(provider.skillDocumentSchema(), changes);
                String validatedBody = validateBody(body);
                String generated = codec.render(parsed, validatedChanges, validatedBody);
                if (generated.equals(current)) {
                    JsonObject unchanged = successBase();
                    unchanged.addProperty("revision", currentRevision);
                    unchanged.addProperty("changed", false);
                    return unchanged;
                }

                Path backup = writer.write(target.file(), generated);
                try {
                    String persisted = Files.readString(target.file(), StandardCharsets.UTF_8);
                    codec.parse(persisted);
                    if (!persisted.equals(generated)) {
                        throw new IOException("Persisted SKILL.md content does not match generated content");
                    }
                    JsonObject result = successBase();
                    result.addProperty("revision", revision(persisted));
                    result.addProperty("changed", true);
                    result.addProperty("backupPath", backup.toString());
                    return result;
                } catch (Exception verificationFailure) {
                    // SKILL-03: restore 自身失败时文件留 corrupt + 孤儿 backup。异常不可逃逸到外层
                    // catch(IOException) 被伪装成通用 "Failed to save"——需显式告知用户回滚失败、
                    // 文件可能已损坏,提示从 backup 手动恢复。
                    boolean rolledBackOk;
                    String restoreError = null;
                    try {
                        writer.restore(target.file(), backup);
                        rolledBackOk = true;
                    } catch (Exception restoreFailure) {
                        rolledBackOk = false;
                        restoreError = restoreFailure instanceof IOException
                                ? restoreFailure.getMessage() : String.valueOf(restoreFailure);
                    }
                    String message = rolledBackOk
                            ? "SKILL.md verification failed and the backup was restored: "
                                    + verificationFailure.getMessage()
                            : "SKILL.md verification failed and rollback FAILED — the file may be corrupt; "
                                    + "recover from backup. Verification error: "
                                    + verificationFailure.getMessage()
                                    + (restoreError != null ? " | rollback error: " + restoreError : "");
                    JsonObject result = failure(message, false, false);
                    result.addProperty("rolledBack", rolledBackOk);
                    return result;
                }
            } catch (SkillDocumentFormatException e) {
                return failure(e.getMessage(), true, false);
            } catch (IllegalArgumentException e) {
                return failure(e.getMessage(), false, false);
            } catch (IOException e) {
                return failure("Failed to save SKILL.md: " + e.getMessage(), false, false);
            }
        }
    }

    private JsonObject readSuccess(UnifiedSkillService provider, SkillDocumentTarget target,
                                   SkillDocumentCodec.ParsedDocument document,
                                   SkillFrontmatterParser.SkillMetadata metadata) {
        JsonObject result = successBase();
        result.addProperty("provider", provider.provider().value());
        result.addProperty("revision", revision(document.originalContent()));
        result.addProperty("fileName", target.file().getFileName().toString());
        result.addProperty("body", document.body());
        result.addProperty("editable", true);

        JsonArray fields = new JsonArray();
        for (SkillFrontmatterField definition : provider.skillDocumentSchema().fields()) {
            JsonObject field = new JsonObject();
            field.addProperty("key", definition.key());
            field.addProperty("labelKey", definition.labelKey());
            field.addProperty("control", definition.control().value());
            field.addProperty("required", definition.required());
            if (definition.maxLength() > 0) {
                field.addProperty("maxLength", definition.maxLength());
            }
            field.addProperty("present", document.yamlMap().containsKey(definition.key()));
            addFieldValue(field, definition, metadata);
            fields.add(field);
        }
        result.add("fields", fields);
        return result;
    }

    private void addFieldValue(JsonObject field, SkillFrontmatterField definition,
                               SkillFrontmatterParser.SkillMetadata metadata) {
        switch (definition) {
            case NAME -> field.addProperty("value", metadata.name());
            case DESCRIPTION -> field.addProperty("value", metadata.description());
            case LICENSE -> addNullableString(field, metadata.license());
            case COMPATIBILITY -> addNullableString(field, metadata.compatibility());
            case ALLOWED_TOOLS -> addNullableString(field, metadata.allowedTools());
            case USER_INVOCABLE -> field.addProperty("value", metadata.userInvocable());
            case PATHS -> {
                JsonArray paths = new JsonArray();
                metadata.paths().forEach(paths::add);
                field.add("value", paths);
            }
        }
    }

    private void addNullableString(JsonObject field, String value) {
        if (value == null) {
            field.add("value", JsonNull.INSTANCE);
        } else {
            field.addProperty("value", value);
        }
    }

    private Map<SkillFrontmatterField, Object> validateChanges(
            SkillDocumentSchema schema, JsonObject changes) {
        Map<String, SkillFrontmatterField> allowed = new HashMap<>();
        for (SkillFrontmatterField field : schema.fields()) {
            allowed.put(field.key(), field);
        }
        Map<SkillFrontmatterField, Object> result = new EnumMap<>(SkillFrontmatterField.class);
        if (changes == null) {
            return result;
        }
        for (Map.Entry<String, JsonElement> entry : changes.entrySet()) {
            SkillFrontmatterField field = allowed.get(entry.getKey());
            if (field == null) {
                throw new IllegalArgumentException(
                        "Unsupported frontmatter field for this provider: " + entry.getKey());
            }
            result.put(field, validateValue(field, entry.getValue()));
        }
        return result;
    }

    private Object validateValue(SkillFrontmatterField field, JsonElement element) {
        return switch (field.control()) {
            case TEXT, TEXTAREA -> validateString(field, element);
            case BOOLEAN -> validateBoolean(field, element);
            case STRING_LIST -> validateStringList(field, element);
        };
    }

    private String validateString(SkillFrontmatterField field, JsonElement element) {
        String value = "";
        if (element != null && !element.isJsonNull()) {
            JsonPrimitive primitive = requirePrimitive(element);
            if (!primitive.isString()) {
                throw new IllegalArgumentException(field.key() + " must be a string");
            }
            value = primitive.getAsString();
        }
        value = value.trim();
        if (field.required() && value.isEmpty()) {
            throw new IllegalArgumentException(field.key() + " is required");
        }
        if (field.maxLength() > 0 && value.length() > field.maxLength()) {
            throw new IllegalArgumentException(field.key() + " exceeds its maximum length");
        }
        if (field == SkillFrontmatterField.NAME && !SkillFrontmatterParser.isValidSkillName(value)) {
            throw new IllegalArgumentException("name must use lowercase letters, numbers, and single hyphens");
        }
        return value;
    }

    private boolean validateBoolean(SkillFrontmatterField field, JsonElement element) {
        JsonPrimitive primitive = requirePrimitive(element);
        if (!primitive.isBoolean()) {
            throw new IllegalArgumentException(field.key() + " must be a boolean");
        }
        return primitive.getAsBoolean();
    }

    private List<String> validateStringList(SkillFrontmatterField field, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException(field.key() + " must be a string list");
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() > MAX_PATHS) {
            throw new IllegalArgumentException(field.key() + " contains too many entries");
        }
        List<String> values = new ArrayList<>();
        for (JsonElement item : array) {
            JsonPrimitive primitive = requirePrimitive(item);
            if (!primitive.isString()) {
                throw new IllegalArgumentException(field.key() + " entries must be strings");
            }
            String value = primitive.getAsString().trim();
            if (value.isEmpty()) {
                continue;
            }
            if (field.maxLength() > 0 && value.length() > field.maxLength()) {
                throw new IllegalArgumentException(field.key() + " entry exceeds its maximum length");
            }
            values.add(value);
        }
        return values;
    }

    private JsonPrimitive requirePrimitive(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException("Frontmatter field value must be a primitive");
        }
        return element.getAsJsonPrimitive();
    }

    private String validateBody(String body) {
        if (body == null) {
            return null;
        }
        String value = body;
        if (value.length() > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("Markdown body exceeds the maximum length");
        }
        return value;
    }

    private static Object lockFor(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        // ConcurrentHashMap.computeIfAbsent is atomic, no external sync needed
        return FILE_LOCKS.computeIfAbsent(normalized, ignored -> new Object());
    }

    private static String revision(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static JsonObject successBase() {
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        return result;
    }

    private static JsonObject failure(String error, boolean parseError, boolean conflict) {
        JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("editable", false);
        result.addProperty("parseError", parseError);
        result.addProperty("conflict", conflict);
        result.addProperty("error", error);
        return result;
    }
}
