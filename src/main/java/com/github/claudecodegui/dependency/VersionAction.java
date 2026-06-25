package com.github.claudecodegui.dependency;

import com.github.claudecodegui.protocol.ProtocolValue;

import java.util.Arrays;
import java.util.Optional;

/**
 * SDK 版本安装动作枚举(A6 后端 SSOT)。
 *
 * <p>表示针对某 SDK 的版本安装/更新决策结果。后端 {@link DependencyManager#resolveVersionAction}
 * 为版本动作的唯一真相源:对每个可选版本相对已安装版本的方向(update/rollback/current)做权威判定,
 * 经 {@code dependency.versions_loaded} 下行事件的 {@code versionActions} map 下发
 * (key=可选版本号,value=该版本的动作)。前端按用户选择的版本查表渲染按钮态,
 * 消除前端 {@code getVersionAction} 决策双写与 {@code compareVersions} 算法副本。
 *
 * <p>值域 4 值:
 * <ul>
 *   <li>{@code INSTALL} —— SDK 未安装(由 installed 布尔决定,不进 versionActions map)</li>
 *   <li>{@code UPDATE} —— 已安装版本低于目标版本</li>
 *   <li>{@code ROLLBACK} —— 已安装版本高于目标版本(原前端独有语义,后端 checkForUpdates 曾误报为「无更新」)</li>
 *   <li>{@code CURRENT} —— 已安装版本与目标版本相同</li>
 * </ul>
 *
 * <p>范式对齐 {@code PermissionMode}/{@code ReasoningEffort}/{@code ProviderType}:
 * {@link ProtocolValue} 协议值出口,经 {@code generate-protocol-types.mjs} 生成前端
 * {@code VERSION_ACTION} 常量 + {@code VersionAction} 类型(三端值域守门)。
 *
 * <p>⚠️ 修改此文件后需重新构建前端(npm run build 重新生成 protocol.ts)。
 */
public enum VersionAction implements ProtocolValue {

    INSTALL("install"),
    UPDATE("update"),
    ROLLBACK("rollback"),
    CURRENT("current"),
    ;

    private final String value;

    VersionAction(String value) {
        this.value = value;
    }

    /** 协议线上实际传输的字符串值(SSOT),由生成器反射/regex 消费。 */
    @Override
    public String value() {
        return value;
    }

    /**
     * SSOT 严格往返:值不匹配返回 {@link Optional#empty()}。
     * 范式对齐 {@code PermissionMode#fromValue}/{@code ReasoningEffort#fromValue}。
     */
    public static Optional<VersionAction> fromValue(String value) {
        return Arrays.stream(values()).filter(a -> a.value.equals(value)).findFirst();
    }
}
