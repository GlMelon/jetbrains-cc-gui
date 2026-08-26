package com.github.claudecodegui.provider.common;

import java.util.ArrayList;
import java.util.List;

/**
 * AI provider operation result (DTO consumed by the CLI adapter layer).
 * Contains the outcome of a provider turn.
 */
public class CliResult {
    public boolean success;
    public boolean interrupted;
    public String error;
    public int messageCount;
    public List<Object> messages;
    public String rawOutput;
    public String finalResult;

    public CliResult() {
        this.messages = new ArrayList<>();
    }

    /**
     * Create a successful result.
     */
    public static CliResult success(String finalResult) {
        CliResult result = new CliResult();
        result.success = true;
        result.finalResult = finalResult;
        return result;
    }

    /**
     * Create a failed result.
     */
    public static CliResult error(String errorMessage) {
        CliResult result = new CliResult();
        result.success = false;
        result.error = errorMessage;
        return result;
    }

    /**
     * 构造一个带完整状态的结果,供需要显式设置 interrupted/finalResult/error 的调用方
     * (如 CLI 适配层)使用,避免直接改写 public 字段。
     */
    public static CliResult completed(boolean success, String finalResult, String error, boolean interrupted) {
        CliResult result = new CliResult();
        result.success = success;
        result.finalResult = finalResult;
        result.error = error;
        result.interrupted = interrupted;
        return result;
    }
}
