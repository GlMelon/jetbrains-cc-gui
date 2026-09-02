package com.github.claudecodegui.ui.toolwindow;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

/**
 * Source-string guard (Platform-coupled code that cannot be unit-tested,
 * mirroring the CliMcpGatewaySymmetryTest / PermissionServiceRefactorTest
 * pattern mandated by AGENTS.md 总则六).
 *
 * <p>Regression for the stuck-streaming bug introduced by the
 * WebviewEventQueue migration: {@code callJavaScript("__lastStreamEndSource",
 * "'backend'")} built {@code window.__lastStreamEndSource(''backend'')} — a
 * parse-time SyntaxError. Before batching this only killed that one (already
 * non-functional) diagnostic call; once calls are concatenated into a single
 * executeJavaScript script, the SyntaxError silently discards the WHOLE batch,
 * including {@code onStreamEnd} and {@code showLoading('false')} batched after
 * it — the frontend then stays in the streaming state (stop button and
 * streaming footer never clear) even though the response-phase "done" card
 * arrives via a separate batch.</p>
 *
 * <p>{@code callJavaScript} args are contractually pre-escaped
 * (JsUtils.escapeJs); a Java string literal starting with a quote character
 * is always a bug of this class.</p>
 */
public class BatchedWebviewCallArgSafetyTest {

    /** Matches callJavaScript( ... "' — an arg literal beginning with a JS quote. */
    private static final Pattern PRE_QUOTED_ARG =
            Pattern.compile("callJavaScript\\([\\s\\S]{0,300}?\"'");

    @Test
    public void noCallJavaScriptSitePassesPreQuotedJsLiteralArgs() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Paths.get("src/main/java"))) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                    if (PRE_QUOTED_ARG.matcher(source).find()) {
                        violations.add(path.toString());
                    }
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
        assertTrue(
                "callJavaScript args must be pre-escaped (JsUtils.escapeJs), never "
                        + "pre-quoted JS literals — a pre-quoted arg is a SyntaxError "
                        + "that kills the whole batched script. Violations: " + violations,
                violations.isEmpty());
    }
}
