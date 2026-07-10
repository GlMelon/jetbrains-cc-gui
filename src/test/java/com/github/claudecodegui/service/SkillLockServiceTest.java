package com.github.claudecodegui.service;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link SkillLockService} 纯函数单测(锁读写 + SHA-256 哈希校验)。
 * <p>不测生产便捷入口 recordInstall/removeRecord/verifyHash(读真实 home,留端到端);
 * 覆盖 defaultLockPath/readLock/recordSkill/removeSkill/isHashMatched/computeSha256/writeLock
 * 的边界与篡改检测。
 */
public class SkillLockServiceTest {

    // ── defaultLockPath ──

    @Test
    public void defaultLockPathAppendsClaudeSkillsLock() {
        Path p = SkillLockService.defaultLockPath("/home/user");
        assertEquals(Path.of("/home/user", ".claude", "skills-lock.json"), p);
    }

    // ── readLock ──

    @Test
    public void readLockMissingFileReturnsEmptyLock() {
        JsonObject lock = SkillLockService.readLock(Path.of("/nonexistent", "lock.json"));
        assertTrue(lock.has("skills"));
        assertTrue(lock.has("version"));
        assertEquals(0, lock.getAsJsonObject("skills").size());
    }

    @Test
    public void readLockMissingSkillsFieldFillsEmpty() throws Exception {
        Path tmp = Files.createTempFile("skilllock", ".json");
        Files.writeString(tmp, "{\"version\":1}");
        try {
            JsonObject lock = SkillLockService.readLock(tmp);
            assertTrue(lock.has("skills"));
            assertEquals(0, lock.getAsJsonObject("skills").size());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void readLockIllegalJsonReturnsEmptyLock() throws Exception {
        Path tmp = Files.createTempFile("skilllock-bad", ".json");
        Files.writeString(tmp, "not json");
        try {
            JsonObject lock = SkillLockService.readLock(tmp);
            assertTrue(lock.has("skills"));
            assertEquals(0, lock.getAsJsonObject("skills").size());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ── recordSkill ──

    @Test
    public void recordSkillAddsEntryWithAllFields() {
        JsonObject lock = SkillLockService.emptyLock();
        SkillLockService.recordSkill(lock, "pdf", "anthropics", "github", "abc123", "global");
        JsonObject skills = lock.getAsJsonObject("skills");
        assertTrue(skills.has("pdf"));
        JsonObject entry = skills.getAsJsonObject("pdf");
        assertEquals("anthropics", entry.get("source").getAsString());
        assertEquals("github", entry.get("sourceType").getAsString());
        assertEquals("abc123", entry.get("computedHash").getAsString());
        assertEquals("global", entry.get("scope").getAsString());
    }

    @Test
    public void recordSkillNullSourceTypeDefaultsToGithub() {
        JsonObject lock = SkillLockService.emptyLock();
        SkillLockService.recordSkill(lock, "x", "s", null, null, null);
        JsonObject entry = lock.getAsJsonObject("skills").getAsJsonObject("x");
        assertEquals("github", entry.get("sourceType").getAsString());
        assertFalse(entry.has("computedHash"));
        assertFalse(entry.has("scope"));
    }

    @Test
    public void recordSkillOverwritesExistingEntry() {
        JsonObject lock = SkillLockService.emptyLock();
        SkillLockService.recordSkill(lock, "pdf", "old", "github", "h1", "global");
        SkillLockService.recordSkill(lock, "pdf", "new", "github", "h2", "local");
        JsonObject entry = lock.getAsJsonObject("skills").getAsJsonObject("pdf");
        assertEquals("new", entry.get("source").getAsString());
        assertEquals("h2", entry.get("computedHash").getAsString());
        assertEquals("local", entry.get("scope").getAsString());
    }

    // ── removeSkill ──

    @Test
    public void removeSkillRemovesEntry() {
        JsonObject lock = SkillLockService.emptyLock();
        SkillLockService.recordSkill(lock, "pdf", "anthropics", "github", "h", "global");
        SkillLockService.recordSkill(lock, "docx", "anthropics", "github", "h2", "global");
        SkillLockService.removeSkill(lock, "pdf");
        JsonObject skills = lock.getAsJsonObject("skills");
        assertFalse(skills.has("pdf"));
        assertTrue(skills.has("docx"));
    }

    @Test
    public void removeSkillMissingNameNoop() {
        JsonObject lock = SkillLockService.emptyLock();
        SkillLockService.removeSkill(lock, "nonexistent");
        assertEquals(0, lock.getAsJsonObject("skills").size());
    }

    // ── isHashMatched ──

    @Test
    public void isHashMatchedFirstInstallReturnsTrue() {
        JsonObject lock = SkillLockService.emptyLock();
        assertTrue(SkillLockService.isHashMatched(lock, "newskill", "anyhash"));
    }

    @Test
    public void isHashMatchedMatchingHashReturnsTrue() {
        JsonObject lock = SkillLockService.emptyLock();
        SkillLockService.recordSkill(lock, "pdf", "s", "g", "abc", "global");
        assertTrue(SkillLockService.isHashMatched(lock, "pdf", "abc"));
    }

    @Test
    public void isHashMatchedMismatchedHashReturnsFalseTamperDetected() {
        JsonObject lock = SkillLockService.emptyLock();
        SkillLockService.recordSkill(lock, "pdf", "s", "g", "abc", "global");
        assertFalse("篡改的哈希应被检测拒绝", SkillLockService.isHashMatched(lock, "pdf", "tampered"));
    }

    @Test
    public void isHashMatchedEntryWithoutHashReturnsTrue() {
        // 锁记录存在但无 computedHash 字段(旧版锁)→ 放行
        JsonObject lock = SkillLockService.emptyLock();
        SkillLockService.recordSkill(lock, "pdf", "s", null, null, null);
        assertTrue(SkillLockService.isHashMatched(lock, "pdf", "anyhash"));
    }

    // ── computeSha256 ──

    @Test
    public void computeSha256KnownContent() throws Exception {
        // SHA-256("hello") 标准已知值
        Path f = Files.createTempFile("sha256", ".txt");
        Files.writeString(f, "hello");
        try {
            assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                SkillLockService.computeSha256(f));
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    public void computeSha256EmptyFile() throws Exception {
        Path f = Files.createTempFile("sha256empty", ".txt");
        // 空文件 SHA-256 标准已知值
        try {
            assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                SkillLockService.computeSha256(f));
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test(expected = IOException.class)
    public void computeSha256MissingFileThrows() throws Exception {
        SkillLockService.computeSha256(Path.of("/nonexistent", "file.bin"));
    }

    // ── writeLock + readLock round-trip ──

    @Test
    public void writeLockThenReadLockRoundTrip() throws Exception {
        Path tmp = Files.createTempFile("skilllock-rt", ".json");
        try {
            JsonObject lock = SkillLockService.emptyLock();
            SkillLockService.recordSkill(lock, "pdf", "anthropics", "github", "abc", "global");
            SkillLockService.writeLock(tmp, lock);

            JsonObject read = SkillLockService.readLock(tmp);
            assertTrue(read.getAsJsonObject("skills").has("pdf"));
            assertEquals("abc",
                read.getAsJsonObject("skills").getAsJsonObject("pdf").get("computedHash").getAsString());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void writeLockCreatesParentDirs() throws Exception {
        Path tmp = Files.createTempDirectory("skilllock-parent");
        Path nested = tmp.resolve("nested").resolve("dir").resolve("skills-lock.json");
        try {
            JsonObject lock = SkillLockService.emptyLock();
            SkillLockService.recordSkill(lock, "x", "s", "github", "h", "global");
            SkillLockService.writeLock(nested, lock);
            assertTrue(Files.isRegularFile(nested));
        } finally {
            deleteRecursively(tmp.toFile());
        }
    }

    private static void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
