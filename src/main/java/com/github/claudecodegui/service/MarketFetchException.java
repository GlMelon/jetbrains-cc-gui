package com.github.claudecodegui.service;

import com.github.claudecodegui.protocol.McpMarketErrorCode;

/**
 * MCP 市场(Smithery Registry)拉取失败(网络/HTTP/认证/解析错误)。
 *
 * <p>由 {@link SmitheryMarketService} 抛出,handler 捕获后据 {@link #getErrorCode()}
 * 转对应 i18n 文案回前端(见 {@link MarketFetchException#MISSING_API_KEY} 等常量)。
 * 仿 {@link ModelFetchException},但额外携带稳定 errorCode 供前端精准引导。
 * errorCode 字面量 SSOT 在 {@link McpMarketErrorCode}(前端类型经生成管线同步)。
 */
public class MarketFetchException extends Exception {

    /** API Key 未配置(空)→ 前端引导去配置入口填写 */
    public static final String MISSING_API_KEY = McpMarketErrorCode.MISSING_API_KEY.value();
    /** API Key 无效/未授权(HTTP 401/403)→ 前端提示 key 无效 */
    public static final String INVALID_API_KEY = McpMarketErrorCode.INVALID_API_KEY.value();
    /** 网络异常(连接失败/DNS 失败/未知主机)→ 前端提示网络问题 */
    public static final String NETWORK_ERROR = McpMarketErrorCode.NETWORK_ERROR.value();
    /** 请求超时(connect/request timeout)→ 前端提示超时并允许重试 */
    public static final String TIMEOUT = McpMarketErrorCode.TIMEOUT.value();
    /** 响应解析失败 → 前端提示服务端响应异常 */
    public static final String PARSE_ERROR = McpMarketErrorCode.PARSE_ERROR.value();

    private final String errorCode;

    public MarketFetchException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    public MarketFetchException(String errorCode, Throwable cause) {
        super(errorCode, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
