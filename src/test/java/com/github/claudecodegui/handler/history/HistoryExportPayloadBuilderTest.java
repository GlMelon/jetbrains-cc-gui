package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.protocol.HistoryExportFormat;
import com.github.claudecodegui.protocol.payload.HistoryExportPayloadField;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HistoryExportPayloadBuilderTest {
    private static final String MESSAGES = "messages";

    @Test
    public void defaultsToJsonAndExportsEmptySessionWithinBudget() {
        HistoryExportPayload payload = builder(10, 2048).build("session-1", "Demo", HistoryMessageBatch.empty());
        JsonObject envelope = parse(payload);
        JsonObject content = parseJsonContent(envelope);

        assertTrue(envelope.get(HistoryExportPayloadField.SUCCESS.wireKey()).getAsBoolean());
        assertEquals(HistoryExportFormat.JSON.value(), envelope.get(HistoryExportPayloadField.FORMAT.wireKey()).getAsString());
        assertEquals(HistoryExportFormat.JSON.mimeType(), envelope.get(HistoryExportPayloadField.MIME_TYPE.wireKey()).getAsString());
        assertTrue(envelope.get(HistoryExportPayloadField.FILE_NAME.wireKey()).getAsString().endsWith(HistoryExportFormat.JSON.fileExtension()));
        assertEquals(0, content.getAsJsonArray(MESSAGES).size());
        assertFalse(payload.truncated());
        assertEquals(0, payload.exportedMessageCount());
        assertEquals(0, payload.omittedMessageCount());
        assertTrue(payload.utf8Bytes() <= 2048);
    }

    @Test
    public void truncatesJsonAtMessageCountLimit() {
        HistoryExportPayload payload = builder(2, 4096).build(
                "session-1",
                "Demo",
                batch(messages("one", "two", "three"))
        );
        JsonObject envelope = parse(payload);
        JsonObject content = parseJsonContent(envelope);

        assertTrue(payload.truncated());
        assertEquals(2, payload.exportedMessageCount());
        assertEquals(1, payload.omittedMessageCount());
        assertEquals(2, content.getAsJsonArray(MESSAGES).size());
        assertEquals(2, envelope.get(HistoryExportPayloadField.MAX_MESSAGE_COUNT.wireKey()).getAsInt());
    }

    @Test
    public void omitsSingleJsonMessageThatCannotFitByteBudget() {
        HistoryExportPayload payload = builder(10, 1024).build(
                "session-1",
                "Demo",
                batch(messages("x".repeat(20_000)))
        );

        assertTrue(payload.truncated());
        assertEquals(0, payload.exportedMessageCount());
        assertEquals(1, payload.omittedMessageCount());
        assertTrue(payload.utf8Bytes() <= 1024);
    }

    @Test
    public void countsUtf8BytesRatherThanJavaCharacters() {
        HistoryExportPayload payload = builder(10, 1024).build(
                "session-1",
                "Demo",
                batch(messages("汉".repeat(1_000), "tail"))
        );

        int actualBytes = payload.json().getBytes(StandardCharsets.UTF_8).length;
        assertEquals(actualBytes, payload.utf8Bytes());
        assertTrue(payload.utf8Bytes() <= 1024);
        assertTrue(payload.truncated());
    }

    @Test
    public void handlesSourceByteTruncationWithoutReadingMissingMessages() {
        HistoryExportPayload payload = builder(10, 4096).build(
                "session-1",
                "Demo",
                new HistoryMessageBatch(List.of(), 5)
        );

        assertTrue(payload.truncated());
        assertEquals(0, payload.exportedMessageCount());
        assertEquals(5, payload.omittedMessageCount());
        assertEquals(0, parseJsonContent(parse(payload)).getAsJsonArray(MESSAGES).size());
    }

    @Test
    public void combinesSourceAndPayloadTruncationIntoExactOmittedCount() {
        HistoryExportPayload payload = builder(2, 4096).build(
                "session-1",
                "Demo",
                new HistoryMessageBatch(messages("one", "two"), 5)
        );

        assertTrue(payload.truncated());
        assertEquals(2, payload.exportedMessageCount());
        assertEquals(3, payload.omittedMessageCount());
    }

    @Test
    public void rendersHtmlWithBackendOwnedMetadata() {
        HistoryExportPayload payload = builder(10, 16_384).build(
                "session-1",
                "Demo",
                batch(messages("hello")),
                HistoryExportFormat.HTML
        );
        JsonObject envelope = parse(payload);
        String content = envelope.get(HistoryExportPayloadField.CONTENT.wireKey()).getAsString();

        assertEquals(HistoryExportFormat.HTML.value(), envelope.get(HistoryExportPayloadField.FORMAT.wireKey()).getAsString());
        assertEquals(HistoryExportFormat.HTML.mimeType(), envelope.get(HistoryExportPayloadField.MIME_TYPE.wireKey()).getAsString());
        assertTrue(envelope.get(HistoryExportPayloadField.FILE_NAME.wireKey()).getAsString().endsWith(HistoryExportFormat.HTML.fileExtension()));
        assertTrue(content.startsWith("<!doctype html>"));
        assertTrue(content.contains("hello"));
        assertFalse(payload.truncated());
        assertEquals(1, payload.exportedMessageCount());
        assertTrue(payload.utf8Bytes() <= 16_384);
    }

    @Test
    public void combinesHtmlPayloadAndSourceOmissionsUnderFinalEnvelopeBudget() {
        HistoryExportPayload payload = builder(10, 8192).build(
                "session-1",
                "Demo",
                new HistoryMessageBatch(messages("x".repeat(20_000), "tail"), 5),
                HistoryExportFormat.HTML
        );

        assertTrue(payload.truncated());
        assertEquals(0, payload.exportedMessageCount());
        assertEquals(5, payload.omittedMessageCount());
        assertTrue(payload.utf8Bytes() <= 8192);
    }

    @Test
    public void producesBackendOwnedSafeFilenameForEachFormat() {
        HistoryExportPayload jsonPayload = builder(10, 2048).build(
                "1234567890",
                "bad:/ title?*",
                HistoryMessageBatch.empty()
        );
        HistoryExportPayload htmlPayload = builder(10, 8192).build(
                "1234567890",
                "bad:/ title?*",
                HistoryMessageBatch.empty(),
                HistoryExportFormat.HTML
        );

        assertEquals(
                "bad___title___12345678.json",
                parse(jsonPayload).get(HistoryExportPayloadField.FILE_NAME.wireKey()).getAsString()
        );
        assertEquals(
                "bad___title___12345678.html",
                parse(htmlPayload).get(HistoryExportPayloadField.FILE_NAME.wireKey()).getAsString()
        );
    }

    @Test
    public void errorPayloadUsesDeclaredWireFields() {
        String raw = builder(10, 1024).buildError("boom");
        JsonObject json = JsonParser.parseString(raw).getAsJsonObject();

        assertFalse(json.get(HistoryExportPayloadField.SUCCESS.wireKey()).getAsBoolean());
        assertEquals("boom", json.get(HistoryExportPayloadField.ERROR.wireKey()).getAsString());
    }

    private static HistoryExportPayloadBuilder builder(int maxMessages, int maxBytes) {
        return new HistoryExportPayloadBuilder(new HistoryExportPolicy(maxMessages, maxBytes));
    }

    private static HistoryMessageBatch batch(List<JsonObject> messages) {
        return new HistoryMessageBatch(messages, messages.size());
    }

    private static List<JsonObject> messages(String... contents) {
        List<JsonObject> messages = new ArrayList<>();
        for (String content : contents) {
            JsonObject message = new JsonObject();
            message.addProperty("content", content);
            messages.add(message);
        }
        return messages;
    }

    private static JsonObject parse(HistoryExportPayload payload) {
        return JsonParser.parseString(payload.json()).getAsJsonObject();
    }

    private static JsonObject parseJsonContent(JsonObject envelope) {
        return JsonParser.parseString(
                envelope.get(HistoryExportPayloadField.CONTENT.wireKey()).getAsString()
        ).getAsJsonObject();
    }
}
