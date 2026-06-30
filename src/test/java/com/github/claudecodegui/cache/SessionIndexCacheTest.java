package com.github.claudecodegui.cache;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SessionIndexCacheTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void codexCacheInvalidatesWhenNestedSessionFileIsAdded() throws Exception {
        SessionIndexCache cache = SessionIndexCache.getInstance();
        String key = "codex-cache-test-" + System.nanoTime();
        Path sessionsDir = tmp.newFolder("codex-sessions").toPath();
        Path dayDir = Files.createDirectories(sessionsDir.resolve("2026/06/30"));

        Files.writeString(dayDir.resolve("rollout-old.jsonl"), "{}\n");
        cache.updateCodexCache(key, sessionsDir, List.of("old"));

        assertEquals(List.of("old"), cache.getCodexSessions(key, sessionsDir));

        Files.writeString(dayDir.resolve("rollout-new.jsonl"), "{}\n");

        assertNull(cache.getCodexSessions(key, sessionsDir));
    }
}
