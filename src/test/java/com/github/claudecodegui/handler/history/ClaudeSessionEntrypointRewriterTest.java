package com.github.claudecodegui.handler.history;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClaudeSessionEntrypointRewriterTest {

    private static final String SESSION_ID = "423c2eb9-c014-443d-8067-190445103d18";

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void automaticCliRewriteOnlyAcceptsSdkCliEntrypoint() throws Exception {
        Path projectsDir = temporaryFolder.newFolder("projects").toPath();
        Path projectDir = Files.createDirectory(projectsDir.resolve("project"));
        Path sessionFile = projectDir.resolve(SESSION_ID + ".jsonl");
        Files.writeString(
                sessionFile,
                "{\"sessionId\":\"" + SESSION_ID + "\",\"entrypoint\":\"sdk-cli\"}\n"
                        + "{\"entrypoint\":\"claude-vscode\"}\n",
                StandardCharsets.UTF_8
        );
        ClaudeSessionEntrypointRewriter rewriter = rewriter(projectsDir);

        ClaudeSessionEntrypointRewriter.RewriteResult result = rewriter.rewrite(
                SESSION_ID,
                null,
                Set.of(SessionEntrypoint.SDK_CLI),
                SessionEntrypoint.CLI
        );

        assertEquals(ClaudeSessionEntrypointRewriter.RewriteStatus.REWRITTEN, result.status());
        assertEquals(1, result.modifiedCount());
        String rewritten = Files.readString(sessionFile, StandardCharsets.UTF_8);
        assertTrue(rewritten.contains("\"entrypoint\":\"cli\""));
        assertTrue(rewritten.contains("\"entrypoint\":\"claude-vscode\""));
        assertFalse(rewritten.contains("\"entrypoint\":\"sdk-cli\""));
    }

    @Test
    public void reportsAlreadyTargetWithoutReplacingContent() throws Exception {
        Path projectsDir = temporaryFolder.newFolder("already-target").toPath();
        Path projectDir = Files.createDirectory(projectsDir.resolve("project"));
        Path sessionFile = projectDir.resolve(SESSION_ID + ".jsonl");
        String original = "{\"entrypoint\":\"cli\",\"message\":\"unchanged\"}\n";
        Files.writeString(sessionFile, original, StandardCharsets.UTF_8);

        ClaudeSessionEntrypointRewriter.RewriteResult result = rewriter(projectsDir).rewrite(
                SESSION_ID,
                null,
                Set.of(SessionEntrypoint.SDK_CLI),
                SessionEntrypoint.CLI
        );

        assertEquals(ClaudeSessionEntrypointRewriter.RewriteStatus.ALREADY_TARGET, result.status());
        assertTrue(result.success());
        assertEquals(original, Files.readString(sessionFile, StandardCharsets.UTF_8));
    }

    @Test
    public void rejectsUnacceptedSourceWithoutChangingFile() throws Exception {
        Path projectsDir = temporaryFolder.newFolder("source-rejected").toPath();
        Path projectDir = Files.createDirectory(projectsDir.resolve("project"));
        Path sessionFile = projectDir.resolve(SESSION_ID + ".jsonl");
        String original = "{\"entrypoint\":\"claude-vscode\"}\n";
        Files.writeString(sessionFile, original, StandardCharsets.UTF_8);

        ClaudeSessionEntrypointRewriter.RewriteResult result = rewriter(projectsDir).rewrite(
                SESSION_ID,
                null,
                Set.of(SessionEntrypoint.SDK_CLI),
                SessionEntrypoint.CLI
        );

        assertEquals(ClaudeSessionEntrypointRewriter.RewriteStatus.SOURCE_NOT_ACCEPTED, result.status());
        assertFalse(result.success());
        assertEquals(original, Files.readString(sessionFile, StandardCharsets.UTF_8));
    }

    @Test
    public void reportsLockedFileWithoutChangingIt() throws Exception {
        Path projectsDir = temporaryFolder.newFolder("locked").toPath();
        Path projectDir = Files.createDirectory(projectsDir.resolve("project"));
        Path sessionFile = projectDir.resolve(SESSION_ID + ".jsonl");
        String original = "{\"entrypoint\":\"sdk-cli\"}\n";
        Files.writeString(sessionFile, original, StandardCharsets.UTF_8);

        try (FileChannel channel = FileChannel.open(sessionFile, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            ClaudeSessionEntrypointRewriter.RewriteResult result = rewriter(projectsDir).rewrite(
                    SESSION_ID,
                    null,
                    Set.of(SessionEntrypoint.SDK_CLI),
                    SessionEntrypoint.CLI
            );

            assertEquals(ClaudeSessionEntrypointRewriter.RewriteStatus.FILE_LOCKED, result.status());
        }
        assertEquals(original, Files.readString(sessionFile, StandardCharsets.UTF_8));
    }

    @Test
    public void rejectsInvalidSessionIdBeforeScanning() {
        ClaudeSessionEntrypointRewriter.RewriteResult result = rewriter(Path.of("missing")).rewrite(
                "../invalid",
                null,
                Set.of(SessionEntrypoint.SDK_CLI),
                SessionEntrypoint.CLI
        );

        assertEquals(ClaudeSessionEntrypointRewriter.RewriteStatus.INVALID_SESSION_ID, result.status());
    }

    private static ClaudeSessionEntrypointRewriter rewriter(Path projectsDir) {
        return new ClaudeSessionEntrypointRewriter(() -> projectsDir, path -> path);
    }
}
