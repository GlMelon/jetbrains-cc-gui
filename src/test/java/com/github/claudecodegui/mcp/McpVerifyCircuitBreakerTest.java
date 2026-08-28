package com.github.claudecodegui.mcp;

import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link McpVerifyCircuitBreaker} 状态机测试(closed → open → half-open)。
 *
 * 规格:连续失败 ≥{@link McpVerifyCircuitBreaker#FAILURE_THRESHOLD} 次进入 open(跳过验证),
 * 冷却 {@link McpVerifyCircuitBreaker#HALF_OPEN_COOLDOWN_MS} 后 half-open 放行一次试探;
 * 成功清零恢复,失败重新 open。带 {@link McpVerifyCircuitBreaker#CIRCUIT_MARKER} 的合成
 * 结果不参与计数。失败 server 不得影响成功 server 的计数。
 */
public class McpVerifyCircuitBreakerTest {

    private McpVerifyCircuitBreaker breaker;

    @Before
    public void setUp() {
        breaker = new McpVerifyCircuitBreaker();
    }

    private static JsonObject server(String name, String status) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("status", status);
        return o;
    }

    private static JsonObject syntheticSkipped(String name) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("status", "failed");
        o.addProperty("error", McpVerifyCircuitBreaker.CIRCUIT_MARKER + " Verification skipped");
        return o;
    }

    @Test
    public void belowThreshold_staysClosed() {
        breaker.onResult(Arrays.asList(server("a", "failed"), server("a", "failed")));
        assertTrue(breaker.serversToSkip(1000L).isEmpty());
    }

    @Test
    public void reachesThreshold_opensCircuit() {
        for (int i = 0; i < McpVerifyCircuitBreaker.FAILURE_THRESHOLD; i++) {
            breaker.onResult(Collections.singletonList(server("a", "failed")));
        }
        Set<String> skip = breaker.serversToSkip(1000L);
        assertEquals(Collections.singleton("a"), skip);
    }

    @Test
    public void success_resetsCountAndClosesCircuit() {
        for (int i = 0; i < McpVerifyCircuitBreaker.FAILURE_THRESHOLD; i++) {
            breaker.onResult(Collections.singletonList(server("a", "failed")));
        }
        assertFalse(breaker.serversToSkip(1000L).isEmpty());

        breaker.onResult(Collections.singletonList(server("a", "connected")));
        assertEquals(0, breaker.failureCount("a"));
        assertTrue(breaker.serversToSkip(2000L).isEmpty());
    }

    @Test
    public void failureDoesNotAffectOtherServers() {
        breaker.onResult(Arrays.asList(
                server("broken", "failed"),
                server("healthy", "connected")));
        breaker.onResult(Arrays.asList(
                server("broken", "failed"),
                server("healthy", "connected")));
        breaker.onResult(Arrays.asList(
                server("broken", "failed"),
                server("healthy", "connected")));

        Set<String> skip = breaker.serversToSkip(1000L);
        assertEquals(Collections.singleton("broken"), skip);
        assertEquals(0, breaker.failureCount("healthy"));
    }

    @Test
    public void openWithinCooldown_skips_halfOpenAfterCooldown_admitsProbe() {
        long t0 = 1_000_000L;
        for (int i = 0; i < McpVerifyCircuitBreaker.FAILURE_THRESHOLD; i++) {
            breaker.onResult(Collections.singletonList(server("a", "failed")));
        }

        // 首次判定即打开熔断,openedAt 记为 t0
        assertTrue(breaker.serversToSkip(t0).contains("a"));
        // 冷却期内:持续 open
        assertTrue(breaker.serversToSkip(t0 + McpVerifyCircuitBreaker.HALF_OPEN_COOLDOWN_MS - 1).contains("a"));

        // 冷却到期:half-open 放行试探(不在 skip 名单)
        long probeAt = t0 + McpVerifyCircuitBreaker.HALF_OPEN_COOLDOWN_MS;
        assertFalse(breaker.serversToSkip(probeAt).contains("a"));

        // 试探成功:恢复 closed
        breaker.onResult(Collections.singletonList(server("a", "connected")));
        assertTrue(breaker.serversToSkip(probeAt + 1).isEmpty());
    }

    @Test
    public void halfOpenProbeFailure_reopensForAnotherCooldown() {
        long t0 = 1_000_000L;
        for (int i = 0; i < McpVerifyCircuitBreaker.FAILURE_THRESHOLD; i++) {
            breaker.onResult(Collections.singletonList(server("a", "failed")));
        }
        assertTrue(breaker.serversToSkip(t0).contains("a")); // 打开熔断,openedAt=t0

        long probeAt = t0 + McpVerifyCircuitBreaker.HALF_OPEN_COOLDOWN_MS;
        assertFalse(breaker.serversToSkip(probeAt).contains("a")); // 放行试探

        // 试探失败:重新 open,进入新冷却窗
        breaker.onResult(Collections.singletonList(server("a", "failed")));
        assertTrue(breaker.serversToSkip(probeAt + 1).contains("a"));
        // 新冷却到期(openedAt 仍为 probeAt,冷却期内不重记):放行下一次试探
        assertFalse(breaker.serversToSkip(probeAt + McpVerifyCircuitBreaker.HALF_OPEN_COOLDOWN_MS).contains("a"));
    }

    @Test
    public void syntheticSkippedResults_doNotCount() {
        for (int i = 0; i < McpVerifyCircuitBreaker.FAILURE_THRESHOLD; i++) {
            breaker.onResult(Collections.singletonList(server("a", "failed")));
        }
        int countBefore = breaker.failureCount("a");

        // open 态下 ai-bridge 合成的跳过结果反复回灌,计数不得被推高
        List<JsonObject> batch = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            batch.add(syntheticSkipped("a"));
        }
        breaker.onResult(batch);

        assertEquals(countBefore, breaker.failureCount("a"));
    }

    @Test
    public void nullAndMalformedEntries_areIgnored() {
        breaker.onResult(null);
        breaker.onResult(new ArrayList<>());
        breaker.onResult(Collections.singletonList(null));

        JsonObject noName = new JsonObject();
        noName.addProperty("status", "failed");
        breaker.onResult(Collections.singletonList(noName));

        assertTrue(breaker.serversToSkip(1000L).isEmpty());
    }
}
