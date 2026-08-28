package com.github.claudecodegui.mcp;

import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 验证熔断器(closed → open → half-open)。
 *
 * <p>背景:注定失败的 MCP server(ENOENT / fetch failed / 配置坏)在每次
 * getMcpServerStatus 查询时都会被重新冷启动验证(stdio 走 {@code cmd /c npx} 冷启动,
 * 秒级开销),前端乒乓风暴下放大为系统进程耗尽(2026-08-27,496 spawn/小时 + 908 僵尸)。
 * 前端 {@code TERMINAL_STATUSES} 语义下失败是终态,用户看到的失败结果不因跳过验证而变化
 * ——失败的照旧失败(带熔断标记),成功的照常验证,互不影响。
 *
 * <p>状态机:
 * <ul>
 *   <li><b>closed</b>:正常,每次查询都验证。失败连续累加。</li>
 *   <li><b>open</b>:连续失败 ≥{@link #FAILURE_THRESHOLD} 次后打开,验证被跳过
 *       (名单经 stdin 传给 ai-bridge,node 直接合成失败结果,不再 spawn)。</li>
 *   <li><b>half-open</b>:open 满 {@link #HALF_OPEN_COOLDOWN_MS} 后放行一次试探验证;
 *       成功 → closed(计数清零),失败 → 重新 open 计时。</li>
 * </ul>
 *
 * <p>线程安全:字段均为 ConcurrentHashMap,供共享池上的并发查询读写。
 * 合成结果(带 {@link #CIRCUIT_MARKER})不参与计数,防止 open 态下计数被跳过结果无限推高。
 */
public final class McpVerifyCircuitBreaker {

    /** 连续失败达到该次数即熔断(用户规格:超过三次不再继续循环请求)。 */
    public static final int FAILURE_THRESHOLD = 3;
    /** open → half-open 的冷却窗:到期放行一次试探验证。 */
    public static final long HALF_OPEN_COOLDOWN_MS = 5 * 60_000L;
    /** ai-bridge 合成的跳过结果在 error 字段携带的标记(Java 侧据此不计失败)。 */
    public static final String CIRCUIT_MARKER = "[circuit-open]";

    private final ConcurrentHashMap<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> circuitOpenedAt = new ConcurrentHashMap<>();

    /**
     * 查询结果回灌:failed 连续失败 +1;connected(及任何非失败终态)清零并关闭熔断。
     * ai-bridge 合成的熔断跳过结果({@link #CIRCUIT_MARKER})不计入——那些 server 本就在 open 态。
     *
     * @param servers 本次查询返回的全量 server 状态列表(元素含 name/status/error 可选字段)
     */
    public void onResult(List<JsonObject> servers) {
        if (servers == null) {
            return;
        }
        for (JsonObject server : servers) {
            if (server == null || !server.has("name")) {
                continue;
            }
            String name = server.get("name").getAsString();
            String status = server.has("status") && server.get("status").isJsonPrimitive()
                    ? server.get("status").getAsString() : "";
            if ("connected".equals(status)) {
                consecutiveFailures.remove(name);
                circuitOpenedAt.remove(name);
            } else if ("failed".equals(status)) {
                String error = server.has("error") && server.get("error").isJsonPrimitive()
                        ? server.get("error").getAsString() : "";
                if (error.contains(CIRCUIT_MARKER)) {
                    continue; // 合成的跳过结果:不计数
                }
                consecutiveFailures.merge(name, 1, Integer::sum);
            }
        }
    }

    /**
     * 计算本次查询应跳过验证的 server 名单,并推进状态机(open 到期转 half-open 放行)。
     *
     * <p>注意:half-open 放行发生在本方法内(重记 openedAt)——若试探又失败,
     * onResult 使计数保持 ≥阈值,下次调用重新进入 open;若成功,onResult 清零关断。
     *
     * @param now 当前时刻毫秒(显式传入便于测试)
     * @return 应跳过验证的 server 名集合(open 态);空集表示全部照常验证
     */
    public Set<String> serversToSkip(long now) {
        if (consecutiveFailures.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> skip = null;
        for (ConcurrentHashMap.Entry<String, Integer> entry : consecutiveFailures.entrySet()) {
            if (entry.getValue() < FAILURE_THRESHOLD) {
                continue;
            }
            String name = entry.getKey();
            Long openedAt = circuitOpenedAt.get(name);
            if (openedAt == null) {
                // 首次达到阈值:打开熔断
                circuitOpenedAt.putIfAbsent(name, now);
                appendSkip(skip == null ? (skip = new HashSet<>()) : skip, name);
            } else if (now - openedAt < HALF_OPEN_COOLDOWN_MS) {
                // open 冷却期内:继续跳过
                appendSkip(skip == null ? (skip = new HashSet<>()) : skip, name);
            } else {
                // half-open:放行一次试探(重记时间;结果由 onResult 决定恢复或继续 open)
                circuitOpenedAt.put(name, now);
            }
        }
        return skip != null ? skip : Collections.emptySet();
    }

    /** 仅测试与诊断用:某 server 当前连续失败次数。 */
    int failureCount(String name) {
        return consecutiveFailures.getOrDefault(name, 0);
    }

    /**
     * 全量重置(配置变更时调用):用户增/删/改了 server 配置,旧失败计数与新配置无关,
     * 清空计数与熔断状态,保证修改后的 server 不被历史失败挡住。
     */
    public void reset() {
        consecutiveFailures.clear();
        circuitOpenedAt.clear();
    }

    private static void appendSkip(Set<String> skip, String name) {
        skip.add(name);
    }
}
