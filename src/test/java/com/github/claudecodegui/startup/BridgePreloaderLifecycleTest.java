package com.github.claudecodegui.startup;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

/** Source guard for Project-bound startup preloads that depend on IntelliJ Platform scheduling. */
public class BridgePreloaderLifecycleTest {

    @Test
    public void preloadFutureIsBoundToProjectLifecycle() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/github/claudecodegui/startup/BridgePreloader.java"), StandardCharsets.UTF_8);

        assertTrue(source.contains("Future<?> preloadFuture ="));
        assertTrue(source.contains("Disposer.tryRegister(project, () -> preloadFuture.cancel(true))"));
        assertTrue(source.contains("return project.isDisposed() || Thread.currentThread().isInterrupted()"));
        assertTrue(source.contains("cliPrewarm.cancel(true)"));
    }

    @Test
    public void gatewayPrewarmChecksLifecycleBeforeProjectServiceAccess() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/github/claudecodegui/startup/BridgePreloader.java"), StandardCharsets.UTF_8);

        int lifecycleGate = source.indexOf("if (shouldStopPreload(project) || !McpGatewayFeatureFlags.isGatewayActive())");
        int serviceAccess = source.indexOf("McpGatewayService.getInstance(project)", lifecycleGate);
        int secondGate = source.indexOf("if (shouldStopPreload(project))", serviceAccess);
        int refresh = source.indexOf("gatewayService.refreshConfig(project.getBasePath())", secondGate);
        assertTrue(lifecycleGate >= 0 && serviceAccess > lifecycleGate);
        assertTrue(secondGate > serviceAccess && refresh > secondGate);
    }
}
