package com.github.claudecodegui.cli.common;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 长驻 CLI 进程启动规格:指纹 + spawn 材料 + provider 协议钩子。
 *
 * <p>指纹(provider 层拼接)仅包含影响进程行为语义的字段——provider + model +
 * permission-mode + cwd + add-dirs + mcp-config 路径。指纹不匹配时
 * 不复用旧进程:当前消息走 one-shot,后台按新指纹重建。命令行/环境由 provider 层
 * 按现有 one-shot 链路构建(仅额外加 {@code --input-format stream-json}),Registry 不感知。
 *
 * <p>{@code interruptLineSupplier} 是 provider 的中断协议行构造器(如 claude 的
 * control_request interrupt 行):provider 无关抽象 {@link CliPersistentProcess} 不内置任何
 * 协议格式,interruptTurn() 经此钩子取得协议行;为 null 表示该 provider 无进程保留式
 * 中断,interrupt 直接走杀进程兜底。
 */
public record CliProcessSpec(
        String fingerprint,
        List<String> command,
        Map<String, String> env,
        String cwd,
        Supplier<String> interruptLineSupplier
) {
    /** 兼容构造:无协议级中断的 provider(或测试)用。 */
    public CliProcessSpec(
            String fingerprint,
            List<String> command,
            Map<String, String> env,
            String cwd
    ) {
        this(fingerprint, command, env, cwd, null);
    }
}
