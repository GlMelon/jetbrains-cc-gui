package com.github.claudecodegui.cli.common;

import org.junit.After;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link CliPersistentProcessRegistry} slot bookkeeping.
 *
 * <p>Spawn-success paths need a real resident CLI process, which is out of unit-test
 * scope — here we pin the failure/no-op contracts that the one-shot fallback relies on:
 * a spawn failure must degrade to {@code null} (never throw),
 * and every lifecycle entry point must tolerate empty/unknown slot state.
 */
public class CliPersistentProcessRegistryTest {

    private CliPersistentProcessRegistry registry;

    @After
    public void tearDown() {
        if (registry != null) {
            registry.dispose();
        }
    }

    private CliPersistentProcessRegistry freshRegistry() {
        registry = new CliPersistentProcessRegistry();
        return registry;
    }

    /** Spec pointing at an executable that cannot exist — ProcessBuilder.start() fails fast. */
    private static CliProcessSpec unspawnableSpec() {
        return new CliProcessSpec(
                "claude|test-model|-|-|-|-|-",
                List.of("aicg-no-such-dir/aicg-no-such-executable-" + System.nanoTime()),
                Collections.emptyMap(),
                null);
    }

    @Test
    public void acquireReturnsNullWhenSpawnFails() {
        CliPersistentProcessRegistry registry = freshRegistry();
        // 首条消息 spawn 失败必须安静降级为 null(上层走 one-shot),不得抛异常
        assertNull(registry.acquire("tab-1", "claude", unspawnableSpec()));
        // spawn 失败不占槽:再次 acquire 仍是同样的 null 降级路径
        assertNull(registry.acquire("tab-1", "claude", unspawnableSpec()));
        assertTrue(registry.describeAll().isEmpty());
    }

    @Test
    public void describeAllEmptyWhenNoSlots() {
        assertTrue(freshRegistry().describeAll().isEmpty());
    }

    @Test
    public void diagnosticsStartAtZero() {
        CliPersistentProcessRegistry.Diagnostics diagnostics = freshRegistry().diagnostics();

        assertEquals(0, diagnostics.registrySize());
        assertEquals(0, diagnostics.usableProcessCount());
        assertEquals(0, diagnostics.pendingRebuildCount());
        assertEquals(0L, diagnostics.evictionCount());
        assertEquals(0L, diagnostics.rebuildCooldownHitCount());
    }

    @Test
    public void reclaimIdleProcessesNowToleratesEmptyRegistry() {
        // 开关关闭副作用在空注册表上必须是 no-op
        freshRegistry().reclaimIdleProcessesNow();
    }

    @Test
    public void releaseUnknownSlotIsNoop() {
        // tab 关闭可能先于任何 spawn(dispose 链路),未知键不得抛
        freshRegistry().release("never-spawned-tab", "claude");
    }

    @Test
    public void acquireRejectsDegenerateSpec() {
        CliPersistentProcessRegistry registry = freshRegistry();
        // 空 command 一样走 spawn-failure 降级,不抛
        CliProcessSpec emptyCommand = new CliProcessSpec(
                "f", Collections.emptyList(), (Map<String, String>) null, null);
        assertNull(registry.acquire("tab-2", "claude", emptyCommand));
    }

    @Test
    public void consecutiveSpawnFailuresActivateRebuildCooldown() {
        CliPersistentProcessRegistry registry = freshRegistry();
        // 连续失败达上限(CLI_PERSISTENT_REBUILD_MAX_FAILURES)后进入冷却窗口
        for (int i = 0; i < CliConstants.CLI_PERSISTENT_REBUILD_MAX_FAILURES; i++) {
            assertNull(registry.acquire("tab-cooldown", "claude", unspawnableSpec()));
        }
        assertTrue(registry.isRebuildCoolingDown("tab-cooldown", "claude"));
        // 冷却期内:acquire 直接 null(one-shot 降级),rebuildInBackground 也直接放弃
        assertNull(registry.acquire("tab-cooldown", "claude", unspawnableSpec()));
        registry.rebuildInBackground("tab-cooldown", "claude", unspawnableSpec());
        CliPersistentProcessRegistry.Diagnostics diagnostics = registry.diagnostics();
        assertEquals(0, diagnostics.registrySize());
        assertEquals(2L, diagnostics.rebuildCooldownHitCount());
        // 其他 tab 不受坏槽位冷却牵连
        assertFalse(registry.isRebuildCoolingDown("tab-other", "claude"));
    }

    @Test
    public void cooldownIsNotActivatedBelowFailureThreshold() {
        CliPersistentProcessRegistry registry = freshRegistry();
        for (int i = 0; i < CliConstants.CLI_PERSISTENT_REBUILD_MAX_FAILURES - 1; i++) {
            assertNull(registry.acquire("tab-warmup", "claude", unspawnableSpec()));
        }
        // 未达上限:不冷却,下条消息仍会再次尝试(偶发失败快速自愈)
        assertFalse(registry.isRebuildCoolingDown("tab-warmup", "claude"));
    }
}
