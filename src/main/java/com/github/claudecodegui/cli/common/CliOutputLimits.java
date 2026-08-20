package com.github.claudecodegui.cli.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * CLI 输出的有界缓冲工具，防止异常 provider 输出无限占用堆内存。
 */
public final class CliOutputLimits {

    /** 单个 stdout 行的最大字节数。超出后停止继续累积并终止本次 drain。 */
    public static final int MAX_LINE_BYTES = 4 * 1024 * 1024;
    /** 单轮 assistant 文本最大字符数。 */
    public static final int MAX_ASSISTANT_CHARS = 8 * 1024 * 1024;
    /** 单轮诊断文本最大字符数。 */
    public static final int MAX_DIAGNOSTIC_CHARS = 32 * 1024;
    /** 单个尚未判定为正文/工具跟踪文本块的最大字符数。 */
    public static final int MAX_PENDING_TEXT_BLOCK_CHARS = 64 * 1024;
    /** 单轮 reasoning 文本最大字符数。 */
    public static final int MAX_REASONING_CHARS = 2 * 1024 * 1024;
    /** 流式节流器在调度线程暂时无法 flush 时允许积压的最大字符数。 */
    public static final int MAX_PENDING_DELTA_CHARS = 256 * 1024;
    /** 单个 raw JSON 消息允许保留的字符串字符总量。 */
    public static final int MAX_RAW_JSON_CHARS = 16 * 1024 * 1024;
    /** raw JSON 中单个字符串字段允许保留的最大字符数。 */
    public static final int MAX_RAW_STRING_CHARS = 512 * 1024;
    /** raw JSON 单个消息允许保留的最大节点数量。 */
    public static final int MAX_RAW_JSON_NODES = 20_000;
    /** raw JSON 递归复制的最大深度。 */
    public static final int MAX_RAW_JSON_DEPTH = 32;

    private CliOutputLimits() {
    }

    /**
     * 向目标缓冲追加不超过上限的文本，并返回实际追加的文本。
     */
    public static String appendBounded(StringBuilder target, String text, int maxChars) {
        if (target == null || text == null || text.isEmpty() || maxChars <= target.length()) {
            return "";
        }
        int remaining = maxChars - target.length();
        String accepted = text.length() <= remaining ? text : text.substring(0, remaining);
        target.append(accepted);
        return accepted;
    }

    /**
     * Copy a JSON tree with structural and string limits. The returned tree
     * remains valid JSON while protecting event sinks from oversized raw
     * provider payloads.
     */
    public static JsonElement boundedJsonCopy(JsonElement source) {
        RawJsonBudget budget = new RawJsonBudget(MAX_RAW_JSON_CHARS, MAX_RAW_JSON_NODES);
        return boundedJsonCopy(source, budget, 0);
    }

    /**
     * Copy a JSON object with the same limits as {@link #boundedJsonCopy(JsonElement)}.
     */
    public static JsonObject boundedJsonObjectCopy(JsonObject source) {
        JsonElement copy = boundedJsonCopy(source);
        return copy != null && copy.isJsonObject() ? copy.getAsJsonObject() : new JsonObject();
    }

    /**
     * Serialize a bounded JSON object for a wire event.
     */
    public static String boundedJsonString(JsonElement source) {
        return boundedJsonCopy(source).toString();
    }

    private static JsonElement boundedJsonCopy(JsonElement source, RawJsonBudget budget, int depth) {
        if (source == null || source.isJsonNull()) {
            return JsonNull.INSTANCE;
        }
        if (depth > MAX_RAW_JSON_DEPTH || !budget.consumeNode()) {
            return JsonNull.INSTANCE;
        }

        if (source.isJsonPrimitive()) {
            JsonPrimitive primitive = source.getAsJsonPrimitive();
            if (!primitive.isString()) {
                return primitive.deepCopy();
            }
            String value = primitive.getAsString();
            int acceptedLength = budget.acceptString(value.length());
            return new JsonPrimitive(value.substring(0, acceptedLength));
        }

        if (source.isJsonArray()) {
            JsonArray copy = new JsonArray();
            for (JsonElement element : source.getAsJsonArray()) {
                if (!budget.hasNodesRemaining()) {
                    break;
                }
                copy.add(boundedJsonCopy(element, budget, depth + 1));
            }
            return copy;
        }

        if (source.isJsonObject()) {
            JsonObject copy = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : source.getAsJsonObject().entrySet()) {
                if (!budget.hasNodesRemaining()) {
                    break;
                }
                copy.add(entry.getKey(), boundedJsonCopy(entry.getValue(), budget, depth + 1));
            }
            return copy;
        }

        return JsonNull.INSTANCE;
    }

    private static final class RawJsonBudget {
        private int remainingChars;
        private int remainingNodes;

        private RawJsonBudget(int maxChars, int maxNodes) {
            this.remainingChars = Math.max(0, maxChars);
            this.remainingNodes = Math.max(0, maxNodes);
        }

        private boolean consumeNode() {
            if (remainingNodes <= 0) {
                return false;
            }
            remainingNodes--;
            return true;
        }

        private boolean hasNodesRemaining() {
            return remainingNodes > 0;
        }

        private int acceptString(int requestedLength) {
            int accepted = Math.min(
                    Math.min(requestedLength, MAX_RAW_STRING_CHARS),
                    remainingChars);
            remainingChars -= accepted;
            return accepted;
        }
    }

    /**
     * 有界的单行字节缓冲。超过上限后继续消费输入但不再扩容，避免管道线程被超长行拖垮。
     */
    public static final class LineBuffer extends ByteArrayOutputStream {
        private boolean truncated;

        public LineBuffer() {
            super(Math.min(MAX_LINE_BYTES, 8192));
        }

        @Override
        public void write(int value) {
            if (count >= MAX_LINE_BYTES) {
                truncated = true;
                return;
            }
            super.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            if (bytes == null || length <= 0) {
                return;
            }
            int remaining = MAX_LINE_BYTES - count;
            if (remaining <= 0) {
                truncated = true;
                return;
            }
            int accepted = Math.min(remaining, length);
            super.write(bytes, offset, accepted);
            if (accepted < length) {
                truncated = true;
            }
        }

        public boolean isTruncated() {
            return truncated;
        }

        @Override
        public void reset() {
            super.reset();
            truncated = false;
        }
    }

    /**
     * 逐行读取 CLI stdout，并对单行设置硬上限。
     * <p>不能使用 {@code BufferedReader.readLine()}：异常 provider 可能不发送换行，
     * 该 API 会先把整行扩容到堆上。此读取器超过上限后仍继续消费到换行，
     * 但只保留前 {@link #MAX_LINE_BYTES} 个字节，调用方可据此终止失控进程。
     */
    public static final class BoundedLineReader implements AutoCloseable {
        private final InputStream input;
        private final byte[] readBuffer = new byte[8192];
        private final LineBuffer lineBuffer = new LineBuffer();
        private int offset;
        private int length;
        private boolean lastLineTruncated;

        public BoundedLineReader(InputStream input) {
            this.input = input;
        }

        public String readLine() throws IOException {
            lineBuffer.reset();
            lastLineTruncated = false;
            boolean sawAnyByte = false;
            while (true) {
                int value = readByte();
                if (value < 0) {
                    if (!sawAnyByte) {
                        return null;
                    }
                    break;
                }
                sawAnyByte = true;
                if (value == '\n') {
                    break;
                }
                lineBuffer.write(value);
            }
            lastLineTruncated = lineBuffer.isTruncated();
            byte[] bytes = lineBuffer.toByteArray();
            int length = bytes.length;
            if (length > 0 && bytes[length - 1] == '\r') {
                length--;
            }
            return new String(bytes, 0, length, StandardCharsets.UTF_8);
        }

        public boolean lastLineTruncated() {
            return lastLineTruncated;
        }

        private int readByte() throws IOException {
            if (offset >= length) {
                length = input.read(readBuffer);
                offset = 0;
                if (length < 0) {
                    return -1;
                }
            }
            return readBuffer[offset++] & 0xff;
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }
}
