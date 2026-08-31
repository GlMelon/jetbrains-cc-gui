package com.github.claudecodegui.session;

import com.github.claudecodegui.session.runtime.ProviderType;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Source contract guard for pending tool diagnostics across all provider routes. */
public class PendingInteractionDiagnosticsSymmetryTest {

    private static final Path SESSION_ROOT = Path.of(
            "src/main/java/com/github/claudecodegui/session");

    @Test
    public void allProviderRoutesRemainCoveredByDiagnosticsLifecycle() throws IOException {
        String source = read("SessionSendService.java");

        assertContains(source,
                "private CompletableFuture<Void> sendToClaude(",
                "private CompletableFuture<Void> sendToCodex(",
                "private CompletableFuture<Void> sendToCodexProtocolProvider(",
                "registerToolDiagnosticsSource()",
                "sendWithToolDiagnostics(");
        assertTrue("all three send families must register a diagnostics source",
                count(source, "registerToolDiagnosticsSource();") >= 3);
        assertTrue("all three send families must close through the shared future wrapper",
                count(source, "sendWithToolDiagnostics(") >= 4);
        assertEquals("provider route coverage changed", 8, ProviderType.values().length);
    }

    @Test
    public void claudeAndCodexProtocolHandlersPreserveObserverAcrossLedgerReset() throws IOException {
        String claude = read("ClaudeMessageHandler.java");
        String codex = read("CodexMessageHandler.java");

        assertTrue("Claude handler must initialize and reset with the diagnostics observer",
                count(claude, "new MessageBlockContract.ToolLedger(toolDiagnosticsObserver)") >= 2);
        assertTrue("Codex protocol handler must initialize and reset with the diagnostics observer",
                count(codex, "new MessageBlockContract.ToolLedger(toolDiagnosticsObserver)") >= 2);
    }

    private static String read(String fileName) throws IOException {
        Path path = SESSION_ROOT.resolve(fileName);
        assertTrue("source file must exist: " + path.toAbsolutePath(), Files.isRegularFile(path));
        return Files.readString(path);
    }

    private static void assertContains(String source, String... fragments) {
        for (String fragment : fragments) {
            assertTrue("source must contain diagnostics contract: " + fragment,
                    source.contains(fragment));
        }
    }

    private static int count(String source, String fragment) {
        int occurrences = 0;
        int offset = 0;
        while ((offset = source.indexOf(fragment, offset)) >= 0) {
            occurrences++;
            offset += fragment.length();
        }
        return occurrences;
    }
}
