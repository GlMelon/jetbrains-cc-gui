package com.github.claudecodegui.mcp;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
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
            assertEquals("dispose must publish terminal lifecycle state", McpGatewayLifecycleState.STOPPED, service.lifecycleState());
            assertEquals("disposed status must degrade safely", "{}", service.statusJson());

            service.dispose();
            assertEquals("repeated dispose must remain idempotent", 1, client.stopCalls);
        } finally {
            collector.release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    public void stopDuringSnapshotPublicationInvalidatesInFlightOperation() throws Exception {
        BlockingCollector collector = new BlockingCollector();
        BlockingClient client = new BlockingClient();
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
            collector.release.countDown();
            assertTrue("snapshot publication should start", client.entered.await(2, TimeUnit.SECONDS));

            service.stopGateway();
            client.release.countDown();

            try {
                apply.get(2, TimeUnit.SECONDS);
                fail("stop must invalidate the in-flight snapshot");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause().getCause() instanceof IllegalStateException);
            }
            assertEquals("stopped service must not commit a snapshot", McpGatewayLifecycleState.STOPPED,
                    service.lifecycleState());
        } finally {
            collector.release.countDown();
            client.release.countDown();
            pool.shutdownNow();
        }
    }
    @Test
    public void stopCancelsRefreshFlightAndExecutorTask() throws Exception {
        McpGatewayService service = new McpGatewayService(new McpGatewayServiceTest.StubCollector(),
                new CountingClient());
        CompletableFuture<Void> flight = new CompletableFuture<>();
        FutureTask<Void> task = new FutureTask<>(() -> null);
        setField(service, "refreshFlight", flight);
        setField(service, "refreshTask", task);

        service.stopGateway();

        assertTrue("stop should cancel the public refresh future", flight.isCancelled());
        assertTrue("stop should cancel the underlying executor task", task.isCancelled());
    }

    @Test
    public void forceRefreshAndProjectPathChangeAreRetainedDuringExistingFlight() throws Exception {
        McpGatewayService service = new McpGatewayService(new McpGatewayServiceTest.StubCollector(),
                new CountingClient());
        CompletableFuture<Void> flight = new CompletableFuture<>();
        setField(service, "refreshFlight", flight);
        setField(service, "refreshProjectPath", "/first");

        Object joined = invokeStartOrJoinRefresh(service, "/second", true);

        assertTrue("request should join the existing flight", joined == flight);
        assertTrue("force refresh should be retained as a pending pass", (Boolean) getField(service, "refreshPending"));
        assertEquals("latest project path should be retained for the pending pass", "/second",
                getField(service, "pendingRefreshProjectPath"));
        service.stopGateway();
    }

    @Test
    public void successfulLifecycleStateClearsPreviousFailure() throws Exception {
        McpGatewayService service = new McpGatewayService(new McpGatewayServiceTest.StubCollector(),
                new CountingClient());
        invokeLifecycleState(service, McpGatewayLifecycleState.DEGRADED_DIRECT, "temporary failure");
        assertEquals("temporary failure", service.lastFailure());

        invokeLifecycleState(service, McpGatewayLifecycleState.IPC_READY, null);

        assertEquals("successful recovery should clear the previous failure", null, service.lastFailure());
    }

    @Test
    public void diagnosticsStartEmptyAndTrackAcceptedDirectDegradation() throws Exception {
        McpGatewayService service = new McpGatewayService(new McpGatewayServiceTest.StubCollector(),
                new CountingClient());

        McpGatewayService.Diagnostics initial = service.diagnostics();
        assertEquals(McpGatewayLifecycleState.STOPPED.value(), initial.lifecycleState());
        assertEquals(0L, initial.processGeneration());
        assertEquals(0, initial.activeProcessCount());
        assertEquals(false, initial.refreshInFlight());
        assertEquals(0L, initial.restartCount());
        assertEquals(-1L, initial.lastColdStartDurationMs());
        assertEquals(-1L, initial.lastCatalogReadyDurationMs());
        assertEquals(0L, initial.directDegradedCount());

        invokeLifecycleState(service, McpGatewayLifecycleState.IPC_READY, null);
        invokeMarkDegradedDirect(service, 0L, "send timeout");
        invokeMarkDegradedDirect(service, 0L, "second timeout");
        invokeMarkDegradedDirect(service, -1L, "stale timeout");

        McpGatewayService.Diagnostics degraded = service.diagnostics();
        assertEquals(McpGatewayLifecycleState.DEGRADED_DIRECT.value(), degraded.lifecycleState());
        assertEquals("second timeout", degraded.lastFailure());
        assertEquals(2L, degraded.directDegradedCount());
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
        assertTrue(source.contains("CompletableFuture<Void> refreshFlight"));
        assertTrue(source.contains("startOrJoinRefresh(projectPath, false)"));
        assertTrue(source.contains("startOrJoinRefresh(projectPath, true)"));
        assertTrue(source.contains("SEND_READY_TIMEOUT.toMillis()"));
        assertTrue(source.contains("pending.cancel(true)"));
        assertTrue(source.contains("task.cancel(true)"));
        assertTrue(source.contains("long previousGeneration = processGeneration;"));
        assertTrue(source.contains("restartCount++;"));
        assertTrue(source.contains("lastColdStartDurationMs = elapsedMillis"));
        assertTrue(source.contains("lastCatalogReadyDurationMs = elapsedMillis"));
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = McpGatewayService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = McpGatewayService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object invokeStartOrJoinRefresh(McpGatewayService service, String projectPath,
                                                    boolean forceRefresh) throws Exception {
        var method = McpGatewayService.class.getDeclaredMethod("startOrJoinRefresh", String.class, boolean.class);
        method.setAccessible(true);
        return method.invoke(service, projectPath, forceRefresh);
    }

    private static void invokeLifecycleState(McpGatewayService service, McpGatewayLifecycleState state,
                                             String failure) throws Exception {
        var method = McpGatewayService.class.getDeclaredMethod("setLifecycleState",
                McpGatewayLifecycleState.class, String.class);
        method.setAccessible(true);
        method.invoke(service, state, failure);
    }

    private static void invokeMarkDegradedDirect(McpGatewayService service, long generation,
                                                  String diagnostic) throws Exception {
        var method = McpGatewayService.class.getDeclaredMethod("markDegradedDirect",
                CompletableFuture.class, long.class, String.class);
        method.setAccessible(true);
        method.invoke(service, null, generation, diagnostic);
    }    private static final class BlockingCollector extends McpGatewayConfigCollector {
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
    private static final class BlockingClient extends McpGatewayBridgeClient {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingClient() {
            super(Path.of(System.getProperty("java.io.tmpdir")).resolve("mcp-gw-lifecycle-block.json"),
                    "test-token");
        }

        @Override
        public JsonObject postSnapshot(McpGatewayConfigSnapshot snapshot) throws InterruptedException {
            entered.countDown();
            release.await();
            return new JsonObject();
        }

        @Override
        public JsonObject stop() {
            return new JsonObject();
        }
    }
}
