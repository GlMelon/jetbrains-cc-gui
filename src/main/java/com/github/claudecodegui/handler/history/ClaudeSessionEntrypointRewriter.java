package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.util.PathUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedWriter;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Rewrites Claude Code session entrypoints after the writer process has exited.
 */
public final class ClaudeSessionEntrypointRewriter {

    private static final Logger LOG = Logger.getInstance(ClaudeSessionEntrypointRewriter.class);

    private final Gson gson = new Gson();
    private final Supplier<Path> projectsDirSupplier;
    private final UnaryOperator<String> projectPathResolver;

    public ClaudeSessionEntrypointRewriter() {
        this(
                () -> Paths.get(NodeDetector.resolveHomeForFileOps(), ".claude", "projects"),
                ClaudeSessionEntrypointRewriter::resolveProjectPathForFileOps
        );
    }

    ClaudeSessionEntrypointRewriter(
            Supplier<Path> projectsDirSupplier,
            UnaryOperator<String> projectPathResolver
    ) {
        this.projectsDirSupplier = Objects.requireNonNull(projectsDirSupplier);
        this.projectPathResolver = Objects.requireNonNull(projectPathResolver);
    }

    /**
     * Rewrites accepted source entrypoints to the requested target.
     *
     * @param sessionId Claude session UUID
     * @param projectPath project path used to narrow file lookup; all projects are scanned as fallback
     * @param acceptedSources source entrypoints eligible for rewriting
     * @param target target entrypoint
     * @return structured rewrite result
     */
    public RewriteResult rewrite(
            String sessionId,
            String projectPath,
            Set<SessionEntrypoint> acceptedSources,
            SessionEntrypoint target
    ) {
        if (!HistoryDeleteService.isValidSessionId(sessionId)) {
            return new RewriteResult(RewriteStatus.INVALID_SESSION_ID, 0, null);
        }
        if (acceptedSources == null || acceptedSources.isEmpty() || target == null
                || target == SessionEntrypoint.UNKNOWN || target.getValue() == null) {
            return new RewriteResult(RewriteStatus.FAILED, 0, null);
        }

        Path sessionFile = null;
        Path tempFile = null;
        Path backupFile = null;
        FileLock fileLock = null;
        FileChannel fileChannel = null;

        try {
            sessionFile = this.findSessionFile(sessionId, projectPath);
            if (sessionFile == null) {
                return new RewriteResult(RewriteStatus.SESSION_NOT_FOUND, 0, null);
            }
            if (!Files.exists(sessionFile)) {
                return new RewriteResult(RewriteStatus.FILE_NOT_EXIST, 0, sessionFile);
            }

            try {
                fileChannel = FileChannel.open(
                        sessionFile,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE
                );
                fileLock = fileChannel.tryLock();
                if (fileLock == null) {
                    return new RewriteResult(RewriteStatus.FILE_LOCKED, 0, sessionFile);
                }
            } catch (OverlappingFileLockException e) {
                return new RewriteResult(RewriteStatus.FILE_LOCKED, 0, sessionFile);
            }

            Path sessionDir = sessionFile.getParent();
            backupFile = Files.createTempFile(sessionDir, sessionId + ".jsonl.backup.", ".tmp");
            copyLockedFile(fileChannel, backupFile);
            tempFile = Files.createTempFile(sessionDir, sessionId + ".jsonl.rewrite.", ".tmp");

            RewriteSummary summary = this.rewriteFile(
                    fileChannel,
                    tempFile,
                    acceptedSources,
                    target
            );
            if (summary.modifiedCount() == 0) {
                RewriteStatus status = summary.hasTargetEntrypoint()
                        ? RewriteStatus.ALREADY_TARGET
                        : RewriteStatus.SOURCE_NOT_ACCEPTED;
                return new RewriteResult(status, 0, sessionFile);
            }

            releaseFileLock(fileLock, fileChannel);
            Files.move(tempFile, sessionFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            tempFile = null;
            return new RewriteResult(RewriteStatus.REWRITTEN, summary.modifiedCount(), sessionFile);
        } catch (Exception e) {
            LOG.warn("[ClaudeSessionEntrypointRewriter] Failed to rewrite session " + sessionId, e);
            releaseFileLock(fileLock, fileChannel);
            restoreBackup(backupFile, sessionFile);
            return new RewriteResult(RewriteStatus.FAILED, 0, sessionFile);
        } finally {
            releaseFileLock(fileLock, fileChannel);
            deleteTemporaryFile(tempFile);
            deleteTemporaryFile(backupFile);
        }
    }

    private RewriteSummary rewriteFile(
            FileChannel sessionChannel,
            Path tempFile,
            Set<SessionEntrypoint> acceptedSources,
            SessionEntrypoint target
    ) throws IOException {
        int modifiedCount = 0;
        boolean hasTargetEntrypoint = false;

        sessionChannel.position(0);
        try (BufferedReader reader = new BufferedReader(Channels.newReader(sessionChannel, StandardCharsets.UTF_8));
             BufferedWriter writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                LineRewrite lineRewrite = this.rewriteLine(line, acceptedSources, target);
                writer.write(lineRewrite.line());
                writer.newLine();
                if (lineRewrite.modified()) {
                    modifiedCount++;
                }
                if (lineRewrite.hasTargetEntrypoint()) {
                    hasTargetEntrypoint = true;
                }
            }
        }

        return new RewriteSummary(modifiedCount, hasTargetEntrypoint);
    }

    LineRewrite rewriteLine(
            String line,
            Set<SessionEntrypoint> acceptedSources,
            SessionEntrypoint target
    ) {
        JsonObject row;
        try {
            row = this.gson.fromJson(line, JsonObject.class);
        } catch (JsonSyntaxException | IllegalStateException e) {
            return new LineRewrite(line, false, false);
        }

        if (row == null || !row.has("entrypoint") || row.get("entrypoint").isJsonNull()
                || !row.get("entrypoint").isJsonPrimitive()) {
            return new LineRewrite(line, false, false);
        }

        SessionEntrypoint current = SessionEntrypoint.fromValue(row.get("entrypoint").getAsString());
        if (current == target) {
            return new LineRewrite(line, false, true);
        }
        if (!acceptedSources.contains(current)) {
            return new LineRewrite(line, false, false);
        }

        row.addProperty("entrypoint", target.getValue());
        return new LineRewrite(this.gson.toJson(row), true, false);
    }

    private Path findSessionFile(String sessionId, String projectPath) throws IOException {
        Path projectsDir = this.projectsDirSupplier.get();
        if (projectPath != null && !projectPath.isBlank()) {
            String resolvedProjectPath = this.projectPathResolver.apply(projectPath);
            if (resolvedProjectPath != null && !resolvedProjectPath.isBlank()) {
                Path projectDir = projectsDir.resolve(PathUtils.sanitizePath(resolvedProjectPath));
                Path candidate = projectDir.resolve(sessionId + ".jsonl");
                if (Files.exists(candidate)) {
                    return candidate;
                }
            }
        }

        if (!Files.exists(projectsDir)) {
            return null;
        }
        try (var stream = Files.newDirectoryStream(projectsDir)) {
            for (Path projectDir : stream) {
                if (!Files.isDirectory(projectDir)) {
                    continue;
                }
                Path candidate = projectDir.resolve(sessionId + ".jsonl");
                if (Files.exists(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String resolveProjectPathForFileOps(String rawProjectPath) {
        String nodePath = NodeDetector.getInstance().getCachedNodePath();
        return NodeDetector.isWslPath(nodePath)
                ? NodeDetector.convertToWslPath(rawProjectPath)
                : rawProjectPath;
    }

    private static void copyLockedFile(FileChannel source, Path target) throws IOException {
        source.position(0);
        try (FileChannel destination = FileChannel.open(
                target,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            long offset = 0;
            long size = source.size();
            while (offset < size) {
                offset += source.transferTo(offset, size - offset, destination);
            }
        }
        source.position(0);
    }

    private static void restoreBackup(Path backupFile, Path sessionFile) {
        if (backupFile == null || sessionFile == null || !Files.exists(backupFile)) {
            return;
        }
        try {
            Files.move(backupFile, sessionFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException restoreError) {
            LOG.error("[ClaudeSessionEntrypointRewriter] Failed to restore session backup", restoreError);
        }
    }

    private static void deleteTemporaryFile(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupError) {
            LOG.warn("[ClaudeSessionEntrypointRewriter] Failed to clean up temporary file: " + path);
        }
    }

    private static void releaseFileLock(FileLock fileLock, FileChannel fileChannel) {
        try {
            if (fileLock != null && fileLock.isValid()) {
                fileLock.release();
            }
            if (fileChannel != null && fileChannel.isOpen()) {
                fileChannel.close();
            }
        } catch (IOException lockError) {
            LOG.warn("[ClaudeSessionEntrypointRewriter] Failed to release file lock", lockError);
        }
    }

    public enum RewriteStatus {
        INVALID_SESSION_ID,
        SESSION_NOT_FOUND,
        FILE_NOT_EXIST,
        FILE_LOCKED,
        ALREADY_TARGET,
        SOURCE_NOT_ACCEPTED,
        REWRITTEN,
        FAILED
    }

    public record RewriteResult(RewriteStatus status, int modifiedCount, Path sessionFile) {
        public boolean success() {
            return this.status == RewriteStatus.REWRITTEN || this.status == RewriteStatus.ALREADY_TARGET;
        }
    }

    record LineRewrite(String line, boolean modified, boolean hasTargetEntrypoint) {
    }

    private record RewriteSummary(int modifiedCount, boolean hasTargetEntrypoint) {
    }
}
