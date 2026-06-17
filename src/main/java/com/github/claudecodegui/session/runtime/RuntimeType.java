package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.common.CommonConstants;

/**
 * Runtime 维度枚举（路由键之一）。
 * 区分 SDK 模式（Node.js daemon）与 CLI 模式（一次性子进程）。
 */
public enum RuntimeType {
    SDK,
    CLI;

    /**
     * 从 invocationMode 字符串转换为 RuntimeType。
     * 兼容现有字符串常量 CommonConstants.INVOCATION_MODE_CLI / INVOCATION_MODE_SDK。
     */
    public static RuntimeType fromInvocationMode(String mode) {
        return CommonConstants.INVOCATION_MODE_CLI.equals(mode) ? CLI : SDK;
    }
}
