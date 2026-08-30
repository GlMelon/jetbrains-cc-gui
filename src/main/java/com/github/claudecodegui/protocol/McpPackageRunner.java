package com.github.claudecodegui.protocol;

import java.util.Arrays;
import java.util.Optional;

/**
 * MCP 包管理型 runner 业务枚举(SSOT)。
 *
 * <p>「会从网络拉取并执行任意包」的 command 首词表。此前前端
 * {@code components/mcp/packageRunner.ts} 手工维护同名数组、后端
 * {@code McpCommandRiskEvaluator.KNOWN_RUNNERS} 各写一份且已漂移(后端多 node/deno/python 等)。
 * 本枚举收敛词表,前端二次确认弹窗与后端 known-runner 校验同源派生。
 *
 * <p>⚠️ 修改此文件后需运行 {@code gradle generateProtocol} 更新前端类型。
 */
public enum McpPackageRunner implements ProtocolValue {

    NPX("npx"),
    UVX("uvx"),
    UV("uv"),
    PNPM("pnpm"),
    PNPX("pnpx"),
    BUNX("bunx");

    private final String value;

    McpPackageRunner(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static Optional<McpPackageRunner> fromValue(String value) {
        return Arrays.stream(values()).filter(runner -> runner.value.equals(value)).findFirst();
    }
}
