package com.github.claudecodegui.cli;

import com.github.claudecodegui.cli.claude.ClaudeCliSession;
import com.github.claudecodegui.cli.codex.CodexCliSession;
import com.github.claudecodegui.cli.opencode.OpenCodeCliSession;
import com.github.claudecodegui.common.CommonConstants;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Temporary local timing probe. Remove after collecting CLI phase logs.
 */
public class CliTimingProbeTest {

    @Test(timeout = 90_000L)
    public void runClaudeCliTimingProbe() {
        run(CommonConstants.PROVIDER_CLAUDE, new ClaudeCliSession("probe-claude"));
    }

    @Test(timeout = 90_000L)
    public void runCodexCliTimingProbe() {
        run(CommonConstants.PROVIDER_CODEX, new CodexCliSession("probe-codex"));
    }

    @Test(timeout = 90_000L)
    public void runOpenCodeCliTimingProbe() {
        run(CommonConstants.PROVIDER_OPENCODE, new OpenCodeCliSession("probe-opencode"));
    }

    private static void run(String provider, CliSession session) {
        CliSendRequest request = new CliSendRequest(
                "probe-" + provider,
                provider,
                "Reply with exactly: ok",
                null,
                System.getProperty("user.dir"),
                List.of(),
                new JsonObject(),
                List.of(),
                null,
                CommonConstants.PERMISSION_MODE_DEFAULT,
                null,
                null,
                null,
                null,
                Map.of()
        );
        long start = System.nanoTime();
        try {
            session.send(request, new ProbeCallback(provider)).get(75, TimeUnit.SECONDS);
        } catch (Exception e) {
            session.interrupt();
            System.out.println("[CliTimingProbe][" + provider + "] exception=" + e.getClass().getName()
                    + ": " + e.getMessage());
        } finally {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            System.out.println("[CliTimingProbe][" + provider + "] totalMs=" + elapsedMs);
            session.dispose();
        }
    }

    private static final class ProbeCallback implements CliSessionCallback {
        private final String provider;

        private ProbeCallback(String provider) {
            this.provider = provider;
        }

        @Override
        public void onMessage(String type, String content) {
            if ("content_delta".equals(type)) {
                return;
            }
            System.out.println("[CliTimingProbe][" + provider + "] message type=" + type);
        }

        @Override
        public void onError(String error) {
            System.out.println("[CliTimingProbe][" + provider + "] error=" + error);
        }

        @Override
        public void onComplete(boolean success, String finalResult, String error) {
            System.out.println("[CliTimingProbe][" + provider + "] complete success=" + success
                    + ", finalChars=" + (finalResult != null ? finalResult.length() : 0)
                    + ", error=" + error);
        }
    }
}
