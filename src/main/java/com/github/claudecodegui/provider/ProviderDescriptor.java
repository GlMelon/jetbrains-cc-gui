package com.github.claudecodegui.provider;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.session.runtime.RuntimeType;
import com.github.claudecodegui.util.PlatformUtils;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Provider 描述符 —— F1 / S4-1C+「配置驱动 Provider 扩展」的地基。
 *
 * <p>描述一个 Provider 的可配置元信息:协议 id、显示名、CLI 命令、声明能力、支持的 runtime。
 * 与编译时固定的 {@link ProviderType} 枚举正交:{@code ProviderType} 是协议线上 provider 值的
 * SSOT(三内置 Provider),而本描述符支持<b>运行时</b>通过配置扩展 Provider 集合(自定义 Provider),
 * 无需第三方 JVM 代码 / 沙箱(JDK17 SecurityManager 已废弃,见 S4-1C+ 决策)。
 *
 * <p>内置三 Provider 默认值复用 {@link ProviderType}(cliCommand / displayLabel SSOT) +
 * {@link ProviderCapability}(全能力声明),保证与既有装配行为等价;自定义 Provider 从配置 JSON 加载,
 * 经 {@link ProviderDescriptorRegistry} 聚合后供通用 SessionRuntime(由 CLI 命令模板驱动)消费,
 * 实现「添加 Provider 无需改约 32 个静态接触点」的目标(替代 AGENTS.md E7 手工装配)。
 */
public record ProviderDescriptor(
        String providerId,
        String displayLabel,
        String cliCommand,
        String cliCommandWindows,
        Set<ProviderCapability> capabilities,
        Set<RuntimeType> supportedRuntimes
) {
    public ProviderDescriptor {
        Objects.requireNonNull(providerId, "providerId");
        providerId = providerId.trim().toLowerCase(Locale.ROOT);
        if (providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        Objects.requireNonNull(displayLabel, "displayLabel");
        Objects.requireNonNull(cliCommand, "cliCommand");
        Objects.requireNonNull(cliCommandWindows, "cliCommandWindows");
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        supportedRuntimes = supportedRuntimes == null ? Set.of() : Set.copyOf(supportedRuntimes);
    }

    /** 平台相关 CLI 命令(Windows 用 cliCommandWindows,其他用 cliCommand),对齐 {@link ProviderType#cliCommandForPlatform()}。 */
    public String cliCommandForPlatform() {
        return PlatformUtils.isWindows() ? cliCommandWindows : cliCommand;
    }

    public boolean supports(ProviderCapability capability) {
        return capabilities.contains(capability);
    }

    public boolean supports(RuntimeType runtime) {
        return supportedRuntimes.contains(runtime);
    }

    /** 内置 Claude Provider 描述符(复用 ProviderType SSOT,声明全能力 + SDK/CLI)。 */
    public static ProviderDescriptor claude() {
        return builtin(ProviderType.CLAUDE);
    }

    public static ProviderDescriptor codex() {
        return builtin(ProviderType.CODEX);
    }

    public static ProviderDescriptor opencode() {
        return builtin(ProviderType.OPENCODE);
    }

    private static ProviderDescriptor builtin(ProviderType type) {
        return new ProviderDescriptor(
                type.value(),
                type.displayLabel(),
                type.cliCommand(),
                type.cliCommandWindows(),
                EnumSet.allOf(ProviderCapability.class),
                EnumSet.of(RuntimeType.SDK, RuntimeType.CLI)
        );
    }

    /** 三内置 Provider 描述符(Claude / Codex / OpenCode),按 {@link ProviderType} 声明顺序。 */
    public static List<ProviderDescriptor> builtins() {
        return List.of(claude(), codex(), opencode());
    }
}
