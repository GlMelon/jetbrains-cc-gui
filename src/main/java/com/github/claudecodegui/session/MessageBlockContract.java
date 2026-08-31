package com.github.claudecodegui.session;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.protocol.MessageBlockToolIdSource;
import com.github.claudecodegui.protocol.MessageBlockToolStatus;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Single backend contract for assistant content blocks emitted by live CLI
 * parsers and reconstructed from provider history.
 *
 * <p>Provider adapters may use different source field names, but the wire
 * shape produced here is always the Claude-compatible block shape consumed by
 * {@code MessageParser} and the webview. Tool identity and lifecycle status
 * are owned here rather than inferred by the frontend.</p>
 */
public final class MessageBlockContract {

    public static final String KEY_TOOL_STATUS = "tool_status";
    public static final String KEY_TOOL_ID_SOURCE = "tool_id_source";
    public static final String KEY_PAIRED = "paired";

    private static final String DEFAULT_TOOL_NAME = "tool";
    private static final String GENERATED_TOOL_PREFIX = "tool-use-";
    private static final String GENERATED_RESULT_PREFIX = "tool-result-";

    private MessageBlockContract() {
    }

    /** Current tool pairing diagnostics for one live turn or history replay. */
    public record ToolDiagnostics(int pendingToolCalls, int orphanToolResults) {
        public ToolDiagnostics {
            pendingToolCalls = Math.max(0, pendingToolCalls);
            orphanToolResults = Math.max(0, orphanToolResults);
        }
    }

    @FunctionalInterface
    public interface ToolDiagnosticsObserver {
        void onDiagnostics(ToolDiagnostics diagnostics);
    }

    /** Stateful identity and lifecycle ledger for one live turn or history replay. */
    public static final class ToolLedger {
        private static final ToolDiagnosticsObserver NOOP_DIAGNOSTICS_OBSERVER = diagnostics -> { };

        private final Set<String> toolUseIds = new LinkedHashSet<>();
        private final Set<String> completedResultIds = new LinkedHashSet<>();
        private final Set<String> orphanResultIds = new LinkedHashSet<>();
        private final ToolDiagnosticsObserver diagnosticsObserver;
        private int generatedToolSequence;
        private int generatedResultSequence;
        private boolean finalized;

        public ToolLedger() {
            this(NOOP_DIAGNOSTICS_OBSERVER);
        }

        public ToolLedger(ToolDiagnosticsObserver diagnosticsObserver) {
            this.diagnosticsObserver = diagnosticsObserver == null
                    ? NOOP_DIAGNOSTICS_OBSERVER
                    : diagnosticsObserver;
            publishDiagnostics();
        }

        public JsonObject normalizeToolUse(JsonObject source) {
            return normalizeToolUse(source, "tool-" + (++generatedToolSequence));
        }

        public JsonObject normalizeToolUse(JsonObject source, String identitySeed) {
            JsonObject input = source == null ? new JsonObject() : source;
            String suppliedId = firstString(input, CommonConstants.JSON_KEY_ID,
                    CommonConstants.JSON_KEY_TOOL_USE_ID, "toolCallId", "tool_call_id");
            String name = firstString(input, CommonConstants.JSON_KEY_NAME,
                    CommonConstants.JSON_KEY_TOOL, "toolName", "tool_name");
            JsonElement rawInput = firstElement(input, CommonConstants.JSON_KEY_INPUT,
                    "arguments", "args", "parameters");
            JsonObject normalizedInput = asObject(rawInput);
            boolean generatedId = MessageBlockToolIdSource.GENERATED.value().equals(
                    firstString(input, KEY_TOOL_ID_SOURCE));
            boolean reuseGeneratedId = generatedId && hasText(suppliedId);
            boolean explicitId = hasText(suppliedId) && !generatedId;
            String id = explicitId || reuseGeneratedId
                    ? suppliedId.trim()
                    : generatedIdentity(GENERATED_TOOL_PREFIX, identitySeed, name, normalizedInput);

            toolUseIds.add(id);
            orphanResultIds.remove(id);
            JsonObject block = input.deepCopy();
            block.addProperty(CommonConstants.JSON_KEY_TYPE, CommonConstants.BLOCK_TYPE_TOOL_USE);
            block.addProperty(CommonConstants.JSON_KEY_ID, id);
            block.addProperty(CommonConstants.JSON_KEY_NAME,
                    hasText(name) ? name.trim() : DEFAULT_TOOL_NAME);
            block.add(CommonConstants.JSON_KEY_INPUT, normalizedInput);
            block.addProperty(KEY_TOOL_ID_SOURCE, explicitId
                    ? MessageBlockToolIdSource.EXPLICIT.value()
                    : MessageBlockToolIdSource.GENERATED.value());
            applyToolUseStatus(block, id);
            publishDiagnostics();
            return block;
        }

        public JsonObject normalizeToolResult(JsonObject source) {
            return normalizeToolResult(source, toolUseIds);
        }

        private JsonObject normalizeToolResult(
                JsonObject source,
                Collection<String> pendingCandidates
        ) {
            JsonObject input = source == null ? new JsonObject() : source;
            String suppliedId = toolResultId(input);
            boolean generatedId = MessageBlockToolIdSource.GENERATED.value().equals(
                    firstString(input, KEY_TOOL_ID_SOURCE));
            boolean reuseGeneratedId = generatedId && hasText(suppliedId);
            boolean explicitId = hasText(suppliedId) && !generatedId;
            String id = explicitId || reuseGeneratedId
                    ? suppliedId.trim()
                    : onlyPendingToolUseId(pendingCandidates);
            if (!hasText(id)) {
                id = generatedResultIdentity(input, ++generatedResultSequence);
            }

            boolean duplicate = !completedResultIds.add(id);
            boolean known = toolUseIds.contains(id);
            if (known) {
                orphanResultIds.remove(id);
            } else if (!duplicate) {
                orphanResultIds.add(id);
            }
            String content = contentString(input.get(CommonConstants.JSON_KEY_CONTENT));
            if (content == null) {
                content = contentString(input.get(CommonConstants.JSON_KEY_RESULT));
            }
            boolean isError = booleanValue(input.get(CommonConstants.JSON_KEY_IS_ERROR))
                    || booleanValue(input.get("isError"));

            JsonObject block = input.deepCopy();
            block.addProperty(CommonConstants.JSON_KEY_TYPE, CommonConstants.BLOCK_TYPE_TOOL_RESULT);
            block.addProperty(CommonConstants.JSON_KEY_TOOL_USE_ID, id);
            block.addProperty(CommonConstants.JSON_KEY_IS_ERROR, isError);
            block.addProperty(CommonConstants.JSON_KEY_CONTENT, content == null ? "" : content);
            block.addProperty(KEY_TOOL_ID_SOURCE, explicitId
                    ? MessageBlockToolIdSource.EXPLICIT.value()
                    : MessageBlockToolIdSource.GENERATED.value());
            block.addProperty(KEY_PAIRED, known);
            block.addProperty(KEY_TOOL_STATUS, duplicate
                    ? MessageBlockToolStatus.DUPLICATE.value()
                    : known
                            ? MessageBlockToolStatus.COMPLETED.value()
                            : MessageBlockToolStatus.ORPHANED.value());
            publishDiagnostics();
            return block;
        }

        /** Mark the current turn/replay terminal; synchronization applies unpaired status by ID. */
        public void markUnpairedToolUses() {
            finalized = true;
            publishDiagnostics();
        }

        /** Apply ledger state to the authoritative content array of an envelope. */
        public void synchronizeEnvelope(JsonObject envelope) {
            JsonObject owner = authoritativeContentOwner(envelope);
            if (owner == null) {
                return;
            }
            JsonArray content = owner.getAsJsonArray(CommonConstants.JSON_KEY_CONTENT);
            for (JsonElement element : content) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject block = element.getAsJsonObject();
                String type = stringValue(block.get(CommonConstants.JSON_KEY_TYPE));
                if (CommonConstants.BLOCK_TYPE_TOOL_USE.equals(type)) {
                    String id = firstString(block, CommonConstants.JSON_KEY_ID,
                            CommonConstants.JSON_KEY_TOOL_USE_ID);
                    if (hasText(id)) {
                        applyToolUseStatus(block, id);
                    }
                } else if (CommonConstants.BLOCK_TYPE_TOOL_RESULT.equals(type)) {
                    String id = toolResultId(block);
                    if (hasText(id)) {
                        applyToolResultStatus(block, id);
                    }
                }
            }
        }

        /** Synchronize the actual session message raws after mergers/deep copies. */
        public void synchronizeMessages(Collection<ClaudeSession.Message> messages) {
            if (messages == null) {
                return;
            }
            for (ClaudeSession.Message message : messages) {
                if (message != null && message.raw != null) {
                    synchronizeEnvelope(message.raw);
                }
            }
        }

        public Set<String> toolUseIds() {
            return Collections.unmodifiableSet(toolUseIds);
        }

        public ToolDiagnostics diagnostics() {
            int pendingToolCalls = 0;
            if (!finalized) {
                for (String id : toolUseIds) {
                    if (!completedResultIds.contains(id)) {
                        pendingToolCalls++;
                    }
                }
            }
            return new ToolDiagnostics(pendingToolCalls, orphanResultIds.size());
        }

        private void publishDiagnostics() {
            diagnosticsObserver.onDiagnostics(diagnostics());
        }

        private String onlyPendingToolUseId(Collection<String> pendingCandidates) {
            String only = null;
            if (pendingCandidates == null) {
                return null;
            }
            for (String id : pendingCandidates) {
                if (completedResultIds.contains(id)) {
                    continue;
                }
                if (only != null) {
                    return null;
                }
                only = id;
            }
            return only;
        }

        private void applyToolUseStatus(JsonObject block, String id) {
            boolean completed = completedResultIds.contains(id);
            MessageBlockToolStatus status = completed
                    ? MessageBlockToolStatus.COMPLETED
                    : finalized
                            ? MessageBlockToolStatus.UNPAIRED
                            : MessageBlockToolStatus.PENDING;
            block.addProperty(KEY_TOOL_STATUS, status.value());
            block.addProperty(KEY_PAIRED, completed);
        }

        private void applyToolResultStatus(JsonObject block, String id) {
            boolean paired = toolUseIds.contains(id);
            boolean duplicate = MessageBlockToolStatus.DUPLICATE.value().equals(
                    firstString(block, KEY_TOOL_STATUS));
            MessageBlockToolStatus status = duplicate
                    ? MessageBlockToolStatus.DUPLICATE
                    : paired
                            ? MessageBlockToolStatus.COMPLETED
                            : MessageBlockToolStatus.ORPHANED;
            block.addProperty(KEY_TOOL_STATUS, status.value());
            block.addProperty(KEY_PAIRED, paired);
        }
    }

    public static JsonObject normalizeToolUse(JsonObject source, String identitySeed) {
        return new ToolLedger().normalizeToolUse(source, identitySeed);
    }

    public static JsonObject normalizeToolResult(JsonObject source, Collection<String> knownToolUseIds) {
        ToolLedger ledger = new ToolLedger();
        if (knownToolUseIds != null) {
            ledger.toolUseIds.addAll(knownToolUseIds);
        }
        return ledger.normalizeToolResult(source);
    }

    /** Normalize all tool blocks in the single authoritative content array of a live envelope. */
    public static JsonObject normalizeEnvelope(JsonObject envelope, ToolLedger ledger) {
        if (envelope == null) {
            return null;
        }
        ToolLedger activeLedger = ledger == null ? new ToolLedger() : ledger;
        JsonObject copy = envelope.deepCopy();
        JsonObject owner = authoritativeContentOwner(copy);
        if (owner != null) {
            normalizeContentToolUses(owner, activeLedger, "live");
            normalizeContentToolResults(owner, activeLedger);
            activeLedger.synchronizeEnvelope(copy);
        }
        return copy;
    }

    /**
     * Normalize a completed history list. The first pass registers every tool
     * call, so an explicitly identified result that appears earlier in a file
     * can still pair. The second pass handles results; EOF then marks unresolved
     * calls unpaired. Missing result IDs pair only when exactly one previously
     * encountered call remains pending, otherwise they stay explicitly orphaned.
     */
    public static List<JsonObject> normalizeHistoryMessages(List<JsonObject> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<JsonObject> normalized = new ArrayList<>(messages.size());
        ToolLedger ledger = new ToolLedger();
        int messageIndex = 0;
        for (JsonObject message : messages) {
            if (message == null) {
                continue;
            }
            JsonObject copy = message.deepCopy();
            JsonObject owner = authoritativeContentOwner(copy);
            if (owner != null) {
                normalizeContentToolUses(owner, ledger, "history-" + messageIndex);
            }
            normalized.add(copy);
            messageIndex++;
        }
        Set<String> encounteredToolUseIds = new LinkedHashSet<>();
        for (JsonObject message : normalized) {
            JsonObject owner = authoritativeContentOwner(message);
            if (owner != null) {
                normalizeHistoryContent(owner, ledger, encounteredToolUseIds);
            }
        }
        ledger.markUnpairedToolUses();
        for (JsonObject message : normalized) {
            ledger.synchronizeEnvelope(message);
        }
        return normalized;
    }

    private static String toolResultId(JsonObject block) {
        return firstString(block, CommonConstants.JSON_KEY_TOOL_USE_ID,
                "toolCallId", "tool_call_id", CommonConstants.JSON_KEY_ID);
    }
    private static JsonObject authoritativeContentOwner(JsonObject envelope) {
        if (envelope == null) {
            return null;
        }
        JsonObject raw = objectValue(envelope.get(CommonConstants.JSON_KEY_RAW));
        JsonObject owner = contentOwnerWithin(raw);
        if (owner != null) {
            return owner;
        }
        JsonObject message = objectValue(envelope.get(CommonConstants.JSON_KEY_MESSAGE));
        owner = contentOwnerWithin(message);
        if (owner != null) {
            return owner;
        }
        return hasContentArray(envelope) ? envelope : null;
    }

    private static JsonObject contentOwnerWithin(JsonObject object) {
        if (object == null) {
            return null;
        }
        if (hasContentArray(object)) {
            return object;
        }
        JsonObject message = objectValue(object.get(CommonConstants.JSON_KEY_MESSAGE));
        return hasContentArray(message) ? message : null;
    }

    private static boolean hasContentArray(JsonObject object) {
        return object != null
                && object.has(CommonConstants.JSON_KEY_CONTENT)
                && object.get(CommonConstants.JSON_KEY_CONTENT).isJsonArray();
    }

    private static JsonObject objectValue(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static void normalizeContentToolUses(JsonObject object, ToolLedger ledger, String seed) {
        JsonArray array = object.getAsJsonArray(CommonConstants.JSON_KEY_CONTENT);
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject block = element.getAsJsonObject();
            if (CommonConstants.BLOCK_TYPE_TOOL_USE.equals(
                    stringValue(block.get(CommonConstants.JSON_KEY_TYPE)))) {
                array.set(i, ledger.normalizeToolUse(block, seed + "-" + i));
            }
        }
    }

    private static void normalizeContentToolResults(JsonObject object, ToolLedger ledger) {
        JsonArray array = object.getAsJsonArray(CommonConstants.JSON_KEY_CONTENT);
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject block = element.getAsJsonObject();
            if (CommonConstants.BLOCK_TYPE_TOOL_RESULT.equals(
                    stringValue(block.get(CommonConstants.JSON_KEY_TYPE)))) {
                array.set(i, ledger.normalizeToolResult(block));
            }
        }
    }

    private static void normalizeHistoryContent(
            JsonObject object,
            ToolLedger ledger,
            Set<String> encounteredToolUseIds
    ) {
        JsonArray array = object.getAsJsonArray(CommonConstants.JSON_KEY_CONTENT);
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject block = element.getAsJsonObject();
            String type = stringValue(block.get(CommonConstants.JSON_KEY_TYPE));
            if (CommonConstants.BLOCK_TYPE_TOOL_USE.equals(type)) {
                String id = firstString(block, CommonConstants.JSON_KEY_ID,
                        CommonConstants.JSON_KEY_TOOL_USE_ID);
                if (hasText(id)) {
                    encounteredToolUseIds.add(id);
                }
            } else if (CommonConstants.BLOCK_TYPE_TOOL_RESULT.equals(type)) {
                array.set(i, ledger.normalizeToolResult(block, encounteredToolUseIds));
            }
        }
    }

    private static String generatedIdentity(String prefix, String seed, String name, JsonObject input) {
        String material = String.valueOf(seed) + "|" + String.valueOf(name) + "|" + input;
        return prefix + sha256(material).substring(0, 16);
    }

    private static String generatedResultIdentity(JsonObject input, int sequence) {
        String material = sequence + "|" + (input == null ? "" : input.toString());
        return GENERATED_RESULT_PREFIX + sha256(material).substring(0, 16);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String firstString(JsonObject object, String... keys) {
        for (String key : keys) {
            String value = stringValue(object.get(key));
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static JsonElement firstElement(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value != null && !value.isJsonNull()) {
                return value;
            }
        }
        return null;
    }

    private static JsonObject asObject(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return new JsonObject();
        }
        if (value.isJsonObject()) {
            return value.getAsJsonObject().deepCopy();
        }
        JsonObject wrapped = new JsonObject();
        wrapped.add(CommonConstants.JSON_KEY_VALUE, value.deepCopy());
        return wrapped;
    }

    private static String contentString(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    private static boolean booleanValue(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    private static String stringValue(JsonElement value) {
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
