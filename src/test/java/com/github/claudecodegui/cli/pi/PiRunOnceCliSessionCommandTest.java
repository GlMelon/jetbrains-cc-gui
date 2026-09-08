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
 * 协议档位经能力表 clamp 后透传 --thinking(协议 none → wire off),
 * 由 thinkingOutputEnabled 门控(官方调研 2026-09-07:pi 收到不支持的档位
 * warning + 静默丢参,必须本地钳制)。
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
    public void resolveThinkingLevelPassesAllProtocolLevelsThrough() {
        assertEquals("minimal", PiRunOnceCliSession.resolveThinkingLevel("minimal", null));
        assertEquals("low", PiRunOnceCliSession.resolveThinkingLevel("low", null));
        assertEquals("medium", PiRunOnceCliSession.resolveThinkingLevel("medium", null));
        assertEquals("high", PiRunOnceCliSession.resolveThinkingLevel("high", null));
        assertEquals("xhigh", PiRunOnceCliSession.resolveThinkingLevel("xhigh", null));
        assertEquals("max", PiRunOnceCliSession.resolveThinkingLevel("max", null));
    }

    @Test
    public void resolveThinkingLevelMapsProtocolNoneToWireOff() {
        // 协议词表用 none,pi CLI 词表用 off 表达关闭思考
        assertEquals("off", PiRunOnceCliSession.resolveThinkingLevel("none", null));
        assertEquals("off", PiRunOnceCliSession.resolveThinkingLevel(" NONE ", null));
    }

    @Test
    public void resolveThinkingLevelNormalizesCaseAndWhitespace() {
        assertEquals("max", PiRunOnceCliSession.resolveThinkingLevel("MAX", null));
        assertEquals("xhigh", PiRunOnceCliSession.resolveThinkingLevel(" XHigh ", null));
    }

    @Test
    public void resolveThinkingLevelReturnsNullForNullBlankAndUnknown() {
        assertNull(PiRunOnceCliSession.resolveThinkingLevel(null, null));
        assertNull(PiRunOnceCliSession.resolveThinkingLevel("", null));
        assertNull(PiRunOnceCliSession.resolveThinkingLevel("   ", null));
        assertNull(PiRunOnceCliSession.resolveThinkingLevel("turbo", null));
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
