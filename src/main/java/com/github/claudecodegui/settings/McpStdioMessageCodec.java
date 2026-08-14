package com.github.claudecodegui.settings;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * MCP STDIO message framing used by the Codex status probe.
 *
 * <p>Current MCP STDIO servers use one JSON-RPC object per UTF-8 line. Reading
 * remains compatible with the older {@code Content-Length} framing so existing
 * custom servers do not regress.</p>
 */
final class McpStdioMessageCodec {
    private static final String CONTENT_LENGTH_HEADER = "content-length";

    private McpStdioMessageCodec() {
    }

    static void writeNdjson(OutputStream outputStream, String jsonPayload) throws IOException {
        outputStream.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
        outputStream.write('\n');
        outputStream.flush();
    }

    static JsonObject read(InputStream inputStream) throws IOException {
        String firstLine;
        do {
            firstLine = readUtf8Line(inputStream);
        } while (firstLine != null && firstLine.isBlank());

        if (firstLine == null) {
            return null;
        }

        String trimmedFirstLine = stripBom(firstLine).trim();
        if (trimmedFirstLine.startsWith("{")) {
            return parseObject(trimmedFirstLine);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        String line = firstLine;
        while (line != null) {
            if (line.isEmpty()) {
                break;
            }

            int separatorIndex = line.indexOf(':');
            if (separatorIndex > 0) {
                String name = line.substring(0, separatorIndex).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(separatorIndex + 1).trim();
                headers.put(name, value);
            }
            line = readUtf8Line(inputStream);
        }

        String contentLengthValue = headers.get(CONTENT_LENGTH_HEADER);
        if (contentLengthValue == null || contentLengthValue.isEmpty()) {
            return null;
        }

        int contentLength;
        try {
            contentLength = Integer.parseInt(contentLengthValue);
        } catch (NumberFormatException e) {
            return null;
        }
        if (contentLength < 0) {
            return null;
        }

        byte[] payload = inputStream.readNBytes(contentLength);
        if (payload.length != contentLength) {
            return null;
        }
        return parseObject(new String(payload, StandardCharsets.UTF_8));
    }

    private static JsonObject parseObject(String payload) {
        try {
            return JsonParser.parseString(payload).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripBom(String value) {
        return value != null && !value.isEmpty() && value.charAt(0) == '\uFEFF'
                ? value.substring(1)
                : value;
    }

    private static String readUtf8Line(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int current;
        boolean sawAnyByte = false;
        while ((current = inputStream.read()) != -1) {
            sawAnyByte = true;
            if (current == '\n') {
                break;
            }
            if (current != '\r') {
                buffer.write(current);
            }
        }
        if (!sawAnyByte && buffer.size() == 0) {
            return null;
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
