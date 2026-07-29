package com.github.claudecodegui.dependency;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link VersionComparator} 纯静态版本比较工具测试。
 */
public class VersionComparatorTest {

    // ── compareVersions ──

    @Test
    public void shouldCompareEqualVersions() {
        assertEquals(0, VersionComparator.compareVersions("1.0.0", "1.0.0"));
    }

    @Test
    public void shouldCompareMajorVersion() {
        assertTrue(VersionComparator.compareVersions("2.0.0", "1.0.0") > 0);
        assertTrue(VersionComparator.compareVersions("1.0.0", "2.0.0") < 0);
    }

    @Test
    public void shouldCompareMinorVersion() {
        assertTrue(VersionComparator.compareVersions("1.2.0", "1.1.0") > 0);
        assertTrue(VersionComparator.compareVersions("1.1.0", "1.2.0") < 0);
    }

    @Test
    public void shouldComparePatchVersion() {
        assertTrue(VersionComparator.compareVersions("1.0.2", "1.0.1") > 0);
        assertTrue(VersionComparator.compareVersions("1.0.1", "1.0.2") < 0);
    }

    @Test
    public void shouldStripLeadingV() {
        assertEquals(0, VersionComparator.compareVersions("v1.0.0", "1.0.0"));
        assertEquals(0, VersionComparator.compareVersions("V1.0.0", "1.0.0"));
        assertEquals(0, VersionComparator.compareVersions("v1.0.0", "V1.0.0"));
    }

    @Test
    public void shouldHandleNullVersions() {
        assertEquals(0, VersionComparator.compareVersions(null, "1.0.0"));
        assertEquals(0, VersionComparator.compareVersions("1.0.0", null));
        assertEquals(0, VersionComparator.compareVersions(null, null));
    }

    @Test
    public void shouldHandleDifferentLengths() {
        assertTrue(VersionComparator.compareVersions("1.0", "1.0.0") == 0);
        assertTrue(VersionComparator.compareVersions("1.0.1", "1.0") > 0);
    }

    // ── resolveVersionAction ──

    @Test
    public void shouldResolveInstallWhenNotInstalled() {
        assertEquals(VersionAction.INSTALL,
                VersionComparator.resolveVersionAction(false, null, "1.0.0"));
        assertEquals(VersionAction.INSTALL,
                VersionComparator.resolveVersionAction(false, "1.0.0", null));
    }

    @Test
    public void shouldResolveCurrentWhenInstalledEqualsRequested() {
        assertEquals(VersionAction.CURRENT,
                VersionComparator.resolveVersionAction(true, "1.0.0", "1.0.0"));
    }

    @Test
    public void shouldResolveUpdateWhenInstalledIsOlder() {
        assertEquals(VersionAction.UPDATE,
                VersionComparator.resolveVersionAction(true, "0.9.0", "1.0.0"));
    }

    @Test
    public void shouldResolveRollbackWhenInstalledIsNewer() {
        assertEquals(VersionAction.ROLLBACK,
                VersionComparator.resolveVersionAction(true, "2.0.0", "1.0.0"));
    }

    @Test
    public void shouldResolveCurrentWhenInstalledVersionIsBlank() {
        assertEquals(VersionAction.CURRENT,
                VersionComparator.resolveVersionAction(true, "", "1.0.0"));
        assertEquals(VersionAction.CURRENT,
                VersionComparator.resolveVersionAction(true, "  ", "1.0.0"));
    }

    // ── normalizeRequestedVersion ──

    @Test
    public void shouldNormalizeLeadingV() {
        assertEquals("1.0.0", VersionComparator.normalizeRequestedVersion("v1.0.0"));
        assertEquals("1.0.0", VersionComparator.normalizeRequestedVersion("V1.0.0"));
        assertEquals("1.0.0", VersionComparator.normalizeRequestedVersion(" v1.0.0 "));
    }

    @Test
    public void shouldAcceptValidSemver() {
        assertEquals("1.0.0", VersionComparator.normalizeRequestedVersion("1.0.0"));
        assertEquals("0.2.81", VersionComparator.normalizeRequestedVersion("0.2.81"));
        assertEquals("1.2.3-beta.1", VersionComparator.normalizeRequestedVersion("1.2.3-beta.1"));
        assertEquals("2.0.0-rc.1", VersionComparator.normalizeRequestedVersion("v2.0.0-rc.1"));
    }

    @Test
    public void shouldRejectInvalidVersionFormats() {
        assertNull(VersionComparator.normalizeRequestedVersion("not-a-version"));
        assertNull(VersionComparator.normalizeRequestedVersion("1.0"));
        assertNull(VersionComparator.normalizeRequestedVersion("latest"));
        assertNull(VersionComparator.normalizeRequestedVersion(">=1.0.0"));
        assertNull(VersionComparator.normalizeRequestedVersion("1.0.0 && rm -rf /"));
    }

    @Test
    public void shouldRejectNullAndEmpty() {
        assertNull(VersionComparator.normalizeRequestedVersion(null));
        assertNull(VersionComparator.normalizeRequestedVersion(""));
        assertNull(VersionComparator.normalizeRequestedVersion("   "));
    }
}
