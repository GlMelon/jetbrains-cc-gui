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
 * 支持档由 ReasoningCapabilities 按 (grok, model) 派生后 clamp 透传——
 * 4.6+ 官方支持 xhigh(直发),3-mini 仅 low/high,4.3 含 none,未知族默认
 * [low,medium,high];null/未知省略 --reasoning-effort(官方调研 2026-09-07)。
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

    private static CliSendRequest requestWithModel(String effort, String model) {
        return new CliSendRequest(
                "tab-1", CommonConstants.PROVIDER_GROK, "hello",
                null, "/work", List.of(), new JsonObject(), List.of(),
                null, CommonConstants.PERMISSION_MODE_DEFAULT, model,
                model, effort, null, java.util.Map.of()
        );
    }

    @Test
    public void normalizeEffortPassesLowMediumHighThrough() {
        assertEquals("low", GrokRunOnceCliSession.normalizeEffort("low", null));
        assertEquals("medium", GrokRunOnceCliSession.normalizeEffort("medium", null));
        assertEquals("high", GrokRunOnceCliSession.normalizeEffort("high", null));
    }

    @Test
    public void normalizeEffortClampsXhighAndMaxToHighForUnknownFamily() {
        // 未知族(无模型 / 非收录前缀)→ 默认集 [low,medium,high]
        assertEquals("high", GrokRunOnceCliSession.normalizeEffort("xhigh", null));
        assertEquals("high", GrokRunOnceCliSession.normalizeEffort("max", "grok-4-fast"));
    }

    @Test
    public void normalizeEffortPassesXhighThroughForXhighCapableFamily() {
        // 官方支持 xhigh 的族(4.6+)直发,不再本地钳到 high
        assertEquals("xhigh", GrokRunOnceCliSession.normalizeEffort("xhigh", "grok-4.6"));
        assertEquals("xhigh", GrokRunOnceCliSession.normalizeEffort("xhigh", "grok-5"));
        // max 不在 4.6+ 集(词表有 max,API 实义上限 xhigh)→ 钳到 xhigh
        assertEquals("xhigh", GrokRunOnceCliSession.normalizeEffort("max", "grok-4.6"));
    }

    @Test
    public void normalizeEffortNarrowsToModelRules() {
        // 3-mini 仅 [low,high]:medium 钳到 low(不超过请求值的最高支持档不存在时取最低档)
        assertEquals("low", GrokRunOnceCliSession.normalizeEffort("medium", "grok-3-mini"));
        assertEquals("high", GrokRunOnceCliSession.normalizeEffort("high", "grok-3-mini"));
        // 4.3 含 none:none 直发;未知族 none → 无更低支持档,取最低 low
        assertEquals("none", GrokRunOnceCliSession.normalizeEffort("none", "grok-4.3"));
        assertEquals("low", GrokRunOnceCliSession.normalizeEffort("none", null));
    }

    @Test
    public void normalizeEffortNormalizesCaseAndWhitespace() {
        assertEquals("high", GrokRunOnceCliSession.normalizeEffort(" HIGH ", null));
    }

    @Test
    public void normalizeEffortReturnsNullForNullAndUnknown() {
        assertNull(GrokRunOnceCliSession.normalizeEffort(null, null));
        assertNull(GrokRunOnceCliSession.normalizeEffort("turbo", "grok-4.6"));
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
    public void buildRunCommandDegradesXhighAndMaxToHighFlagForUnknownFamily() {
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
    public void buildRunCommandPassesXhighThroughForXhighCapableModel() {
        GrokRunOnceCliSession session = new GrokRunOnceCliSession("t");
        List<String> cmd = session.buildRunCommand(requestWithModel("xhigh", "grok-4.6"), null, List.of());

        int idx = cmd.indexOf(CliConstants.GROK_ARG_REASONING_EFFORT);
        assertTrue("--reasoning-effort flag present", idx >= 0);
        assertEquals("xhigh", cmd.get(idx + 1));
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
