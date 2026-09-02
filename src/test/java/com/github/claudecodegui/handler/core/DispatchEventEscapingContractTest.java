package com.github.claudecodegui.handler.core;

import com.github.claudecodegui.session.SessionCallbackAdapter;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for the dispatchEvent payload-escaping SSOT (AGENTS.md 总则三:
 * 序列化出口统一).
 *
 * <p>Contract: {@code dispatchEvent(type, payload)} receives the RAW payload
 * (plain JSON or bare text); the exit points ({@code HandlerContext.JsCallback}
 * default, {@code SessionCallbackAdapter.JsTarget} default, and
 * {@code ClaudeChatWindow#dispatchEvent}) escape it exactly once via
 * {@code JsUtils.escapeJs} before embedding it into the batched
 * {@code callJavaScript("window.__bridge.dispatch", ...)} argument.</p>
 *
 * <p>Rationale (the stuck-streaming regression this guards against): before the
 * contract existed, every caller escaped by hand — and the ones that forgot
 * produced a parse-time SyntaxError inside the WebviewEventQueue batch script,
 * silently discarding every event batched after them. Centralizing the escape
 * removes the per-caller obligation; the guards below keep it centralized:</p>
 *
 * <ul>
 *   <li>behavioral: the interface defaults escape quotes / newlines / null;</li>
 *   <li>guard A: no caller may pre-escape (double-escaping corrupts payloads);</li>
 *   <li>guard B: every direct {@code window.__bridge.dispatch} builder must
 *       escape — i.e. only the three sanctioned exits may build that call.</li>
 * </ul>
 */
public class DispatchEventEscapingContractTest {

    private static final class RecordingJsCallback implements HandlerContext.JsCallback {
        String function;
        String[] args;

        @Override
        public void callJavaScript(String functionName, String... args) {
            this.function = functionName;
            this.args = args;
        }

        @Override
        public String escapeJs(String str) {
            throw new AssertionError(
                    "dispatchEvent callers must not call escapeJs anymore — the exit escapes centrally");
        }
    }

    @Test
    public void jsCallbackDefaultEscapesPayloadOnce() {
        RecordingJsCallback callback = new RecordingJsCallback();
        callback.dispatchEvent("toast.error", "it's a \"quote\"\nand a\ttab");
        assertEquals("window.__bridge.dispatch", callback.function);
        assertEquals("toast.error", callback.args[0]);
        // Escaped form: quote -> \' , newline -> \n , tab -> \t ; embedded in single quotes
        assertEquals("it\\'s a \\\"quote\\\"\\nand a\\ttab", callback.args[1]);
    }

    @Test
    public void jsCallbackDefaultNormalizesNullPayloadToEmptyString() {
        RecordingJsCallback callback = new RecordingJsCallback();
        callback.dispatchEvent("clipboard.read", null);
        assertEquals("", callback.args[1]);
    }

    @Test
    public void sessionAdapterJsTargetDefaultEscapesPayloadOnce() {
        final String[] captured = new String[3];
        SessionCallbackAdapter.JsTarget target = new SessionCallbackAdapter.JsTarget() {
            @Override
            public void callJavaScript(String functionName, String... args) {
                captured[0] = functionName;
                captured[1] = args.length > 0 ? args[0] : null;
                captured[2] = args.length > 1 ? args[1] : null;
            }
        };
        target.dispatchEvent("stream.response_phase", "{\"title\":\"done\"}");
        assertEquals("window.__bridge.dispatch", captured[0]);
        assertEquals("stream.response_phase", captured[1]);
        assertEquals("{\\\"title\\\":\\\"done\\\"}", captured[2]);
    }

    // ===== Source guards (Platform-coupled exits cannot be unit-tested directly) =====

    /** Guard A: no `.dispatchEvent(...)` invocation statement may contain escapeJs. */
    @Test
    public void noDispatchEventCallerPreEscapesPayload() throws IOException {
        Pattern invocation = Pattern.compile("\\.dispatchEvent\\(");
        List<String> violations = new ArrayList<>();
        for (Path path : javaSources()) {
            String source = read(path);
            Matcher matcher = invocation.matcher(source);
            while (matcher.find()) {
                int statementEnd = source.indexOf(';', matcher.end());
                int limit = statementEnd == -1 ? Math.min(source.length(), matcher.end() + 500) : statementEnd;
                String statement = source.substring(matcher.start(), limit);
                if (statement.contains("escapeJs")) {
                    violations.add(path + " :: " + statement.split("\n")[0].trim());
                }
            }
        }
        assertTrue(
                "dispatchEvent callers must pass RAW payloads (the exit escapes centrally); "
                        + "pre-escaping double-escapes and corrupts the payload. Violations: " + violations,
                violations.isEmpty());
    }

    /** Guard B: every direct window.__bridge.dispatch builder must escape its payload. */
    @Test
    public void everyDirectBridgeDispatchBuilderEscapesPayload() throws IOException {
        Pattern builder = Pattern.compile("callJavaScript\\(\"window\\.__bridge\\.dispatch\"");
        List<String> violations = new ArrayList<>();
        for (Path path : javaSources()) {
            String source = read(path);
            Matcher matcher = builder.matcher(source);
            while (matcher.find()) {
                int statementEnd = source.indexOf(';', matcher.end());
                int limit = statementEnd == -1 ? Math.min(source.length(), matcher.end() + 500) : statementEnd;
                String statement = source.substring(matcher.start(), limit);
                if (!statement.contains("escapeJs")) {
                    violations.add(path + " :: " + statement.split("\n")[0].trim());
                }
            }
        }
        assertTrue(
                "window.__bridge.dispatch must only be built by the sanctioned dispatchEvent exits, "
                        + "each escaping the payload exactly once. Violations: " + violations,
                violations.isEmpty());
    }

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> files = Files.walk(Paths.get("src/main/java"))) {
            List<Path> sources = new ArrayList<>();
            files.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
            return sources;
        }
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
