package com.github.claudecodegui.protocol;

import java.util.Arrays;
import java.util.Optional;

/**
 * MCP Gateway 运行状态业务枚举(SSOT)。
 *
 * <p>生产者在 ai-bridge {@code mcp-gateway/server-supervisor.js}(跨进程裸字符串上报),
 * Java 侧 {@code McpGatewayConstants.STATE_*} 消费(值改引本枚举),并经
 * {@code OpenCodeMcpServerActionHandlers.mapGatewayState} 映射为 MCP 服务器状态后下发前端。
 * 值保持大写,与 ai-bridge 上报字面量逐字对齐(ai-bridge 侧接收为跨进程协议,无共享模块,
 * 由本枚举注释锚定契约)。
 *
 * <p>⚠️ 修改此文件后需运行 {@code gradle generateProtocol} 更新前端类型。
 */
public enum McpGatewayState implements ProtocolValue {

    STARTING("STARTING"),
    READY("READY"),
    DEGRADED("DEGRADED"),
    BACKOFF("BACKOFF"),
    STOPPED("STOPPED");

    private final String value;

    McpGatewayState(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static Optional<McpGatewayState> fromValue(String value) {
        return Arrays.stream(values()).filter(state -> state.value.equals(value)).findFirst();
    }
}
