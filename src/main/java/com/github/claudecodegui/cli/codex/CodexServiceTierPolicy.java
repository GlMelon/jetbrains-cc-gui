package com.github.claudecodegui.cli.codex;

import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Objects;

/**
 * Determines whether a Codex model advertises a requested service tier.
 *
 * <p>The catalog is treated as a capability declaration, not as a source of
 * defaults. Missing, malformed, or incomplete catalog data therefore fails
 * closed and does not send an experimental service tier override to the CLI.</p>
 */
public final class CodexServiceTierPolicy {

    private static final Logger LOG = Logger.getInstance(CodexServiceTierPolicy.class);
    private static final String CATALOG_FILE_NAME = "cc-switch-model-catalog.json";
    private static final String CATALOG_MODELS_KEY = "models";
    private static final String SERVICE_TIERS_KEY = "service_tiers";
    private static final String ADDITIONAL_SPEED_TIERS_KEY = "additional_speed_tiers";

    private final Path catalogPath;
    private volatile CatalogSnapshot cachedSnapshot;

    public CodexServiceTierPolicy() {
        this(resolveDefaultCatalogPath());
    }

    public CodexServiceTierPolicy(Path catalogPath) {
        this.catalogPath = Objects.requireNonNull(catalogPath, "catalogPath").toAbsolutePath().normalize();
    }

    /**
     * Returns true only when the catalog explicitly lists the tier for the
     * selected model.
     */
    public boolean supports(String model, String serviceTier) {
        if (isBlank(model) || isBlank(serviceTier)) {
            return false;
        }

        FileState fileState = readFileState(catalogPath);
        CatalogSnapshot snapshot = cachedSnapshot;
        if (snapshot != null && snapshot.matches(fileState)) {
            return snapshot.supports(model, serviceTier);
        }

        CatalogSnapshot loaded = loadSnapshot(fileState);
        cachedSnapshot = loaded;
        return loaded.supports(model, serviceTier);
    }

    static boolean supports(JsonObject catalog, String model, String serviceTier) {
        if (catalog == null || isBlank(model) || isBlank(serviceTier)
                || !catalog.has(CATALOG_MODELS_KEY) || !catalog.get(CATALOG_MODELS_KEY).isJsonArray()) {
            return false;
        }

        JsonArray models = catalog.getAsJsonArray(CATALOG_MODELS_KEY);
        for (JsonElement modelElement : models) {
            if (!modelElement.isJsonObject()) {
                continue;
            }
            JsonObject modelObject = modelElement.getAsJsonObject();
            if (matchesModel(modelObject, model)) {
                return containsTier(modelObject.get(SERVICE_TIERS_KEY), serviceTier)
                        || containsTier(modelObject.get(ADDITIONAL_SPEED_TIERS_KEY), serviceTier);
            }
        }
        return false;
    }

    private CatalogSnapshot loadSnapshot(FileState fileState) {
        if (!fileState.exists()) {
            return CatalogSnapshot.unsupported(fileState);
        }
        try {
            String content = Files.readString(catalogPath, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(content);
            if (!parsed.isJsonObject()) {
                LOG.warn("[Codex] Model catalog is not a JSON object: " + catalogPath);
                return CatalogSnapshot.unsupported(fileState);
            }
            return CatalogSnapshot.loaded(fileState, parsed.getAsJsonObject());
        } catch (Exception e) {
            LOG.warn("[Codex] Failed to read model capability catalog: " + catalogPath, e);
            return CatalogSnapshot.unsupported(fileState);
        }
    }

    private static boolean matchesModel(JsonObject modelObject, String requestedModel) {
        String normalized = requestedModel.trim();
        String[] keys = {"slug", "id", "model", "name", "display_name"};
        for (String key : keys) {
            JsonElement value = modelObject.get(key);
            if (value != null && value.isJsonPrimitive()
                    && normalized.equalsIgnoreCase(value.getAsString().trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTier(JsonElement tiers, String requestedTier) {
        if (tiers == null || tiers.isJsonNull()) {
            return false;
        }
        String normalized = requestedTier.trim();
        if (tiers.isJsonArray()) {
            for (JsonElement tier : tiers.getAsJsonArray()) {
                if (tierMatches(tier, normalized)) {
                    return true;
                }
            }
            return false;
        }
        return tierMatches(tiers, normalized);
    }

    private static boolean tierMatches(JsonElement tier, String requestedTier) {
        if (tier == null || tier.isJsonNull()) {
            return false;
        }
        if (tier.isJsonPrimitive()) {
            return requestedTier.equalsIgnoreCase(tier.getAsString().trim());
        }
        if (!tier.isJsonObject()) {
            return false;
        }
        JsonObject tierObject = tier.getAsJsonObject();
        String[] keys = {"id", "name", "value", "tier", "slug"};
        for (String key : keys) {
            JsonElement value = tierObject.get(key);
            if (value != null && value.isJsonPrimitive()
                    && requestedTier.equalsIgnoreCase(value.getAsString().trim())) {
                return true;
            }
        }
        return false;
    }

    private static Path resolveDefaultCatalogPath() {
        String home = PlatformUtils.getHomeDirectory();
        if (isBlank(home)) {
            return Path.of(CATALOG_FILE_NAME);
        }
        return Path.of(home, ".codex", CATALOG_FILE_NAME);
    }

    private static FileState readFileState(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return new FileState(false, -1L, -1L);
            }
            FileTime modified = Files.getLastModifiedTime(path);
            return new FileState(true, modified.toMillis(), Files.size(path));
        } catch (IOException e) {
            return new FileState(false, -1L, -1L);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record FileState(boolean exists, long lastModifiedMillis, long size) {
    }

    private static final class CatalogSnapshot {
        private final FileState fileState;
        private final JsonObject catalog;

        private CatalogSnapshot(FileState fileState, JsonObject catalog) {
            this.fileState = fileState;
            this.catalog = catalog;
        }

        private static CatalogSnapshot loaded(FileState fileState, JsonObject catalog) {
            return new CatalogSnapshot(fileState, catalog);
        }

        private static CatalogSnapshot unsupported(FileState fileState) {
            return new CatalogSnapshot(fileState, null);
        }

        private boolean matches(FileState current) {
            return fileState.equals(current);
        }

        private boolean supports(String model, String serviceTier) {
            return CodexServiceTierPolicy.supports(catalog, model, serviceTier);
        }
    }
}
