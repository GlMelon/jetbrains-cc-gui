package com.github.claudecodegui.handler.history;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class HistoryProjectPathResolverTest {

    @Test
    public void resolveProjectPathRejectsMissingRawPath() {
        assertNull(HistoryProjectPathResolver.resolveProjectPath(null, "C:\\node\\node.exe"));
        assertNull(HistoryProjectPathResolver.resolveProjectPath("   ", "C:\\node\\node.exe"));
    }

    @Test
    public void resolveProjectPathKeepsNativePathForNativeNode() {
        assertEquals("C:\\Users\\me\\repo",
                HistoryProjectPathResolver.resolveProjectPath("C:\\Users\\me\\repo", "C:\\node\\node.exe"));
    }

    @Test
    public void resolveProjectPathConvertsWindowsPathWhenNodeIsWsl() {
        assertEquals("/mnt/c/Users/me/repo",
                HistoryProjectPathResolver.resolveProjectPath("C:\\Users\\me\\repo", "/usr/bin/node"));
    }

    @Test
    public void resolveProjectPathConvertsWslUncPathWhenNodeIsWsl() {
        assertEquals("/home/me/repo",
                HistoryProjectPathResolver.resolveProjectPath("\\\\wsl.localhost\\Ubuntu\\home\\me\\repo", "/usr/bin/node"));
    }
}
