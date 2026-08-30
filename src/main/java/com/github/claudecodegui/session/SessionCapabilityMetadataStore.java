package com.github.claudecodegui.session;

import com.github.claudecodegui.bridge.NodeDetector;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded, best-effort persistence for negotiated session capabilities.
 *
 * <p>This metadata is deliberately separate from provider history files. A
 * provider may rewrite or delete its history independently, while the plugin
 * still needs to explain the runtime channel used by a historical session.</p>
 */
public final class SessionCapabilityMetadataStore {
    private static final Logger LOG = Logger.getInstance(SessionCapabilityMetadataStore.class);
    private static final int VERSION = 1;
    private static final int MAX_ENTRIES = 2_048;
    private static final long MAX_FILE_BYTES = 1_024L * 1_024L;
    private static final Gson NULLS_GSON = new GsonBuilder().serializeNulls().create();
    private static final String VERSION_KEY = "version";
    private static final String ENTRIES_KEY = "entries";
    private static final String PROVIDER_KEY = "provider";
    private static final String SESSION_ID_KEY = "sessionId";
    private static final String OBSERVED_AT_KEY = "observedAt";
    private static final String CAPABILITIES_KEY = "capabilities";

    private static final class Holder {
        private static final SessionCapabilityMetadataStore INSTANCE =
                new SessionCapabilityMetadataStore(defaultPath());
    }

    private final Path metadataPath;
    private final ReentrantLock fileLock = new ReentrantLock();

    public SessionCapabilityMetadataStore(Path metadataPath) {
        this.metadataPath = metadataPath;
    }

    public static SessionCapabilityMetadataStore getInstance() {
        return Holder.INSTANCE;
    }

    private static Path defaultPath() {
        return Path.of(NodeDetector.resolveHomeForFileOps(), ".codemoss", "session-capabilities.json");
    }

    /** Persist the latest negotiated snapshot without allowing unbounded growth. */
    public void save(String provider, String sessionId, SessionNegotiatedCapabilities capabilities, long observedAt) {
        if (isBlank(provider) || isBlank(sessionId) || capabilities == null) {
            return;
        }
        fileLock.lock();
        try {
            List<JsonObject> entries = readEntriesLocked();
            JsonObject replacement = new JsonObject();
            replacement.addProperty(PROVIDER_KEY, provider);
            replacement.addProperty(SESSION_ID_KEY, sessionId);
            replacement.addProperty(OBSERVED_AT_KEY, observedAt);
            replacement.add(CAPABILITIES_KEY, capabilities.toJson());

            entries.removeIf(entry -> provider.equals(stringValue(entry, PROVIDER_KEY))
                    && sessionId.equals(stringValue(entry, SESSION_ID_KEY)));
            entries.add(replacement);
            trimEntries(entries);
            writeEntriesLocked(entries);
        } catch (Exception e) {
            LOG.warn("[SessionCapabilityMetadataStore] Failed to persist capability metadata: " + e.getMessage());
        } finally {
            fileLock.unlock();
        }
    }

    /** Return a defensive copy; malformed or missing metadata is treated as absent. */
    public JsonObject find(String provider, String sessionId) {
        if (isBlank(provider) || isBlank(sessionId)) {
            return null;
        }
        fileLock.lock();
        try {
            for (JsonObject entry : readEntriesLocked()) {
                if (!provider.equals(stringValue(entry, PROVIDER_KEY))
                        || !sessionId.equals(stringValue(entry, SESSION_ID_KEY))) {
                    continue;
                }
                JsonElement capabilities = entry.get(CAPABILITIES_KEY);
                return capabilities != null && capabilities.isJsonObject()
                        ? capabilities.getAsJsonObject().deepCopy()
                        : null;
            }
        } catch (Exception e) {
            LOG.warn("[SessionCapabilityMetadataStore] Failed to read capability metadata: " + e.getMessage());
        } finally {
            fileLock.unlock();
        }
        return null;
    }

    /** Remove metadata for one provider/session pair. */
    public void remove(String provider, String sessionId) {
        if (isBlank(provider) || isBlank(sessionId)) {
            return;
        }
        fileLock.lock();
        try {
            List<JsonObject> entries = readEntriesLocked();
            boolean changed = entries.removeIf(entry -> provider.equals(stringValue(entry, PROVIDER_KEY))
                    && sessionId.equals(stringValue(entry, SESSION_ID_KEY)));
            if (changed) {
                writeEntriesLocked(entries);
            }
        } catch (Exception e) {
            LOG.warn("[SessionCapabilityMetadataStore] Failed to remove capability metadata: " + e.getMessage());
        } finally {
            fileLock.unlock();
        }
    }

    Path metadataPathForTest() {
        return metadataPath;
    }

    private List<JsonObject> readEntriesLocked() {
        if (!Files.isRegularFile(metadataPath)) {
            return new ArrayList<>();
        }
        try {
            if (Files.size(metadataPath) > MAX_FILE_BYTES) {
                LOG.warn("[SessionCapabilityMetadataStore] Metadata file exceeds size limit, ignoring it");
                return new ArrayList<>();
            }
            String json = Files.readString(metadataPath, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return new ArrayList<>();
            }
            JsonElement rawEntries = root.getAsJsonObject().get(ENTRIES_KEY);
            if (rawEntries == null || !rawEntries.isJsonArray()) {
                return new ArrayList<>();
            }
            List<JsonObject> entries = new ArrayList<>();
            for (JsonElement element : rawEntries.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                if (!isBlank(stringValue(entry, PROVIDER_KEY))
                        && !isBlank(stringValue(entry, SESSION_ID_KEY))) {
                    entries.add(entry.deepCopy());
                }
            }
            trimEntries(entries);
            return entries;
        } catch (Exception e) {
            LOG.warn("[SessionCapabilityMetadataStore] Ignoring unreadable metadata: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private void writeEntriesLocked(List<JsonObject> entries) throws IOException {
        Path parent = metadataPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        JsonObject root = new JsonObject();
        root.addProperty(VERSION_KEY, VERSION);
        JsonArray array = new JsonArray();
        entries.forEach(array::add);
        root.add(ENTRIES_KEY, array);
        byte[] bytes = NULLS_GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            trimEntries(entries);
            root = new JsonObject();
            root.addProperty(VERSION_KEY, VERSION);
            array = new JsonArray();
            entries.forEach(array::add);
            root.add(ENTRIES_KEY, array);
            bytes = NULLS_GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        }

        Path temp = Files.createTempFile(parent == null ? metadataPath.toAbsolutePath().getParent() : parent,
                metadataPath.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temp, metadataPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                LOG.warn("[SessionCapabilityMetadataStore] Atomic move unsupported, using replace move");
                Files.move(temp, metadataPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void trimEntries(List<JsonObject> entries) {
        entries.sort(Comparator.comparingLong(
                (JsonObject entry) -> numberValue(entry, OBSERVED_AT_KEY)).reversed());
        if (entries.size() > MAX_ENTRIES) {
            entries.subList(MAX_ENTRIES, entries.size()).clear();
        }
    }

    private static String stringValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static long numberValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                ? value.getAsLong() : 0L;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
