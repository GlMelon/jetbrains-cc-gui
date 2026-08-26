package com.github.claudecodegui.config;

/**
 * 单个 provider 的路由策略。
 * <p>
 * SDK 调用模式已移除,runtime 维度已消除——策略仅剩「是否启用」一维。
 * 存量 config.json 中的 legacy {@code supported}/{@code default} 字段由解析侧忽略
 * (见 {@code CodemossSettingsService.parseRuntimePolicy})。
 *
 * @param enabled 是否启用此 provider
 */
public record ProviderRuntimePolicy(boolean enabled) {
}
