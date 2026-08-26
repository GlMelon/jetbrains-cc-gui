package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.protocol.ProtocolValue;
import com.github.claudecodegui.util.PlatformUtils;

import java.util.Arrays;
import java.util.Optional;

/**
 * Provider 维度枚举(路由键之二),亦是协议线上 provider 值的 SSOT(C2 / C9)。
 *
 * <p>区分 Claude 与 Codex 两个 AI 提供者。协议线上传输的 provider 值
 * ({@code "claude"}/{@code "codex"})的唯一权威定义;前端 TypeScript 类型由本枚举在
 * 构建时经 {@code generate-protocol-types.mjs} 自动生成(产物
 * {@code webview/src/generated/protocol.ts}),消除前端 {@code PROVIDER_IDS} /
 * {@code 'claude'|'codex'} 手写第二真相源。
 *
 * <p>值域 2 值,对齐 {@link CommonConstants#PROVIDER_CLAUDE}/{@link CommonConstants#PROVIDER_CODEX}。
 *
 * <p>方法分工:
 * <ul>
 *   <li>{@link #value()} —— {@link ProtocolValue} 协议值出口(SSOT),由生成器反射消费。</li>
 *   <li>{@link #cliCommand()} —— CLI 可执行文件名(非 Windows 平台)。</li>
 *   <li>{@link #cliCommandWindows()} —— CLI 可执行文件名(Windows 平台,含 {@code .cmd} 后缀)。</li>
 *   <li>{@link #cliCommandForPlatform()} —— 根据当前平台返回 CLI 可执行文件名。</li>
 *   <li>{@link #fromValue(String)} —— SSOT 严格往返,值不匹配返回 {@link Optional#empty()},
 *       范式对齐 {@code PermissionMode}/{@code ReasoningEffort}。</li>
 *   <li>{@link #fromString(String)} —— 历史宽容解析(null/未知 → {@code CLAUDE}),
 *       路由层依赖此默认(见 {@code SessionSendService}),保留不变。</li>
 * </ul>
 *
 * <p>注:本枚举留在 {@code session.runtime} 包(SSOT 语义不要求物理位置必须在 {@code protocol}
 * 包,移动会引发大规模 import churn)。与 {@code PermissionMode}/{@code ReasoningEffort} 一样,
 * 凡 {@link ProtocolValue} 枚举均经生成器统一消费。
 *
 * <p>⚠️ 修改此文件后需运行 {@code gradle generateProtocol} 更新前端类型。
 */
public enum ProviderType implements ProtocolValue {

    CLAUDE("claude", "Claude", "claude", "claude.cmd"),
    CODEX("codex", "Codex", "codex", "codex.cmd"),
    OPENCODE("opencode", "OpenCode", "opencode", "opencode.cmd"),
    // grok/kimi/pi 为纯 CLI provider(上游 CliToolId);cliCommand 对齐上游 binaryName。
    GROK("grok", "Grok", "grok", "grok.cmd"),
    KIMI("kimi", "Kimi", "kimi", "kimi.cmd"),
    PI("pi", "Pi", "pi", "pi.cmd"),
    // omp/dsh:上游 v0.5.4 新增纯 CLI provider。OMP 是 pi 的 fork(输出 NDJSON,经 ai-bridge channel 转换);
    // DSH 走本地 dsh web host RPC(经 ai-bridge channel 触发)。Java 侧均经 ChannelCliSession spawn channel-manager,
    // cliCommand 仅为占位(channel-manager 路径由 NodeService 解析,不直接 spawn 该二进制)。
    OMP("omp", "OMP", "omp", "omp.cmd"),
    DSH("dsh", "DeepSeek Harness", "dsh", "dsh.cmd"),
    ;

    private final String value;
    private final String displayLabel;
    private final String cliCommand;
    private final String cliCommandWindows;

    ProviderType(String value, String displayLabel, String cliCommand, String cliCommandWindows) {
        this.value = value;
        this.displayLabel = displayLabel;
        this.cliCommand = cliCommand;
        this.cliCommandWindows = cliCommandWindows;
    }

    /** 协议线上实际传输的字符串值(对齐 {@link CommonConstants#PROVIDER_CLAUDE}/{@code PROVIDER_CODEX}) */
    @Override
    public String value() {
        return value;
    }

    /** User-facing provider label for backend-computed UI payloads. */
    public String displayLabel() {
        return displayLabel;
    }

    /**
     * CLI 可执行文件名(非 Windows 平台)。
     * <p>消除 {@code CodexCliResolver}/{@code OpenCodeCliResolver} 中的硬编码。
     */
    public String cliCommand() {
        return cliCommand;
    }

    /**
     * CLI 可执行文件名(Windows 平台,含 {@code .cmd} 后缀)。
     * <p>消除 {@code CodexCliResolver}/{@code OpenCodeCliResolver} 中的硬编码。
     */
    public String cliCommandWindows() {
        return cliCommandWindows;
    }

    /**
     * 根据当前平台返回 CLI 可执行文件名。
     * <p>消除 {@code CodexCliResolver}/{@code OpenCodeCliResolver}/{@code CodexCliCommandUtils} 中的硬编码。
     *
     * @return Windows 返回 {@link #cliCommandWindows()},其他平台返回 {@link #cliCommand()}
     */
    public String cliCommandForPlatform() {
        return PlatformUtils.isWindows() ? cliCommandWindows : cliCommand;
    }

    /**
     * SSOT 严格往返:值不匹配返回 {@link Optional#empty()}。
     * 范式对齐 {@code PermissionMode#fromValue}/{@code ReasoningEffort#fromValue}。
     */
    public static Optional<ProviderType> fromValue(String value) {
        return Arrays.stream(values()).filter(p -> p.value.equals(value)).findFirst();
    }

    /**
     * 从 provider 字符串转换为 ProviderType(历史宽容解析)。
     * 兼容现有字符串常量 {@link CommonConstants#PROVIDER_CLAUDE}/{@link CommonConstants#PROVIDER_CODEX}。
     * null/未知 → {@code CLAUDE}(路由层依赖此默认,见 SessionSendService)。
     * <p>如需严格校验(值不匹配即失败而非降级),改用 {@link #fromValue(String)}。
     */
    public static ProviderType fromString(String provider) {
        if (provider == null) {
            return CLAUDE;
        }
        return switch (provider.trim().toLowerCase()) {
            case CommonConstants.PROVIDER_CODEX -> CODEX;
            case CommonConstants.PROVIDER_OPENCODE -> OPENCODE;
            case CommonConstants.PROVIDER_GROK -> GROK;
            case CommonConstants.PROVIDER_KIMI -> KIMI;
            case CommonConstants.PROVIDER_PI -> PI;
            case CommonConstants.PROVIDER_OMP -> OMP;
            case CommonConstants.PROVIDER_DSH -> DSH;
            default -> CLAUDE;
        };
    }
}
