package com.github.claudecodegui.protocol;

import java.util.Arrays;
import java.util.Optional;

/**
 * MCP 市场(Smithery Registry)拉取失败 errorCode 业务枚举(SSOT)。
 *
 * <p>由 {@code SmitheryMarketService} 产出、经 payload 透传前端精准引导
 * (mapErrorMessage / key 引导判定)。此前五个字面量在 Java
 * {@code MarketFetchException} String 常量与前端 switch 各写一份。HTTP_{code} 形态
 * (HTTP_401 等)为运行时拼接的动态值,不属本枚举。
 *
 * <p>⚠️ 修改此文件后需运行 {@code gradle generateProtocol} 更新前端类型。
 */
public enum McpMarketErrorCode implements ProtocolValue {

    /** API Key 未配置(空)→ 前端引导去配置入口填写 */
    MISSING_API_KEY("MISSING_API_KEY"),
    /** API Key 无效/未授权(HTTP 401/403)→ 前端提示 key 无效 */
    INVALID_API_KEY("INVALID_API_KEY"),
    /** 网络异常(连接失败/DNS 失败/未知主机)→ 前端提示网络问题 */
    NETWORK_ERROR("NETWORK_ERROR"),
    /** 请求超时(connect/request timeout)→ 前端提示超时并允许重试 */
    TIMEOUT("TIMEOUT"),
    /** 响应解析失败 → 前端提示服务端响应异常 */
    PARSE_ERROR("PARSE_ERROR");

    private final String value;

    McpMarketErrorCode(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static Optional<McpMarketErrorCode> fromValue(String value) {
        return Arrays.stream(values()).filter(code -> code.value.equals(value)).findFirst();
    }
}
