package com.github.claudecodegui.cli.opencode;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.common.CommonConstants;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * §15.4 / §7.1-7.2:OpenCodeCliSession 命令构造验证(B1 不预创建 session / B9 位置参数无双写 / B15 能力透传)。
 */
public class OpenCodeCliSessionCommandTest {

    private static CliSendRequest baseRequest() {
        return new CliSendRequest(
                "tab-1", CommonConstants.PROVIDER_OPENCODE, "hello",
                null, "/work", List.of(), new JsonObject(), List.of(),
                null, CommonConstants.PERMISSION_MODE_DEFAULT, "anthropic/claude-3-5-sonnet",
                "anthropic/claude-3-5-sonnet", "high", null, java.util.Map.of()
        );
    }

    private static int indexOf(List<String> cmd, String token) {
        return cmd.indexOf(token);
    }

    @Test
    public void b1_usesRunSubcommandNotFictitiousApiSubcommand() {
        OpenCodeCliSession session = new OpenCodeCliSession("t");
        List<String> cmd = session.buildRunCommand(baseRequest(), null, List.of());

        // B1:子命令是 `run`,不再是臆造的 `api`
        assertEquals("run", cmd.get(1));
        assertFalse("must not use fictitious api subcommand", cmd.contains("api"));
        assertFalse("must not use fictitious service subcommand", cmd.contains("service"));
        assertFalse("must not use -d data flag (fictitious)", cmd.contains("-d"));
        assertFalse("must not use -H header flag (fictitious)", cmd.contains("-H"));
    }

    @Test
    public void b9_messageIsPositionalArgumentNotDualWritten() {
        OpenCodeCliSession session = new OpenCodeCliSession("t");
        List<String> cmd = session.buildRunCommand(baseRequest(), null, List.of());

        // B9:消息紧跟 `run` 之后作为位置参数;`--format json` 在其后;无 stdin `-` / `-d` 双写
        assertEquals("run", cmd.get(1));
        assertEquals("hello", cmd.get(2));              // 位置参数 = 消息
        assertEquals("--format", cmd.get(3));
        assertEquals("json", cmd.get(4));
        // 消息只出现一次(无双写)
        assertEquals(1, cmd.stream().filter("hello"::equals).count());
        assertFalse(cmd.contains("-"));
    }

    @Test
    public void b15_passesCapabilitiesAsFlags() {
        OpenCodeCliSession session = new OpenCodeCliSession("t");
        List<String> cmd = session.buildRunCommand(baseRequest(), null, List.of());

        // model → -m
        int modelIdx = indexOf(cmd, "-m");
        assertTrue("model flag present", modelIdx >= 0);
        assertEquals("anthropic/claude-3-5-sonnet", cmd.get(modelIdx + 1));

        // reasoningEffort high → --variant high
        int variantIdx = indexOf(cmd, "--variant");
        assertTrue("variant flag present", variantIdx >= 0);
        assertEquals("high", cmd.get(variantIdx + 1));

        // cwd → --dir
        int dirIdx = indexOf(cmd, "--dir");
        assertTrue("dir flag present", dirIdx >= 0);
        assertEquals("/work", cmd.get(dirIdx + 1));

        // 总是带 --thinking:让 opencode run --format json 输出 type:"reasoning" 文本事件
        // (parser EVENT_REASONING 分支据此推送思考区)。--thinking(输出推理文本)与 --variant
        // (推理强度)正交。对齐 SDK 路径(message.part.updated reasoning 文本透传)。
        assertTrue("--thinking flag always present", cmd.contains("--thinking"));
    }

    @Test
    public void b15_bypassPermissionModeAddsDangerouslySkipPermissions() {
        CliSendRequest bypass = new CliSendRequest(
                "tab-1", CommonConstants.PROVIDER_OPENCODE, "hi",
                null, "/work", List.of(), new JsonObject(), List.of(),
                null, CommonConstants.PERMISSION_MODE_BYPASS, "anthropic/claude-3-5-sonnet",
                "anthropic/claude-3-5-sonnet", "high", null, java.util.Map.of()
        );
        OpenCodeCliSession session = new OpenCodeCliSession("t");
        List<String> cmd = session.buildRunCommand(bypass, null, List.of());

        assertTrue("bypass → --dangerously-skip-permissions", cmd.contains("--dangerously-skip-permissions"));
    }

    @Test
    public void b13_defaultPermissionModeDoesNotSkipPermissions() {
        OpenCodeCliSession session = new OpenCodeCliSession("t");
        List<String> cmd = session.buildRunCommand(baseRequest(), null, List.of());
        assertFalse("default mode must NOT skip permissions", cmd.contains("--dangerously-skip-permissions"));
    }

    @Test
    public void b13_continuationAppendsSessionFlag() {
        OpenCodeCliSession session = new OpenCodeCliSession("t");
        List<String> cmd = session.buildRunCommand(baseRequest(), "ses_abc", List.of());
        int sessionIdx = indexOf(cmd, "-s");
        assertTrue("continuation adds -s", sessionIdx >= 0);
        assertEquals("ses_abc", cmd.get(sessionIdx + 1));
    }

    @Test
    public void b13_firstTurnDoesNotAppendSessionFlag() {
        OpenCodeCliSession session = new OpenCodeCliSession("t");
        List<String> cmd = session.buildRunCommand(baseRequest(), null, List.of());
        assertFalse("first turn must NOT add -s", cmd.contains("-s"));
    }

    @Test
    public void b15_reasoningMediumIsOmitted() {
        CliSendRequest medium = new CliSendRequest(
                "tab-1", CommonConstants.PROVIDER_OPENCODE, "hi",
                null, "/work", List.of(), new JsonObject(), List.of(),
                null, CommonConstants.PERMISSION_MODE_DEFAULT, "anthropic/claude-3-5-sonnet",
                "anthropic/claude-3-5-sonnet", "medium", null, java.util.Map.of()
        );
        OpenCodeCliSession session = new OpenCodeCliSession("t");
        List<String> cmd = session.buildRunCommand(medium, null, List.of());
        assertFalse("medium → omit --variant", cmd.contains("--variant"));
        // --thinking 与 --variant 正交:medium 省略 variant,但仍带 --thinking(让推理文本可见)
        assertTrue("medium 仍带 --thinking(与 variant 正交)", cmd.contains("--thinking"));
    }
}
