package com.github.claudecodegui.settings.avatar;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.settings.ConfigPathManager;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * Backend SSOT for chat avatar selections and custom-avatar storage.
 */
public final class AvatarConfigService {
    private static final Logger LOG = Logger.getInstance(AvatarConfigService.class);

    private static final String KEY_ASSISTANT = "assistant";
    private static final String KEY_USER = "user";
    private static final String KEY_MODE = "mode";
    private static final String KEY_PRESET = "preset";
    private static final String KEY_CUSTOM = "custom";
    private static final String KEY_CUSTOM_ID = "customId";
    private static final String KEY_ID = "id";
    private static final String KEY_ROLE = "role";
    private static final String KEY_MIME_TYPE = "mimeType";
    private static final String KEY_BASE64 = "base64";
    private static final String KEY_DATA_URL = "dataUrl";
    private static final String KEY_CREATED_AT = "createdAt";
    private static final String KEY_UPDATED_AT = "updatedAt";
    private static final String KEY_ORIGINAL_NAME = "originalName";
    private static final String KEY_ASSISTANT_PRESET_OPTIONS = "assistantPresetOptions";
    private static final String KEY_VALUE = "value";
    private static final String KEY_LABEL = "label";

    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_USER = "user";
    private static final String MODE_PROVIDER = "provider";
    private static final String MODE_PRESET = "preset";
    private static final String MODE_CUSTOM = "custom";
    private static final String PRESET_ASSISTANT_DEFAULT = "assistant-default";
    private static final String PRESET_USER_DEFAULT = "user-default";

    private static final String MIME_PNG = "image/png";
    private static final String MIME_JPEG = "image/jpeg";
    private static final String MIME_WEBP = "image/webp";
    private static final String MIME_SVG = "image/svg+xml";
    private static final String DATA_URL_PREFIX = "data:";
    private static final String DATA_URL_BASE64_MARKER = ";base64,";
    private static final String CUSTOM_FILE_EXTENSION = ".json";
    private static final int MAX_AVATAR_BYTES = 1_048_576;

    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of(MIME_PNG, MIME_JPEG, MIME_WEBP, MIME_SVG);

    private final ConfigPathManager pathManager;

    public AvatarConfigService() {
        this(new ConfigPathManager());
    }

    AvatarConfigService(ConfigPathManager pathManager) {
        this.pathManager = pathManager;
    }

    public AvatarConfigResult getConfig() {
        return new AvatarConfigResult(serializeAuthoritativeConfig());
    }

    public AvatarConfigResult applyConfig(String payload) {
        try {
            JsonObject requested = JsonParser.parseString(payload == null ? "{}" : payload).getAsJsonObject();
            JsonObject sanitized = sanitizeConfig(requested);
            writeJson(pathManager.getAvatarSettingsFilePath(), sanitized);
        } catch (Exception e) {
            LOG.warn("[AvatarConfigService] Failed to save avatar config: " + e.getMessage(), e);
        }
        return getConfig();
    }

    public AvatarConfigResult uploadCustom(String payload, Path selectedPath, String originalName) {
        try {
            String role = readRole(payload);
            if (!ROLE_ASSISTANT.equals(role) && !ROLE_USER.equals(role)) {
                LOG.warn("[AvatarConfigService] Ignored custom avatar upload with unsupported role: " + role);
                return getConfig();
            }
            if (selectedPath == null || !Files.isRegularFile(selectedPath)) {
                LOG.warn("[AvatarConfigService] Ignored custom avatar upload with missing file");
                return getConfig();
            }

            long size = Files.size(selectedPath);
            if (size <= 0 || size > MAX_AVATAR_BYTES) {
                LOG.warn("[AvatarConfigService] Ignored custom avatar upload with invalid byte size: " + size);
                return getConfig();
            }

            String mimeType = detectMimeType(selectedPath);
            if (!SUPPORTED_MIME_TYPES.contains(mimeType)) {
                LOG.warn("[AvatarConfigService] Ignored unsupported custom avatar mime type: " + mimeType);
                return getConfig();
            }

            byte[] bytes = Files.readAllBytes(selectedPath);
            String encoded = Base64.getEncoder().encodeToString(bytes);
            long now = System.currentTimeMillis();
            String id = role + "-" + now;

            JsonObject custom = new JsonObject();
            custom.addProperty(KEY_ID, id);
            custom.addProperty(KEY_ROLE, role);
            custom.addProperty(KEY_MIME_TYPE, mimeType);
            custom.addProperty(KEY_BASE64, encoded);
            custom.addProperty(KEY_CREATED_AT, now);
            custom.addProperty(KEY_UPDATED_AT, now);
            custom.addProperty(KEY_ORIGINAL_NAME, originalName == null ? selectedPath.getFileName().toString() : originalName);
            writeJson(customAvatarFile(id), custom);

            JsonObject settings = readSettingsOrDefault();
            JsonObject selection = new JsonObject();
            selection.addProperty(KEY_MODE, MODE_CUSTOM);
            selection.addProperty(KEY_CUSTOM_ID, id);
            settings.add(role, selection);
            writeJson(pathManager.getAvatarSettingsFilePath(), sanitizeConfig(settings));
        } catch (Exception e) {
            LOG.warn("[AvatarConfigService] Failed to upload custom avatar: " + e.getMessage(), e);
        }
        return getConfig();
    }

    public String serializeAuthoritativeConfig() {
        try {
            JsonObject persisted = readSettingsOrDefault();
            return GsonHolder.GSON.toJson(hydrateConfig(sanitizeConfig(persisted)));
        } catch (Exception e) {
            LOG.warn("[AvatarConfigService] Failed to read avatar config: " + e.getMessage(), e);
            return GsonHolder.GSON.toJson(defaultConfig());
        }
    }

    private JsonObject readSettingsOrDefault() {
        try {
            pathManager.ensureAvatarDirectories();
            Path file = pathManager.getAvatarSettingsFilePath();
            if (!Files.exists(file)) {
                return defaultConfig();
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject parsed = JsonParser.parseString(content).getAsJsonObject();
            return sanitizeConfig(parsed);
        } catch (Exception e) {
            LOG.warn("[AvatarConfigService] Falling back to default avatar config: " + e.getMessage(), e);
            return defaultConfig();
        }
    }

    private JsonObject defaultConfig() {
        JsonObject root = new JsonObject();
        JsonObject assistant = new JsonObject();
        assistant.addProperty(KEY_MODE, MODE_PROVIDER);
        root.add(KEY_ASSISTANT, assistant);

        JsonObject user = new JsonObject();
        user.addProperty(KEY_MODE, MODE_PRESET);
        user.addProperty(KEY_PRESET, PRESET_USER_DEFAULT);
        root.add(KEY_USER, user);
        root.add(KEY_ASSISTANT_PRESET_OPTIONS, assistantPresetOptions());
        return root;
    }

    private JsonObject sanitizeConfig(JsonObject raw) {
        JsonObject root = new JsonObject();
        JsonObject source = raw == null ? new JsonObject() : raw;
        root.add(KEY_ASSISTANT, sanitizeAssistantSelection(readObject(source, KEY_ASSISTANT)));
        root.add(KEY_USER, sanitizeUserSelection(readObject(source, KEY_USER)));
        return root;
    }

    private JsonObject sanitizeAssistantSelection(JsonObject raw) {
        String mode = readString(raw, KEY_MODE, MODE_PROVIDER);
        if (MODE_CUSTOM.equals(mode)) {
            String customId = readCustomId(raw);
            if (customId != null && customAvatarExists(customId)) {
                JsonObject selection = new JsonObject();
                selection.addProperty(KEY_MODE, MODE_CUSTOM);
                selection.addProperty(KEY_CUSTOM_ID, customId);
                return selection;
            }
        }
        if (MODE_PRESET.equals(mode)) {
            String preset = readString(raw, KEY_PRESET, PRESET_ASSISTANT_DEFAULT);
            if (isAssistantPreset(preset)) {
                JsonObject selection = new JsonObject();
                selection.addProperty(KEY_MODE, MODE_PRESET);
                selection.addProperty(KEY_PRESET, preset);
                return selection;
            }
        }
        JsonObject selection = new JsonObject();
        selection.addProperty(KEY_MODE, MODE_PROVIDER);
        return selection;
    }

    private JsonObject sanitizeUserSelection(JsonObject raw) {
        String mode = readString(raw, KEY_MODE, MODE_PRESET);
        if (MODE_CUSTOM.equals(mode)) {
            String customId = readCustomId(raw);
            if (customId != null && customAvatarExists(customId)) {
                JsonObject selection = new JsonObject();
                selection.addProperty(KEY_MODE, MODE_CUSTOM);
                selection.addProperty(KEY_CUSTOM_ID, customId);
                return selection;
            }
        }
        JsonObject selection = new JsonObject();
        selection.addProperty(KEY_MODE, MODE_PRESET);
        selection.addProperty(KEY_PRESET, PRESET_USER_DEFAULT);
        return selection;
    }

    private JsonObject hydrateConfig(JsonObject stored) {
        JsonObject root = new JsonObject();
        root.add(KEY_ASSISTANT, hydrateSelection(readObject(stored, KEY_ASSISTANT), ROLE_ASSISTANT));
        root.add(KEY_USER, hydrateSelection(readObject(stored, KEY_USER), ROLE_USER));
        root.add(KEY_ASSISTANT_PRESET_OPTIONS, assistantPresetOptions());
        return root;
    }

    private com.google.gson.JsonArray assistantPresetOptions() {
        com.google.gson.JsonArray options = new com.google.gson.JsonArray();
        for (ProviderType provider : ProviderType.values()) {
            JsonObject option = new JsonObject();
            option.addProperty(KEY_VALUE, provider.value());
            option.addProperty(KEY_LABEL, provider.displayLabel());
            options.add(option);
        }
        return options;
    }

    private JsonObject hydrateSelection(JsonObject stored, String role) {
        JsonObject hydrated = new JsonObject();
        String mode = readString(stored, KEY_MODE, ROLE_ASSISTANT.equals(role) ? MODE_PROVIDER : MODE_PRESET);
        hydrated.addProperty(KEY_MODE, mode);
        String preset = readString(stored, KEY_PRESET, null);
        if (preset != null) {
            hydrated.addProperty(KEY_PRESET, preset);
        }
        if (MODE_CUSTOM.equals(mode)) {
            String customId = readString(stored, KEY_CUSTOM_ID, null);
            JsonObject custom = readCustomAvatar(customId, role);
            if (custom != null) {
                hydrated.add(KEY_CUSTOM, custom);
            } else {
                return ROLE_ASSISTANT.equals(role) ? sanitizeAssistantSelection(new JsonObject()) : sanitizeUserSelection(new JsonObject());
            }
        }
        // Always include previously uploaded custom avatar data so the frontend
        // can show the "uploaded" option even when not in CUSTOM mode.
        JsonObject latestCustom = findLatestCustomForRole(role);
        if (latestCustom != null) {
            hydrated.add(KEY_CUSTOM, latestCustom);
        }
        return hydrated;
    }

    private JsonObject readCustomAvatar(String customId, String role) {
        if (customId == null || customId.isBlank()) {
            return null;
        }
        try {
            JsonObject stored = readJson(customAvatarFile(customId));
            String storedRole = readString(stored, KEY_ROLE, null);
            String mimeType = readString(stored, KEY_MIME_TYPE, null);
            String base64 = readString(stored, KEY_BASE64, null);
            if (!role.equals(storedRole) || !SUPPORTED_MIME_TYPES.contains(mimeType) || base64 == null || base64.isBlank()) {
                return null;
            }
            JsonObject custom = new JsonObject();
            custom.addProperty(KEY_ID, customId);
            custom.addProperty(KEY_MIME_TYPE, mimeType);
            custom.addProperty(KEY_DATA_URL, DATA_URL_PREFIX + mimeType + DATA_URL_BASE64_MARKER + base64);
            return custom;
        } catch (Exception e) {
            LOG.warn("[AvatarConfigService] Failed to hydrate custom avatar " + customId + ": " + e.getMessage(), e);
            return null;
        }
    }

    private String readRole(String payload) {
        try {
            JsonObject root = JsonParser.parseString(payload == null ? "{}" : payload).getAsJsonObject();
            return readString(root, KEY_ROLE, null);
        } catch (Exception e) {
            return null;
        }
    }

    private String readCustomId(JsonObject raw) {
        String direct = readString(raw, KEY_CUSTOM_ID, null);
        if (direct != null) {
            return direct;
        }
        JsonObject custom = readObject(raw, KEY_CUSTOM);
        return readString(custom, KEY_ID, null);
    }

    private boolean isAssistantPreset(String preset) {
        return PRESET_ASSISTANT_DEFAULT.equals(preset)
                || ProviderType.fromValue(preset).isPresent();
    }

    private boolean customAvatarExists(String id) {
        return id != null && Files.isRegularFile(customAvatarFile(id));
    }

    /**
     * Find the latest uploaded custom avatar for the given role by scanning
     * the custom avatar directory. Returns null if none exists.
     */
    private JsonObject findLatestCustomForRole(String role) {
        try {
            Path customDir = pathManager.getAvatarCustomDir();
            if (!Files.isDirectory(customDir)) {
                return null;
            }
            String prefix = role + "-";
            Path latest = null;
            long latestTime = -1;
            try (var stream = Files.list(customDir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    String name = p.getFileName().toString();
                    if (name.startsWith(prefix) && name.endsWith(CUSTOM_FILE_EXTENSION)) {
                        long mod = p.toFile().lastModified();
                        if (mod > latestTime) {
                            latestTime = mod;
                            latest = p;
                        }
                    }
                }
            }
            if (latest != null) {
                String customId = latest.getFileName().toString().replace(CUSTOM_FILE_EXTENSION, "");
                return readCustomAvatar(customId, role);
            }
        } catch (IOException e) {
            LOG.warn("[AvatarConfigService] Failed to scan custom avatars for " + role + ": " + e.getMessage(), e);
        }
        return null;
    }

    private Path customAvatarFile(String id) {
        return pathManager.getAvatarCustomDir().resolve(safeId(id) + CUSTOM_FILE_EXTENSION);
    }

    private String safeId(String id) {
        return id == null ? "" : id.replaceAll("[^A-Za-z0-9._-]", "");
    }

    private String detectMimeType(Path path) throws IOException {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".png")) {
            return MIME_PNG;
        }
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return MIME_JPEG;
        }
        if (fileName.endsWith(".webp")) {
            return MIME_WEBP;
        }
        if (fileName.endsWith(".svg")) {
            return MIME_SVG;
        }
        String probed = Files.probeContentType(path);
        return probed == null ? "" : probed.toLowerCase(Locale.ROOT);
    }

    private JsonObject readObject(JsonObject root, String key) {
        if (root != null && root.has(key) && root.get(key).isJsonObject()) {
            return root.getAsJsonObject(key);
        }
        return new JsonObject();
    }

    private String readString(JsonObject root, String key, String fallback) {
        if (root != null && root.has(key)) {
            JsonElement element = root.get(key);
            if (element != null && !element.isJsonNull()) {
                try {
                    String value = element.getAsString();
                    if (value != null && !value.isBlank()) {
                        return value;
                    }
                } catch (Exception ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private JsonObject readJson(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        return JsonParser.parseString(content).getAsJsonObject();
    }

    private void writeJson(Path file, JsonObject json) throws IOException {
        pathManager.ensureAvatarDirectories();
        Files.writeString(file, GsonHolder.GSON.toJson(json), StandardCharsets.UTF_8);
    }
}
