package com.github.claudecodegui.cli.claude;

import com.github.claudecodegui.bridge.NodeService;
import com.github.claudecodegui.cli.common.CliSettings;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates the temporary {@code --settings <file>} consumed by the Claude CLI
 * (persistent + one-shot launchers) to inject a {@code PreToolUse} hook that bridges
 * CLI tool-permission requests into the plugin's file-IPC + frontend dialog chain.
 *
 * <p>The hook runner is {@code ai-bridge/hooks/pre-tool-use-hook.js}; it reuses
 * {@code canUseTool} to write {@code request-<sid>.json} and block on the response
 * the Java {@code PermissionService} writes after the user decides in the frontend
 * {@code PermissionDialog}. Without this injection, the CLI path never writes a
 * request file, so {@code PermissionRequestWatcher} never fires and no confirmation
 * dialog appears (the root cause of the "needs authorization but no dialog" bug).
 *
 * <p>Content (node executable, hook script path, timeout) is provider/runtime-agnostic
 * and stable across tabs/turns, so a single cached file is reused. The file lives in
 * the system temp dir — not the project {@code .claude/} — to avoid polluting the
 * user's repo. Regenerated only when the resolved values change (e.g. node path shift).
 */
final class ClaudeCliHookSettings {

    private static final Logger LOG = Logger.getInstance(ClaudeCliHookSettings.class);

    private static final String HOOK_SCRIPT_REL = "hooks/pre-tool-use-hook.js";
    private static final String SETTINGS_FILE_NAME = "codemoss-claude-cli-hook-settings.json";

    private ClaudeCliHookSettings() {
    }

    /**
     * @return absolute path to a settings.json whose {@code hooks.PreToolUse} points
     *         at the bundled hook runner, or {@code null} if the node executable or
     *         hook script cannot be resolved (caller falls back to launching without
     *         {@code --settings}, i.e. pre-fix behavior — no dialog, but no crash).
     */
    static String getSettingsPath() {
        try {
            String nodeExec = NodeService.getInstance().getNodeExecutable();
            if (nodeExec == null || nodeExec.isBlank()) {
                LOG.warn("[ClaudeCliHookSettings] Node executable not resolved; skipping hook injection");
                return null;
            }
            File aiBridgeDir = NodeService.getInstance().getBridgeDir();
            if (aiBridgeDir == null || !aiBridgeDir.isDirectory()) {
                LOG.warn("[ClaudeCliHookSettings] ai-bridge directory not resolved; skipping hook injection");
                return null;
            }
            File hookScript = new File(aiBridgeDir, HOOK_SCRIPT_REL.replace('/', File.separatorChar));
            if (!hookScript.isFile()) {
                LOG.warn("[ClaudeCliHookSettings] hook script not found: " + hookScript
                        + "; skipping hook injection (ai-bridge may not be extracted yet)");
                return null;
            }
            long timeoutMs = CliSettings.getClaudePermissionSafetyNetMs();
            String content = buildSettingsJson(nodeExec, hookScript.getAbsolutePath(), timeoutMs);

            Path settingsFile = new File(System.getProperty("java.io.tmpdir"), SETTINGS_FILE_NAME).toPath();
            writeIfChanged(settingsFile, content);
            return settingsFile.toAbsolutePath().toString();
        } catch (Exception e) {
            LOG.warn("[ClaudeCliHookSettings] Failed to build hook settings; skipping injection", e);
            return null;
        }
    }

    /**
     * Build the settings JSON. Paths use forward slashes (node accepts them on all
     * platforms) to avoid JSON backslash-escaping pain; the hook script path is
     * quoted inside the command so spaces in the temp path don't break the shell.
     * The {@code timeout} must exceed the dialog timeout so the CLI doesn't kill the
     * hook subprocess before the user decides — it is set to the permission safety-net
     * window, which already bakes in the dialog timeout + buffer.
     */
    private static String buildSettingsJson(String nodeExec, String hookScriptPath, long timeoutMs) {
        String command = normalizePath(nodeExec) + " \"" + normalizePath(hookScriptPath) + "\"";

        JsonObject hook = new JsonObject();
        hook.addProperty("type", "command");
        hook.addProperty("command", command);
        hook.addProperty("timeout", timeoutMs);

        JsonArray hooksArr = new JsonArray();
        hooksArr.add(hook);

        JsonObject matcherEntry = new JsonObject();
        // Empty matcher matches ALL tool names — every tool call routes through the hook
        // so that read-only tools auto-allow, dangerous paths deny, and everything that
        // needs a decision writes an IPC file and surfaces the frontend dialog.
        matcherEntry.addProperty("matcher", "");
        matcherEntry.add("hooks", hooksArr);

        JsonArray preToolUse = new JsonArray();
        preToolUse.add(matcherEntry);

        JsonObject hooksRoot = new JsonObject();
        hooksRoot.add("PreToolUse", preToolUse);

        JsonObject root = new JsonObject();
        root.add("hooks", hooksRoot);

        Gson gson = GsonHolder.GSON;
        return gson.toJson(root);
    }

    private static String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    private static void writeIfChanged(Path file, String content) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try {
                String existing = Files.exists(file)
                        ? Files.readString(file, StandardCharsets.UTF_8)
                        : null;
                if (content.equals(existing)) {
                    return; // unchanged — avoid touching a file the CLI may be mid-read on
                }
            } catch (Exception e) {
                // ignore read failure; fall through and rewrite
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("[ClaudeCliHookSettings] Failed to write settings file: " + file, e);
        }
    }
}
