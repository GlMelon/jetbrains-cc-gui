package com.github.claudecodegui.cli.pi;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.common.CommonConstants;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * PiRunOnceCliSession thinking 级别映射与命令组装验证:
 * 7 档(off/minimal/low/medium/high/xhigh/max)白名单透传 --thinking,
 * 由 thinkingOutputEnabled 门控。
 */
public class PiRunOnceCliSessionCommandTest {

    private static CliSendRequest request(String effort, Boolean thinkingOutputEnabled) {
        return new CliSendRequest(
                "tab-1", CommonConstants.PROVIDER_PI, "hello",
                null, "/work", List.of(), new JsonObject(), List.of(),
                null, CommonConstants.PERMISSION_MODE_DEFAULT, "pi-default",
                "pi-default", effort, null, thinkingOutputEnabled, java.util.Map.of()
        );
    }

    @Test
    public void resolveThinkingLevelPassesAllSevenLevelsThrough() {
        assertEquals("off", PiRunOnceCliSession.resolveThinkingLevel("off"));
        assertEquals("minimal", PiRunOnceCliSession.resolveThinkingLevel("minimal"));
        assertEquals("low", PiRunOnceCliSession.resolveThinkingLevel("low"));
        assertEquals("medium", PiRunOnceCliSession.resolveThinkingLevel("medium"));
        assertEquals("high", PiRunOnceCliSession.resolveThinkingLevel("high"));
        assertEquals("xhigh", PiRunOnceCliSession.resolveThinkingLevel("xhigh"));
        assertEquals("max", PiRunOnceCliSession.resolveThinkingLevel("max"));
    }

    @Test
    public void resolveThinkingLevelNormalizesCaseAndWhitespace() {
        assertEquals("max", PiRunOnceCliSession.resolveThinkingLevel("MAX"));
        assertEquals("xhigh", PiRunOnceCliSession.resolveThinkingLevel(" XHigh "));
    }

    @Test
    public void resolveThinkingLevelReturnsNullForNullBlankAndUnknown() {
        assertNull(PiRunOnceCliSession.resolveThinkingLevel(null));
        assertNull(PiRunOnceCliSession.resolveThinkingLevel(""));
        assertNull(PiRunOnceCliSession.resolveThinkingLevel("   "));
        assertNull(PiRunOnceCliSession.resolveThinkingLevel("turbo"));
    }

    @Test
    public void buildRunCommandCarriesThinkingFlagForMax() {
        PiRunOnceCliSession session = new PiRunOnceCliSession("t");
        List<String> cmd = session.buildRunCommand(request("max", Boolean.TRUE), null, List.of());

        int idx = cmd.indexOf(CliConstants.PI_ARG_THINKING);
        assertTrue("--thinking flag present", idx >= 0);
        assertEquals("max", cmd.get(idx + 1));
    }

    @Test
    public void buildRunCommandOmitsThinkingFlagWhenThinkingOutputDisabled() {
        PiRunOnceCliSession session = new PiRunOnceCliSession("t");
        List<String> cmd = session.buildRunCommand(request("max", Boolean.FALSE), null, List.of());

        assertFalse("thinkingOutputEnabled=false omits --thinking",
                cmd.contains(CliConstants.PI_ARG_THINKING));
    }

    @Test
    public void buildRunCommandOmitsThinkingFlagForNullAndUnknownEffort() {
        PiRunOnceCliSession session = new PiRunOnceCliSession("t");

        List<String> nullCmd = session.buildRunCommand(request(null, Boolean.TRUE), null, List.of());
        assertFalse("null effort omits --thinking", nullCmd.contains(CliConstants.PI_ARG_THINKING));

        List<String> unknownCmd = session.buildRunCommand(request("turbo", Boolean.TRUE), null, List.of());
        assertFalse("unknown effort omits --thinking", unknownCmd.contains(CliConstants.PI_ARG_THINKING));
    }
}
