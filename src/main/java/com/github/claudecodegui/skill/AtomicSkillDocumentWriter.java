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
import java.nio.file.attribute.PosixFileAttributes;

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
        // SKILL-02: Files.createTempFile 默认 0600,ATOMIC_MOVE 后 live 文件继承 temp 属性而非原文件
        //(如 0644 → 0600)。replace 前快照原 POSIX 权限,replace 后回写(非 POSIX 文件系统跳过)。
        PosixFileAttributes originalAttributes = snapshotPosixAttributes(target);
        try {
            writeAndSync(temporary, content);
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
            backupCreated = true;
            replace(temporary, target);
            restorePosixAttributes(target, originalAttributes);
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
        // SKILL-02: backup 经 COPY_ATTRIBUTES 保留原权限,replace 后回写到 target。
        PosixFileAttributes backupAttributes = snapshotPosixAttributes(backup);
        try {
            Files.copy(backup, temporary, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
            replace(temporary, target);
            restorePosixAttributes(target, backupAttributes);
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

    /** SKILL-02: 快照 POSIX 权限(非 POSIX 文件系统如 Windows/NTFS 或文件不存在时返回 null,跳过回写)。 */
    private static PosixFileAttributes snapshotPosixAttributes(Path path) {
        try {
            return Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException | IOException e) {
            return null;
        }
    }

    /** SKILL-02: 把快照的 POSIX 权限回写到目标文件(非 POSIX 文件系统跳过)。 */
    private static void restorePosixAttributes(Path path, PosixFileAttributes snapshot) throws IOException {
        if (snapshot == null) {
            return;
        }
        try {
            Files.setPosixFilePermissions(path, snapshot.permissions());
        } catch (UnsupportedOperationException e) {
            // 非 POSIX 文件系统(Windows),权限模型不同,无 POSIX 权限可设,跳过。
        }
    }

}
