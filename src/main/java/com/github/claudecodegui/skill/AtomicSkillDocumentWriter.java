package com.github.claudecodegui.skill;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Same-directory temporary write with a retained backup and rollback support. */
final class AtomicSkillDocumentWriter implements SkillDocumentWriter {

    private static final String BACKUP_SUFFIX = ".codemoss.bak";
    private static final String TEMP_PREFIX = ".codemoss-skill-";
    private static final String TEMP_SUFFIX = ".tmp";

    @Override
    public Path write(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("Skill document has no parent directory");
        }
        Path backup = target.resolveSibling(target.getFileName() + BACKUP_SUFFIX);
        Path temporary = Files.createTempFile(parent, TEMP_PREFIX, TEMP_SUFFIX);
        boolean backupCreated = false;
        try {
            writeAndSync(temporary, content);
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
            backupCreated = true;
            replace(temporary, target);
            return backup;
        } catch (IOException e) {
            if (backupCreated) {
                restore(target, backup);
            }
            throw e;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void restore(Path target, Path backup) throws IOException {
        if (backup == null || !Files.isRegularFile(backup)) {
            throw new IOException("Skill document backup is unavailable");
        }
        Path parent = target.getParent();
        Path temporary = Files.createTempFile(parent, TEMP_PREFIX, TEMP_SUFFIX);
        try {
            Files.copy(backup, temporary, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
            replace(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void writeAndSync(Path target, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

}
