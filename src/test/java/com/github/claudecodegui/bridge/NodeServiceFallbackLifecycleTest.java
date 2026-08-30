package com.github.claudecodegui.bridge;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for the hand-rolled NodeService fallback lifecycle.
 */
public class NodeServiceFallbackLifecycleTest {

    @Before
    public void setUp() {
        NodeService.resetInstance();
    }

    @After
    public void tearDown() {
        NodeService.resetInstance();
    }

    @Test
    public void fallbackRegistersWithAvailableDisposableOwner() {
        Disposable owner = Disposer.newDisposable("node-service-fallback-test-owner");
        NodeService fallback = NodeService.getFallbackInstanceForTest(null);
        ProcessManager manager = fallback.getProcessManager();

        assertSame(fallback, NodeService.getFallbackInstanceForTest(owner));
        assertTrue(manager.isStaleChannelSweeperActiveForTest());
        assertTrue(NodeService.hasFallbackInstanceForTest());

        Disposer.dispose(owner);

        assertFalse(manager.isStaleChannelSweeperActiveForTest());
        assertEquals(0, manager.getTrackedStateSizeForTest());
        assertFalse(NodeService.hasFallbackInstanceForTest());
    }

    @Test
    public void resetDisposesStartedProcessSweeperAndRegistry() throws Exception {
        NodeService fallback = NodeService.getFallbackInstanceForTest(null);
        ProcessManager manager = fallback.getProcessManager();
        Process child = startBlockingChild();
        String channelId = ProcessManager.newChannelId("fallback-reset");

        try {
            assertFalse(child.waitFor(200, TimeUnit.MILLISECONDS));
            manager.registerProcess(channelId, child);
            assertEquals(1, manager.getActiveProcessCount());
            assertTrue(manager.getTrackedStateSizeForTest() > 0);
            assertTrue(manager.isStaleChannelSweeperActiveForTest());

            NodeService.resetInstance();

            assertTrue("reset must terminate the tracked child", child.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, manager.getActiveProcessCount());
            assertEquals(0, manager.getTrackedStateSizeForTest());
            assertFalse(manager.isStaleChannelSweeperActiveForTest());
            assertFalse(NodeService.hasFallbackInstanceForTest());
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static Process startBlockingChild() throws Exception {
        String javaCommand = ProcessHandle.current().info().command()
                .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
        return new ProcessBuilder(
                javaCommand,
                "-cp",
                System.getProperty("java.class.path"),
                BlockingChild.class.getName())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    public static final class BlockingChild {
        private BlockingChild() {
        }

        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(TimeUnit.MINUTES.toMillis(5));
        }
    }
}