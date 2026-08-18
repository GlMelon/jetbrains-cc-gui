package com.github.claudecodegui.settings;

import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class McpStdioMessageCodecTest {

    private static final String INITIALIZE_RESPONSE =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2024-11-05\"}}";

    @Test
    public void writeNdjsonWritesOneUtf8JsonRpcObjectPerLine() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        McpStdioMessageCodec.writeNdjson(output, INITIALIZE_RESPONSE);

        Assert.assertEquals(INITIALIZE_RESPONSE + "\n", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void readAcceptsNdjsonResponse() throws Exception {
        JsonObject response = McpStdioMessageCodec.read(input(INITIALIZE_RESPONSE + "\n"));

        Assert.assertNotNull(response);
        Assert.assertEquals(1, response.get("id").getAsInt());
        Assert.assertEquals("2024-11-05",
                response.getAsJsonObject("result").get("protocolVersion").getAsString());
    }

    @Test
    public void readRemainsCompatibleWithContentLengthFraming() throws Exception {
        String payload = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"message\":\"连接成功\"}}";
        int byteLength = payload.getBytes(StandardCharsets.UTF_8).length;
        String framed = "Content-Length: " + byteLength + "\r\n\r\n" + payload;

        JsonObject response = McpStdioMessageCodec.read(input(framed));

        Assert.assertNotNull(response);
        Assert.assertEquals("连接成功", response.getAsJsonObject("result").get("message").getAsString());
    }

    @Test
    public void readReturnsNullForEmptyInvalidOrTruncatedFrames() throws Exception {
        Assert.assertNull(McpStdioMessageCodec.read(input("")));
        Assert.assertNull(McpStdioMessageCodec.read(input("Content-Length: invalid\r\n\r\n{}")));
        Assert.assertNull(McpStdioMessageCodec.read(input("Content-Length: 10\r\n\r\n{}")));
    }

    private static ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
