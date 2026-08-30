package com.github.claudecodegui.startup;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Source guards for project-bound startup preloads and provider dispatch. */
public class BridgePreloaderLifecycleTest {

    @Test
    public void preloadFutureIsBoundToProjectLifecycle() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/github/claudecodegui/startup/BridgePreloader.java"), StandardCharsets.UTF_8);

        assertTrue(source.contains("Future<?> preloadFuture ="));
        assertTrue(source.contains("Disposer.tryRegister(project, cancelPreload)"));
        assertTrue(source.contains("cancelPreload.dispose()"));
        assertTrue(source.contains("cancelTasks(cliPrewarmTasks)"));
        assertTrue(source.contains("task.cancel(true)"));
        assertTrue(source.contains("project.isDisposed() || Thread.currentThread().isInterrupted()"));
        assertTrue(source.contains("cliPrewarm.cancel(true)"));
    }

    @Test
    public void providerPrewarmUsesExplicitRegistryWithoutProviderDispatchBranches() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/github/claudecodegui/startup/BridgePreloader.java"), StandardCharsets.UTF_8);

        assertTrue(source.contains("ProviderPrewarmRegistry.defaultRegistry()"));
        assertTrue(source.contains("prewarmCliResolvers("));
        assertTrue(source.contains("List<ProviderPrewarmStrategy> strategies = PROVIDER_PREWARM_REGISTRY.strategies()"));
        assertTrue(source.contains("for (ProviderPrewarmStrategy strategy : strategies)"));
        assertFalse(source.contains("prewarmCodexCli"));
        assertFalse(source.contains("prewarmOpenCodeCli"));
        assertFalse(source.contains("prewarmKimiCli"));
        assertFalse(source.contains("prewarmGrokCli"));
        assertFalse(source.contains("prewarmPiCli"));
    }

    @Test
    public void gatewayPrewarmChecksLifecycleBeforeProjectServiceAccess() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/github/claudecodegui/startup/BridgePreloader.java"), StandardCharsets.UTF_8);

        int lifecycleGate = source.indexOf("if (project.isDisposed() || Thread.currentThread().isInterrupted()");
        int serviceAccess = source.indexOf("McpGatewayService.getInstance(project)", lifecycleGate);
        int secondGate = source.indexOf("if (project.isDisposed() || Thread.currentThread().isInterrupted())", serviceAccess);
        int refresh = source.indexOf("gatewayService.refreshConfig(project.getBasePath())", secondGate);
        assertTrue(lifecycleGate >= 0 && serviceAccess > lifecycleGate);
        assertTrue(secondGate > serviceAccess && refresh > secondGate);
    }
}
