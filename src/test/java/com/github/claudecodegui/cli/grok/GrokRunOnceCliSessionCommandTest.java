package com.github.claudecodegui.cli.grok;

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
 * GrokRunOnceCliSession effort 映射与命令组装验证:
 * xhigh/max 就近降级为 high(grok CLI 上限档,对齐 opencode 语义),
 * null/未知省略 --reasoning-effort。
 */
public class GrokRunOnceCliSessionCommandTest {

    private static CliSendRequest request(String effort) {
        return new CliSendRequest(
                "tab-1", CommonConstants.PROVIDER_GROK, "hello",
                null, "/work", List.of(), new JsonObject(), List.of(),
                null, CommonConstants.PERMISSION_MODE_DEFAULT, "grok-4-fast",
                "grok-4-fast", effort, null, java.util.Map.of()
        );
    }

    @Test
    public void normalizeEffortPassesLowMediumHighThrough() {
        assertEquals("low", GrokRunOnceCliSession.normalizeEffort("low"));
        assertEquals("medium", GrokRunOnceCliSession.normalizeEffort("medium"));
        assertEquals("high", GrokRunOnceCliSession.normalizeEffort("high"));
    }

    @Test
    public void normalizeEffortClampsXhighAndMaxToHigh() {
        assertEquals("high", GrokRunOnceCliSession.normalizeEffort("xhigh"));
        assertEquals("high", GrokRunOnceCliSession.normalizeEffort("max"));
    }

    @Test
    public void normalizeEffortReturnsNullForNullAndUnknown() {
        assertNull(GrokRunOnceCliSession.normalizeEffort(null));
        assertNull(GrokRunOnceCliSession.normalizeEffort("turbo"));
    }

    @Test
    public void buildRunCommandCarriesReasoningEffortFlagForHigh() {
        GrokRunOnceCliSession session = new GrokRunOnceCliSession("t");
        List<String> cmd = session.buildRunCommand(request("high"), null, List.of());

        int idx = cmd.indexOf(CliConstants.GROK_ARG_REASONING_EFFORT);
        assertTrue("--reasoning-effort flag present", idx >= 0);
        assertEquals("high", cmd.get(idx + 1));
    }

    @Test
    public void buildRunCommandDegradesXhighAndMaxToHighFlag() {
        GrokRunOnceCliSession session = new GrokRunOnceCliSession("t");

        List<String> xhighCmd = session.buildRunCommand(request("xhigh"), null, List.of());
        int xhighIdx = xhighCmd.indexOf(CliConstants.GROK_ARG_REASONING_EFFORT);
        assertTrue("xhigh still emits --reasoning-effort", xhighIdx >= 0);
        assertEquals("high", xhighCmd.get(xhighIdx + 1));

        List<String> maxCmd = session.buildRunCommand(request("max"), null, List.of());
        int maxIdx = maxCmd.indexOf(CliConstants.GROK_ARG_REASONING_EFFORT);
        assertTrue("max still emits --reasoning-effort", maxIdx >= 0);
        assertEquals("high", maxCmd.get(maxIdx + 1));
    }

    @Test
    public void buildRunCommandOmitsReasoningEffortFlagForNullAndUnknown() {
        GrokRunOnceCliSession session = new GrokRunOnceCliSession("t");

        List<String> nullCmd = session.buildRunCommand(request(null), null, List.of());
        assertFalse("null effort omits --reasoning-effort",
                nullCmd.contains(CliConstants.GROK_ARG_REASONING_EFFORT));

        List<String> unknownCmd = session.buildRunCommand(request("turbo"), null, List.of());
        assertFalse("unknown effort omits --reasoning-effort",
                unknownCmd.contains(CliConstants.GROK_ARG_REASONING_EFFORT));
    }
}
