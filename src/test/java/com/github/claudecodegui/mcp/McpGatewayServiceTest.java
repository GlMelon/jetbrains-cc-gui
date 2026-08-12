package com.github.claudecodegui.mcp;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 验证 {@link McpGatewayService#applySnapshot} 的关键时序契约:
 * <ul>
 *   <li>postSnapshot 失败不得提交 currentSnapshot(否则永不重推);</li>
 *   <li>configHash 未变应 skip —— 预热完成后发送路径(buildCliConfig → refreshConfig → applySnapshot)秒回的根因;</li>
 *   <li>postSnapshot 同步阻塞调用方 —— 慢冷加载由持锁者承担,配合所有变更路径共用同一 synchronized(lock),
 *       解释"首条消息卡几十秒"。</li>
 * </ul>
 */
public class McpGatewayServiceTest {

    @Test
    public void applySnapshotRetriesPostAfterFailure() throws Exception {
        FailingThenSuccessClient client = new FailingThenSuccessClient();
        StubCollector collector = new StubCollector();
        McpGatewayService service = new McpGatewayService(collector, client);

        // 第一次:postSnapshot 失败,应向上抛出且不提交 currentSnapshot
        try {
            service.applySnapshot("/test");
            fail("applySnapshot 应传播 postSnapshot 失败");
        } catch (Exception expected) {
            // expected
        }
        assertEquals("第一次应调用 postSnapshot", 1, client.postCalls);

        // 第二次:configHash 相同(StubCollector 始终返回空 servers + 相同 projectPath)。
        // 若第一次失败后误提交 currentSnapshot,此处会因 hash 相同而 skip、postCalls 不变(==1)。
        // 正确行为:currentSnapshot 未提交 → 不 skip → 再次 postSnapshot(==2)。
        service.applySnapshot("/test");
        assertEquals("失败后必须重试 postSnapshot,不能因假成功的 configHash 跳过", 2, client.postCalls);
    }

    /**
     * configHash 幂等的正面用例:applySnapshot 成功提交后,第二次因 configHash 相同而 skip、不再 postSnapshot。
     * <p>这是"预热完成后发送路径秒回"的机制根因——预热已把 snapshot 推给 gateway(成功提交 currentSnapshot),
     * 后续只要 MCP 配置不变,发送路径的 applySnapshot 命中 skip 分支、零开销返回。
     * 与 {@link #applySnapshotRetriesPostAfterFailure} 互补:那条测"失败不提交→重试",本条测"成功提交→skip"。
     */
    @Test
    public void applySnapshotSkipsWhenConfigHashUnchanged() throws Exception {
        SuccessClient client = new SuccessClient();
        StubCollector collector = new StubCollector();
        McpGatewayService service = new McpGatewayService(collector, client);

        service.applySnapshot("/test");
        assertEquals("第一次应 postSnapshot", 1, client.postCalls);

        // StubCollector 始终返回相同 servers + projectPath → 两次 configHash 相同。
        // 第一次已提交 currentSnapshot → 第二次命中 skip 分支,不再 post。
        service.applySnapshot("/test");
        assertEquals("configHash 未变应 skip,不再 postSnapshot", 1, client.postCalls);
    }

    /**
     * resetSnapshotState 强制重推:applySnapshot 成功提交后(currentSnapshot≠null,后续会 skip),
     * resetSnapshotState 清空 currentSnapshot → 下一次 applySnapshot 因 currentSnapshot==null 不 skip、重新 post。
     * 这是"重载 Gateway 强制重推 snapshot"语义的机制根因(stopGateway→reset→ensureStarted→applySnapshot 强制 post)。
     */
    @Test
    public void resetForcesReapplySnapshot() throws Exception {
        SuccessClient client = new SuccessClient();
        StubCollector collector = new StubCollector();
        McpGatewayService service = new McpGatewayService(collector, client);

        service.applySnapshot("/test");
        assertEquals("第一次应 postSnapshot", 1, client.postCalls);

        // configHash 相同 → 正常情况会 skip(postCalls 不变)。
        service.applySnapshot("/test");
        assertEquals("configHash 未变应 skip", 1, client.postCalls);

        // reset 清空 currentSnapshot → 下次不再 skip → 重新 post。
        service.resetSnapshotState();
        service.applySnapshot("/test");
        assertEquals("reset 后应强制重推 postSnapshot", 2, client.postCalls);
    }

    /**
     * postSnapshot 同步阻塞语义:Node 侧 applySnapshot 会 await 所有 MCP server 的 initialize+listTools
     * (冷加载 >10s),Java 侧 postSnapshot 同步等其返回。本测试用 BlockingClient 卡住 postSnapshot,
     * 证明 applySnapshot 调用方被阻塞至 postSnapshot 完成。
     * <p>结合 McpGatewayService 所有变更路径(refreshConfig / buildCliConfig)共用同一 synchronized(lock):
     * 预热线程的 applySnapshot 持锁阻塞在慢 postSnapshot 时,发送线程的 buildCliConfig 在 synchronized(lock)
     * 处排队等待 —— 这就是"首条消息卡几十秒"的根因(冷加载耗时由先到者承担,后到者等锁)。
     */
    @Test
    public void slowPostSnapshotBlocksApplySnapshotCaller() throws Exception {
        BlockingClient client = new BlockingClient();
        StubCollector collector = new StubCollector();
        McpGatewayService service = new McpGatewayService(collector, client);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = pool.submit(() -> {
                try {
                    service.applySnapshot("/test");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // 确认 applySnapshot 已进入 postSnapshot(阻塞中):entered latch 在 postSnapshot 入口 countDown。
            assertTrue("applySnapshot 应进入 postSnapshot",
                    client.entered.await(2, TimeUnit.SECONDS));
            // postSnapshot 阻塞期间,applySnapshot 不得返回。
            assertFalse("postSnapshot 阻塞期间 applySnapshot 不得返回", future.isDone());

            // 释放 postSnapshot,applySnapshot 应随即完成。
            client.release.countDown();
            future.get(2, TimeUnit.SECONDS);
            assertEquals("postSnapshot 应被调用一次", 1, client.postCalls);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 固定返回空 server 列表 + 指定 projectPath,使两次 collect 的 configHash 相同。 */
    public static final class StubCollector extends McpGatewayConfigCollector {
        public StubCollector() {
            super(null);
        }

        @Override
        public McpGatewayConfigSnapshot collect(long revision, String projectPath) {
            return McpGatewayConfigSnapshot.create(revision, projectPath, List.of());
        }
    }

    /** 第一次 postSnapshot 抛异常(模拟 HttpTimeoutException),第二次成功。 */
    public static final class FailingThenSuccessClient extends McpGatewayBridgeClient {
        int postCalls = 0;

        public FailingThenSuccessClient() {
            super(Path.of(System.getProperty("java.io.tmpdir")).resolve("mcp-gw-test-state.json"),
                    "test-token");
        }

        @Override
        public JsonObject postSnapshot(McpGatewayConfigSnapshot snapshot) {
            postCalls++;
            if (postCalls == 1) {
                throw new RuntimeException("simulated postSnapshot timeout");
            }
            return new JsonObject();
        }
    }

    /** postSnapshot 计数并成功返回(不阻塞),供 configHash 幂等用例。 */
    public static final class SuccessClient extends McpGatewayBridgeClient {
        int postCalls = 0;

        public SuccessClient() {
            super(Path.of(System.getProperty("java.io.tmpdir")).resolve("mcp-gw-test-success.json"),
                    "test-token");
        }

        @Override
        public JsonObject postSnapshot(McpGatewayConfigSnapshot snapshot) {
            postCalls++;
            return new JsonObject();
        }
    }

    /**
     * postSnapshot 阻塞至 {@link #release} 被 countDown,模拟 Node 侧冷加载同步等待。
     * {@link #entered} 在 postSnapshot 入口 countDown,供测试确认"已进入阻塞"。
     */
    public static final class BlockingClient extends McpGatewayBridgeClient {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile int postCalls = 0;

        public BlockingClient() {
            super(Path.of(System.getProperty("java.io.tmpdir")).resolve("mcp-gw-test-block.json"),
                    "test-token");
        }

        @Override
        public JsonObject postSnapshot(McpGatewayConfigSnapshot snapshot) throws InterruptedException {
            postCalls++;
            entered.countDown();
            release.await();
            return new JsonObject();
        }
    }
}
