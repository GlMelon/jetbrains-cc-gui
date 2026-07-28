package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.protocol.HistoryExportFormat;
import com.github.claudecodegui.protocol.payload.HistoryExportPayloadField;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Builds backend-rendered history exports under the final downstream-envelope byte budget. */
final class HistoryExportPayloadBuilder {
    private static final String DEFAULT_TITLE = "session";
    private static final int MAX_FILE_TITLE_LENGTH = 50;
    private static final int SESSION_ID_FILE_PREFIX_LENGTH = 8;

    private final HistoryExportPolicy policy;
    private final HistoryExportRendererRegistry rendererRegistry;

    HistoryExportPayloadBuilder() {
        this(new HistoryExportPolicy(), new HistoryExportRendererRegistry());
    }

    HistoryExportPayloadBuilder(HistoryExportPolicy policy) {
        this(policy, new HistoryExportRendererRegistry());
    }

    HistoryExportPayloadBuilder(
            HistoryExportPolicy policy,
            HistoryExportRendererRegistry rendererRegistry
    ) {
        this.policy = policy;
        this.rendererRegistry = rendererRegistry;
    }

    HistoryExportPayload build(String sessionId, String title, HistoryMessageBatch sourceBatch) {
        return build(sessionId, title, sourceBatch, HistoryExportFormat.JSON);
    }

    HistoryExportPayload build(
            String sessionId,
            String title,
            HistoryMessageBatch sourceBatch,
            HistoryExportFormat format
    ) {
        if (format == null) {
            throw new IllegalArgumentException("History export format is required");
        }
        HistoryExportRenderer renderer = rendererRegistry.require(format);
        String safeSessionId = normalize(sessionId);
        String safeTitle = normalizeTitle(title);
        String fileName = buildFileName(safeTitle, safeSessionId, format);
        HistoryMessageBatch batch = sourceBatch == null ? HistoryMessageBatch.empty() : sourceBatch;
        List<JsonObject> messages = batch.messages();
        int totalMessageCount = batch.totalMessageCount();
        int candidateLimit = Math.min(messages.size(), policy.maxMessageCount());

        ExportCandidate emptyCandidate = buildCandidate(
                renderer, format, fileName, safeSessionId, safeTitle, messages, 0, totalMessageCount
        );
        if (emptyCandidate.utf8Bytes() > policy.maxUtf8Bytes()) {
            throw new IllegalStateException("History export metadata exceeds configured UTF-8 byte limit");
        }

        ExportCandidate best = emptyCandidate;
        int low = 1;
        int high = candidateLimit;
        while (low <= high) {
            int candidateCount = low + ((high - low) >>> 1);
            ExportCandidate candidate = buildCandidate(
                    renderer, format, fileName, safeSessionId, safeTitle,
                    messages, candidateCount, totalMessageCount
            );
            if (candidate.utf8Bytes() <= policy.maxUtf8Bytes()) {
                best = candidate;
                low = candidateCount + 1;
            } else {
                high = candidateCount - 1;
            }
        }

        int omittedMessageCount = totalMessageCount - best.exportedMessageCount();
        return new HistoryExportPayload(
                best.json(),
                omittedMessageCount > 0,
                best.exportedMessageCount(),
                omittedMessageCount,
                best.utf8Bytes()
        );
    }

    HistoryMessageReadPolicy messageReadPolicy() {
        return policy.messageReadPolicy();
    }

    String buildError(String message) {
        JsonObject payload = new JsonObject();
        payload.addProperty(HistoryExportPayloadField.SUCCESS.wireKey(), false);
        payload.addProperty(
                HistoryExportPayloadField.ERROR.wireKey(),
                message == null || message.isBlank() ? "History export failed" : message
        );
        return GsonHolder.GSON.toJson(payload);
    }

    private ExportCandidate buildCandidate(
            HistoryExportRenderer renderer,
            HistoryExportFormat format,
            String fileName,
            String sessionId,
            String title,
            List<JsonObject> messages,
            int exportedMessageCount,
            int totalMessageCount
    ) {
        HistoryExportDocument document = new HistoryExportDocument(
                sessionId,
                title,
                messages.subList(0, exportedMessageCount),
                exportedMessageCount,
                totalMessageCount,
                policy
        );
        String content = renderer.render(document);
        JsonObject envelope = new JsonObject();
        envelope.addProperty(HistoryExportPayloadField.SUCCESS.wireKey(), true);
        envelope.addProperty(HistoryExportPayloadField.SESSION_ID.wireKey(), sessionId);
        envelope.addProperty(HistoryExportPayloadField.TITLE.wireKey(), title);
        envelope.addProperty(HistoryExportPayloadField.FORMAT.wireKey(), format.value());
        envelope.addProperty(HistoryExportPayloadField.FILE_NAME.wireKey(), fileName);
        envelope.addProperty(HistoryExportPayloadField.MIME_TYPE.wireKey(), format.mimeType());
        envelope.addProperty(HistoryExportPayloadField.CONTENT.wireKey(), content);
        envelope.addProperty(HistoryExportPayloadField.TRUNCATED.wireKey(), document.truncated());
        envelope.addProperty(HistoryExportPayloadField.EXPORTED_MESSAGE_COUNT.wireKey(), exportedMessageCount);
        envelope.addProperty(HistoryExportPayloadField.OMITTED_MESSAGE_COUNT.wireKey(), document.omittedMessageCount());
        envelope.addProperty(HistoryExportPayloadField.MAX_MESSAGE_COUNT.wireKey(), policy.maxMessageCount());
        envelope.addProperty(HistoryExportPayloadField.MAX_UTF8_BYTES.wireKey(), policy.maxUtf8Bytes());
        String json = GsonHolder.GSON.toJson(envelope);
        return new ExportCandidate(json, exportedMessageCount, utf8Length(json));
    }

    private static String buildFileName(String title, String sessionId, HistoryExportFormat format) {
        StringBuilder sanitized = new StringBuilder(title.length());
        for (int index = 0; index < title.length(); index++) {
            char current = title.charAt(index);
            sanitized.append(isInvalidFileNameCharacter(current) || Character.isWhitespace(current) ? '_' : current);
        }
        String sanitizedTitle = sanitized.toString();
        if (sanitizedTitle.isBlank()) {
            sanitizedTitle = DEFAULT_TITLE;
        }
        if (sanitizedTitle.length() > MAX_FILE_TITLE_LENGTH) {
            sanitizedTitle = sanitizedTitle.substring(0, MAX_FILE_TITLE_LENGTH);
        }
        String idPrefix = sessionId.substring(0, Math.min(SESSION_ID_FILE_PREFIX_LENGTH, sessionId.length()));
        return (idPrefix.isBlank() ? sanitizedTitle : sanitizedTitle + "_" + idPrefix) + format.fileExtension();
    }

    private static boolean isInvalidFileNameCharacter(char value) {
        return value == '<' || value == '>' || value == ':' || value == '"'
                || value == '/' || value == '\\' || value == '|' || value == '?' || value == '*';
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeTitle(String title) {
        String normalized = normalize(title);
        return normalized.isBlank() ? DEFAULT_TITLE : normalized;
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private record ExportCandidate(String json, int exportedMessageCount, int utf8Bytes) {
    }
}
