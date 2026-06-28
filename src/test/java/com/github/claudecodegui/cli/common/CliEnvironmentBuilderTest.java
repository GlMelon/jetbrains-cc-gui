package com.github.claudecodegui.cli.common;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * 验证 CliEnvironmentBuilder.applyExtraEnv —— 请求级环境变量注入的对称纯函数。
 * <p>
 * 背景:Claude/Codex/OpenCode 三 provider 的 CLI 模式必须对称注入 {@code request.extraEnv()}。
 * 历史上 Claude 漏注入(静默丢弃请求级 env,如临时 token/proxy),Codex/OpenCode 有;
 * 抽取此纯函数让三 provider 对称调用且可单测(平台耦合的 runOnce 无法直接测)。
 */
public class CliEnvironmentBuilderTest {

    @Test
    public void applyExtraEnvMergesEntriesIntoCliEnv() {
        Map<String, String> cliEnv = new LinkedHashMap<>();
        cliEnv.put("EXISTING", "1");

        CliEnvironmentBuilder.applyExtraEnv(cliEnv, Map.of("FOO", "bar", "BAZ", "qux"));

        assertEquals("bar", cliEnv.get("FOO"));
        assertEquals("qux", cliEnv.get("BAZ"));
        assertEquals("1", cliEnv.get("EXISTING"));
    }

    @Test
    public void applyExtraEnvIgnoresEmptyMap() {
        Map<String, String> cliEnv = new LinkedHashMap<>();
        cliEnv.put("EXISTING", "1");

        CliEnvironmentBuilder.applyExtraEnv(cliEnv, Map.of());

        assertEquals(1, cliEnv.size());
        assertEquals("1", cliEnv.get("EXISTING"));
    }

    @Test
    public void applyExtraEnvIgnoresNullExtraEnv() {
        Map<String, String> cliEnv = new LinkedHashMap<>();
        cliEnv.put("EXISTING", "1");

        CliEnvironmentBuilder.applyExtraEnv(cliEnv, null);

        assertEquals(1, cliEnv.size());
        assertEquals("1", cliEnv.get("EXISTING"));
    }

    @Test
    public void applyExtraEnvOverwritesExistingKey() {
        Map<String, String> cliEnv = new LinkedHashMap<>();
        cliEnv.put("TOKEN", "old");

        CliEnvironmentBuilder.applyExtraEnv(cliEnv, Map.of("TOKEN", "new"));

        assertEquals("请求级 extraEnv 应覆盖已有值", "new", cliEnv.get("TOKEN"));
    }

    @Test
    public void applyExtraEnvToleratesNullCliEnv() {
        // 防御性:与 configureProjectPath/configureClaudePermissionEnv 一致,null env 不抛异常
        CliEnvironmentBuilder.applyExtraEnv(null, Map.of("FOO", "bar"));
    }
}
