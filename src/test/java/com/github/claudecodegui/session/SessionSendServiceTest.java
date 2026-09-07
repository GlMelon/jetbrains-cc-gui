package com.github.claudecodegui.session;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SessionSendServiceTest {

    @Test
    public void normalizeRequestedPermissionModeRejectsBlankAndUnknownValues() {
        assertNull(SessionSendService.normalizeRequestedPermissionMode(null));
        assertNull(SessionSendService.normalizeRequestedPermissionMode(" "));
        assertNull(SessionSendService.normalizeRequestedPermissionMode("dangerouslyAllowEverything"));
        assertEquals("acceptEdits", SessionSendService.normalizeRequestedPermissionMode("autoEdit"));
    }

    @Test
    public void resolveEffectivePermissionModeKeepsSessionModeWhenRequestedDiffers() {
        assertEquals("default", SessionSendService.resolveEffectivePermissionMode("claude", "bypassPermissions", "default"));
        assertEquals(
                "acceptEdits", SessionSendService.resolveEffectivePermissionMode("codex", "bypassPermissions", "acceptEdits")
        );
    }

    @Test
    public void resolveEffectivePermissionModeDowngradesCodexPlanAfterSessionResolution() {
        assertEquals(
                "default",
                SessionSendService.resolveEffectivePermissionMode("codex", null, "plan")
        );
    }

    @Test
    public void resolveEffectivePermissionModeFallsBackToRequestedModeWhenSessionModeMissing() {
        assertEquals("acceptEdits", SessionSendService.resolveEffectivePermissionMode("claude", "acceptEdits", null));
        assertEquals(
                "default",
                SessionSendService.resolveEffectivePermissionMode("claude", null, null)
        );
    }

    @Test
    public void resolveEffectivePermissionModeDowngradesPlanForCliProviders() {
        assertEquals(
                "default",
                SessionSendService.resolveEffectivePermissionMode("grok", "plan", null)
        );
        assertEquals(
                "default",
                SessionSendService.resolveEffectivePermissionMode("kimi", "plan", null)
        );
        assertEquals(
                "default",
                SessionSendService.resolveEffectivePermissionMode("opencode", null, "plan")
        );
        assertEquals(
                "default",
                SessionSendService.resolveEffectivePermissionMode("pi", "plan", null)
        );
    }

    @Test
    public void resolveEffectivePermissionModeDowngradesNativeAutoForCliProvidersWithoutNativeReviewer() {
        // Grok's ACP bridge already uses "auto" as its internal always-approve alias.
        assertEquals(
                "auto",
                SessionSendService.resolveEffectivePermissionMode("grok", "auto", null)
        );
        assertEquals(
                "default",
                SessionSendService.resolveEffectivePermissionMode("kimi", null, "auto")
        );
        assertEquals(
                "default",
                SessionSendService.resolveEffectivePermissionMode("omp", "auto", null)
        );
        assertEquals(
                "default",
                SessionSendService.resolveEffectivePermissionMode("dsh", "auto", null)
        );
        assertEquals(
                "auto",
                SessionSendService.resolveEffectivePermissionMode("codex", "auto", null)
        );
        assertEquals(
                "auto",
                SessionSendService.resolveEffectivePermissionMode("claude", null, "auto")
        );
    }

    @Test
    public void resolveEffectivePermissionModeKeepsPlanForOmpModelRole() {
        // omp's "plan" is a model role (`omp --model plan`), NOT Claude plan mode,
        // so it must survive resolution while other CLI providers are coerced.
        assertEquals(
                "plan",
                SessionSendService.resolveEffectivePermissionMode("omp", "plan", null)
        );
        assertEquals(
                "plan",
                SessionSendService.resolveEffectivePermissionMode("omp", null, "plan")
        );
        // smol/slow roles pass through untouched as well.
        assertEquals(
                "smol",
                SessionSendService.resolveEffectivePermissionMode("omp", "smol", null)
        );
        assertEquals(
                "slow",
                SessionSendService.resolveEffectivePermissionMode("omp", null, "slow")
        );
    }

    @Test
    public void permissionModeWhitelistAcceptsOmpModelRoles() {
        assertTrue(SessionState.isValidPermissionMode("smol"));
        assertTrue(SessionState.isValidPermissionMode("slow"));
        assertTrue(SessionState.isValidPermissionMode("plan"));
        assertEquals("smol", SessionSendService.normalizeRequestedPermissionMode("smol"));
        assertEquals("slow", SessionSendService.normalizeRequestedPermissionMode(" slow "));
    }

    @Test
    public void resolveEffectivePermissionModePreservesBypassForGrokFullAuto() {
        // Regression: UI "全自动" (bypassPermissions) must survive resolution so
        // the Grok ACP bridge can pass it into auto-approve — otherwise every
        // edit/tool still pops the permission dialog under default mode.
        assertEquals(
                "bypassPermissions",
                SessionSendService.resolveEffectivePermissionMode("grok", "bypassPermissions", null)
        );
        assertEquals(
                "bypassPermissions",
                SessionSendService.resolveEffectivePermissionMode("grok", null, "bypassPermissions")
        );
        assertEquals(
                "acceptEdits",
                SessionSendService.resolveEffectivePermissionMode("grok", "acceptEdits", null)
        );
    }

    @Test
    public void getCodexRuntimeAccessErrorRequiresAuthorizationOrManagedProvider() {
        assertEquals(
                "Codex local configuration access is not authorized. Please authorize local ~/.codex access or enable a managed Codex provider first.",
                SessionSendService.getCodexRuntimeAccessError("inactive")
        );
        assertNull(SessionSendService.getCodexRuntimeAccessError("managed"));
        assertNull(SessionSendService.getCodexRuntimeAccessError("cli_login"));
    }

}
