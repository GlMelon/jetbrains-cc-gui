package com.github.claudecodegui.dependency;

import com.github.claudecodegui.dependency.VersionAction;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class DependencyManagerVersioningTest {
    @Test
    public void shouldUseRequestedVersionForMainPackage() {
        List<String> packages = DependencyManager.buildPackageSpecs(
                SdkDefinition.CLAUDE_SDK,
                "0.2.81"
        );

        assertEquals("@anthropic-ai/claude-agent-sdk@0.2.81", packages.get(0));
        assertEquals("@anthropic-ai/sdk", packages.get(1));
        assertEquals("@anthropic-ai/bedrock-sdk", packages.get(2));
    }

    @Test
    public void shouldFallbackToSdkDefaultVersionWhenRequestedVersionIsBlank() {
        List<String> packages = DependencyManager.buildPackageSpecs(
                SdkDefinition.CODEX_SDK,
                " "
        );

        assertEquals("@openai/codex-sdk@latest", packages.get(0));
    }

    @Test
    public void shouldNormalizeLeadingVInRequestedVersion() {
        assertEquals("0.2.81", DependencyManager.normalizeRequestedVersion(" v0.2.81 "));
    }

    @Test
    public void shouldAcceptValidSemverVersions() {
        assertEquals("1.0.0", DependencyManager.normalizeRequestedVersion("1.0.0"));
        assertEquals("0.2.81", DependencyManager.normalizeRequestedVersion("V0.2.81"));
        assertEquals("1.2.3-beta.1", DependencyManager.normalizeRequestedVersion("1.2.3-beta.1"));
        assertEquals("2.0.0-rc.1", DependencyManager.normalizeRequestedVersion("v2.0.0-rc.1"));
    }

    @Test
    public void shouldRejectInvalidVersionFormats() {
        assertNull(DependencyManager.normalizeRequestedVersion("not-a-version"));
        assertNull(DependencyManager.normalizeRequestedVersion("1.0"));
        assertNull(DependencyManager.normalizeRequestedVersion("latest"));
        assertNull(DependencyManager.normalizeRequestedVersion(">=1.0.0"));
        assertNull(DependencyManager.normalizeRequestedVersion("1.0.0 && rm -rf /"));
    }

    @Test
    public void shouldRejectNullAndEmpty() {
        assertNull(DependencyManager.normalizeRequestedVersion(null));
        assertNull(DependencyManager.normalizeRequestedVersion(""));
        assertNull(DependencyManager.normalizeRequestedVersion("   "));
    }

    // ── A6:resolveVersionAction 后端 SSOT(前端 getVersionAction 决策已下沉为 versionActions map 下发) ──

    @Test
    public void shouldResolveInstallWhenNotInstalled() {
        assertEquals(VersionAction.INSTALL,
                DependencyManager.resolveVersionAction(false, null, "1.0.0"));
        assertEquals(VersionAction.INSTALL,
                DependencyManager.resolveVersionAction(false, "1.0.0", null));
    }

    @Test
    public void shouldResolveCurrentWhenInstalledEqualsRequested() {
        assertEquals(VersionAction.CURRENT,
                DependencyManager.resolveVersionAction(true, "1.0.0", "1.0.0"));
    }

    @Test
    public void shouldResolveUpdateWhenInstalledIsOlder() {
        assertEquals(VersionAction.UPDATE,
                DependencyManager.resolveVersionAction(true, "1.0.0", "2.0.0"));
        assertEquals(VersionAction.UPDATE,
                DependencyManager.resolveVersionAction(true, "1.2.3", "1.2.4"));
    }

    @Test
    public void shouldResolveRollbackWhenInstalledIsNewer() {
        assertEquals(VersionAction.ROLLBACK,
                DependencyManager.resolveVersionAction(true, "2.0.0", "1.0.0"));
        assertEquals(VersionAction.ROLLBACK,
                DependencyManager.resolveVersionAction(true, "1.2.4", "1.2.3"));
    }

    @Test
    public void shouldResolveCurrentWhenVersionsBlankOrMissing() {
        // 已安装但版本信息缺失 → 无法判定方向,保守视为 CURRENT
        assertEquals(VersionAction.CURRENT,
                DependencyManager.resolveVersionAction(true, null, "1.0.0"));
        assertEquals(VersionAction.CURRENT,
                DependencyManager.resolveVersionAction(true, "1.0.0", null));
        assertEquals(VersionAction.CURRENT,
                DependencyManager.resolveVersionAction(true, " ", "1.0.0"));
        assertEquals(VersionAction.CURRENT,
                DependencyManager.resolveVersionAction(true, "1.0.0", "  "));
    }

    @Test
    public void shouldStripLeadingVWhenComparingVersions() {
        // compareVersions 内部剥离 v/V 前缀后比较
        assertEquals(VersionAction.CURRENT,
                DependencyManager.resolveVersionAction(true, "v1.0.0", "1.0.0"));
        assertEquals(VersionAction.UPDATE,
                DependencyManager.resolveVersionAction(true, "v1.0.0", "2.0.0"));
        assertEquals(VersionAction.ROLLBACK,
                DependencyManager.resolveVersionAction(true, "V2.0.0", "1.0.0"));
    }
}
