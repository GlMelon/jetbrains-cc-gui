package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.protocol.HistoryExportFormat;
import com.github.claudecodegui.protocol.payload.HistoryExportPayloadField;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;

/** Produces a compact JSON history document from backend-normalized messages. */
final class JsonHistoryExportRenderer implements HistoryExportRenderer {
    @Override
    public HistoryExportFormat format() {
        return HistoryExportFormat.JSON;
    }

    @Override
    public String render(HistoryExportDocument document) {
        StringBuilder json = new StringBuilder();
        json.append('{')
                .append(quoted(HistoryExportPayloadField.SESSION_ID)).append(':').append(jsonString(document.sessionId())).append(',')
                .append(quoted(HistoryExportPayloadField.TITLE)).append(':').append(jsonString(document.title())).append(',')
                .append(quoted(HistoryExportPayloadField.FORMAT)).append(':').append(jsonString(format().value())).append(',')
                .append("\"messages\":[");
        for (int index = 0; index < document.messages().size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            JsonObject message = document.messages().get(index);
            json.append(GsonHolder.GSON.toJson(message == null ? new JsonObject() : message));
        }
        json.append("],")
                .append(quoted(HistoryExportPayloadField.TRUNCATED)).append(':').append(document.truncated()).append(',')
                .append(quoted(HistoryExportPayloadField.EXPORTED_MESSAGE_COUNT)).append(':').append(document.exportedMessageCount()).append(',')
                .append(quoted(HistoryExportPayloadField.OMITTED_MESSAGE_COUNT)).append(':').append(document.omittedMessageCount()).append(',')
                .append(quoted(HistoryExportPayloadField.MAX_MESSAGE_COUNT)).append(':').append(document.policy().maxMessageCount()).append(',')
                .append(quoted(HistoryExportPayloadField.MAX_UTF8_BYTES)).append(':').append(document.policy().maxUtf8Bytes())
                .append('}');
        return json.toString();
    }

    private static String quoted(HistoryExportPayloadField field) {
        return jsonString(field.wireKey());
    }

    private static String jsonString(String value) {
        return GsonHolder.GSON.toJson(value == null ? "" : value);
    }
}
