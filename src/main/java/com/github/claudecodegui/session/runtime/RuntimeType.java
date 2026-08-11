package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.common.CommonConstants;

/**
 * Runtime 维度枚举（路由键之一）。
 * <p>
 * SDK 调用模式(Node.js 常驻 daemon)已移除——超出「简易 AI 配置」插件定位;
 * 所有 provider 统一为 CLI(一次性子进程)。枚举保留单值 CLI 以维持类型签名稳定,
 * 未来可再决定是否彻底消除该维度。
 */
public enum RuntimeType {
    CLI;

    /**
     * 从 invocationMode 字符串转换为 RuntimeType。
     * SDK 模式移除后恒返回 CLI;保留入参仅为向后兼容既有调用点。
     */
    public static RuntimeType fromInvocationMode(String mode) {
        return CLI;
    }
}
