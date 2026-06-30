package com.github.claudecodegui.service;

/**
 * 模型列表拉取失败(网络/HTTP/解析错误)。
 *
 * <p>由 {@link ModelFetchService#fetchModels} 抛出,handler 捕获后转 RPC 错误响应回前端,
 * 前端在引导对话框展示并允许用户改用手动输入模型名。
 */
public class ModelFetchException extends Exception {

    public ModelFetchException(String message) {
        super(message);
    }

    public ModelFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
