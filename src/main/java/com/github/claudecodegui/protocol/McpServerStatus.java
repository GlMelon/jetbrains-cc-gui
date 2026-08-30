package com.github.claudecodegui.protocol;

import java.util.Arrays;
import java.util.Optional;

/**
 * MCP 服务器连接状态业务枚举(SSOT)。
 *
 * <p>由 Claude MCP 查询(ai-bridge mcp-status verifier)与 OpenCode/Codex manager 下发,
 * 前端按值渲染状态徽标与统计。四值词表对齐 {@code CommonConstants.MCP_STATUS_*}
 * (值改引本枚举)。
 *
 * <p>⚠️ 历史遗留说明:前端曾存在第五值 {@code needs-auth},全仓(Java + ai-bridge)无任何
 * 生产者,系 SDK 时代遗留的幽灵值,已随本次收敛从前端词表删除。
 *
 * <p>⚠️ 修改此文件后需运行 {@code gradle generateProtocol} 更新前端类型。
 */
public enum McpServerStatus implements ProtocolValue {

    CONNECTED("connected"),
    FAILED("failed"),
    PENDING("pending"),
    DISABLED("disabled");

    private final String value;

    McpServerStatus(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static Optional<McpServerStatus> fromValue(String value) {
        return Arrays.stream(values()).filter(status -> status.value.equals(value)).findFirst();
    }
}
