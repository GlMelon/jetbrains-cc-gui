package com.github.claudecodegui.provider.common;

import java.util.ArrayList;
import java.util.List;

/**
 * SDK response result.
 * Contains the outcome of an AI provider operation.
 */
public class SDKResult {
    public boolean success;
    public boolean interrupted;
    public String error;
    public int messageCount;
    public List<Object> messages;
    public String rawOutput;
    public String finalResult;

    public SDKResult() {
        this.messages = new ArrayList<>();
    }

    /**
     * Create a successful result.
     */
    public static SDKResult success(String finalResult) {
        SDKResult result = new SDKResult();
        result.success = true;
        result.finalResult = finalResult;
        return result;
    }

    /**
     * Create a failed result.
     */
    public static SDKResult error(String errorMessage) {
        SDKResult result = new SDKResult();
        result.success = false;
        result.error = errorMessage;
        return result;
    }

    /**
     * 构造一个带完整状态的结果,供需要显式设置 interrupted/finalResult/error 的调用方
     * (如 CLI 适配层)使用,避免直接改写 public 字段。
     */
    public static SDKResult completed(boolean success, String finalResult, String error, boolean interrupted) {
        SDKResult result = new SDKResult();
        result.success = success;
        result.finalResult = finalResult;
        result.error = error;
        result.interrupted = interrupted;
        return result;
    }
}
