package com.github.claudecodegui.mcp;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Lifecycle regression coverage for the project-scoped MCP Gateway facade. */
public class McpGatewayLifecycleTest {

    @Test
    public void disposeDuringSnapshotCollectionPreventsPostAndFutureStateCommit() throws Exception {
        BlockingCollector collector = new BlockingCollector();
        CountingClient client = new CountingClient();
        McpGatewayService service = new McpGatewayService(collector, client);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> apply = pool.submit(() -> {
                try {
                    service.applySnapshot("/test");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertTrue("snapshot collection should start", collector.entered.await(2, TimeUnit.SECONDS));
            service.dispose();
            collector.release.countDown();

            try {
                apply.get(2, TimeUnit.SECONDS);
                fail("disposed service must reject the in-flight snapshot");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause().getCause() instanceof IllegalStateException);
            }
            assertEquals("dispose before post must not publish a snapshot", 0, client.postCalls);
            assertEquals("disposed status must degrade safely", "{}", service.statusJson());

            service.dispose();
            assertEquals("repeated dispose must remain idempotent", 1, client.stopCalls);
        } finally {
            collector.release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    public void staleExitCallbackRequiresMatchingHandleAndGeneration() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/github/claudecodegui/mcp/McpGatewayService.java"), StandardCharsets.UTF_8);

        assertTrue(source.contains("onGatewayProcessExit(callbackHandle, generation)"));
        assertTrue(source.contains("runSelfHeal(expectedHandle, expectedGeneration)"));
        assertTrue(source.contains("!isCurrentProcess(expectedHandle, expectedGeneration)"));
        assertTrue(source.contains("processHandle == expectedHandle && processGeneration == expectedGeneration"));
        assertTrue(source.contains("synchronized (lifecycleLock)"));
        assertTrue(source.contains("throwIfLifecycleDisposed();"));
        assertTrue(source.contains("processHandle = startedHandle;"));
    }

    private static final class BlockingCollector extends McpGatewayConfigCollector {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingCollector() {
            super(null);
        }

        @Override
        public McpGatewayConfigSnapshot collect(long revision, String projectPath) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("snapshot collection interrupted", e);
            }
            return McpGatewayConfigSnapshot.create(revision, projectPath, List.of());
        }
    }

    private static final class CountingClient extends McpGatewayBridgeClient {
        private int postCalls;
        private int stopCalls;

        private CountingClient() {
            super(Path.of(System.getProperty("java.io.tmpdir")).resolve("mcp-gw-lifecycle-test.json"),
                    "test-token");
        }

        @Override
        public JsonObject postSnapshot(McpGatewayConfigSnapshot snapshot) {
            postCalls++;
            return new JsonObject();
        }

        @Override
        public JsonObject stop() {
            stopCalls++;
            return new JsonObject();
        }
    }
}
