package com.github.claudecodegui.protocol;

import java.util.Arrays;
import java.util.Optional;

/**
 * MCP 服务器传输类型业务枚举(SSOT)。
 *
 * <p>MCP 服务器连接类型的唯一权威定义。此前 stdio/http/sse 字面量散落四处:
 * 前端 {@code types/mcp.ts} 手写联合类型与各 Dialog、Java {@code CommonConstants.MCP_TRANSPORT_*}
 * 与 {@code McpGatewayConstants.TRANSPORT_*} 双表、ai-bridge mcp-status 分派(另接受
 * streamable-http 别名)。本枚举收敛 Java 侧字面量,前端类型经
 * {@code generate-protocol-types.mjs} 生成。
 *
 * <p>⚠️ 修改此文件后需运行 {@code gradle generateProtocol}(或 node webview/scripts/generate-protocol-types.mjs)
 * 更新前端类型。
 */
public enum McpTransportType implements ProtocolValue {

    STDIO("stdio"),
    HTTP("http"),
    SSE("sse");

    private final String value;

    McpTransportType(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static Optional<McpTransportType> fromValue(String value) {
        return Arrays.stream(values()).filter(type -> type.value.equals(value)).findFirst();
    }
}
