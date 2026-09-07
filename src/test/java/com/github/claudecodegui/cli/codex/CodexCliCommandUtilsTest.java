package com.github.claudecodegui.cli.codex;

import com.github.claudecodegui.cli.common.CliConstants;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CodexCliCommandUtilsTest {

    @Test
    public void defaultModeUsesSuggestApprovalWithConfiguredSandbox() {
        CodexCliCommandUtils.PermissionSelection selection =
                CodexCliCommandUtils.selectPermission("default", "workspace-write");

        assertEquals(CliConstants.CODEX_ARG_APPROVAL_ON_REQUEST, selection.approval());
        assertEquals("workspace-write", selection.sandbox());
    }

    @Test
    public void planModeUsesSuggestApprovalWithReadOnlySandbox() {
        CodexCliCommandUtils.PermissionSelection selection =
                CodexCliCommandUtils.selectPermission("plan", "danger-full-access");

        assertEquals(CliConstants.CODEX_ARG_APPROVAL_ON_REQUEST, selection.approval());
        assertEquals("read-only", selection.sandbox());
    }

    @Test
    public void acceptEditsModeUsesOnRequestWithConfiguredSandbox() {
        CodexCliCommandUtils.PermissionSelection selection =
                CodexCliCommandUtils.selectPermission("acceptEdits", "workspace-write");

        assertEquals("on-request", selection.approval());
        assertEquals("workspace-write", selection.sandbox());
    }

    @Test
    public void bypassPermissionsModeUsesNeverWithFullAccessSandbox() {
        CodexCliCommandUtils.PermissionSelection selection =
                CodexCliCommandUtils.selectPermission("bypassPermissions", "workspace-write");

        assertEquals("never", selection.approval());
        assertEquals("danger-full-access", selection.sandbox());
    }

    @Test
    public void unknownModeFallsBackToSuggestApproval() {
        CodexCliCommandUtils.PermissionSelection selection =
                CodexCliCommandUtils.selectPermission("unknown", "workspace-write");

        assertEquals(CliConstants.CODEX_ARG_APPROVAL_ON_REQUEST, selection.approval());
        assertEquals("workspace-write", selection.sandbox());
    }

    @Test
    public void globalOptionsReflectSelectedApprovalPolicy() {
        List<String> command = new ArrayList<>();
        CodexCliCommandUtils.addCodexGlobalOptions(
                command,
                new CodexCliCommandUtils.PermissionSelection("on-request", "workspace-write", "user")
        );

        assertEquals(List.of("--ask-for-approval", "on-request"), command);
    }

    @Test
    public void autoModePinsAutoReviewReviewerWithGuardedContract() {
        // Native auto review owns workspace-write + on-request + auto_review; the
        // configured sandbox must NOT override the guarded contract.
        CodexCliCommandUtils.PermissionSelection selection =
                CodexCliCommandUtils.selectPermission("auto", "danger-full-access");

        assertEquals(CliConstants.CODEX_ARG_APPROVAL_ON_REQUEST, selection.approval());
        assertEquals(CliConstants.SANDBOX_WORKSPACE_WRITE, selection.sandbox());
        assertEquals(CliConstants.CODEX_APPROVALS_REVIEWER_AUTO_REVIEW, selection.approvalsReviewer());
    }

    @Test
    public void nonAutoModesPinUserReviewerSoResumedThreadsCannotInheritAutoReview() {
        for (String mode : new String[]{"default", "plan", "acceptEdits", "autoEdit", "bypassPermissions", "unknown"}) {
            CodexCliCommandUtils.PermissionSelection selection =
                    CodexCliCommandUtils.selectPermission(mode, "workspace-write");
            assertEquals("mode=" + mode,
                    CliConstants.CODEX_APPROVALS_REVIEWER_USER, selection.approvalsReviewer());
        }
    }

    @Test
    public void approvalsReviewerOverrideUsesConfigFlag() {
        List<String> command = new ArrayList<>();
        CodexCliCommandUtils.addApprovalsReviewerOverride(
                command,
                new CodexCliCommandUtils.PermissionSelection("on-request", "workspace-write", "user")
        );

        assertEquals(List.of("-c", "approvals_reviewer=\"user\""), command);
    }

    @Test
    public void nativeAutoReviewRequiresCliFloorVersion() {
        // Aligned with ai-bridge isCodexNativeAutoReviewSupported: unparseable or
        // below-floor versions are unsupported (fail-fast), floor and above pass.
        assertTrue(CodexCliCommandUtils.isNativeAutoReviewSupported("codex-cli 0.146.0"));
        assertTrue(CodexCliCommandUtils.isNativeAutoReviewSupported("codex-cli 0.150.2"));
        assertTrue(CodexCliCommandUtils.isNativeAutoReviewSupported("codex-cli 1.0.0"));
        assertFalse(CodexCliCommandUtils.isNativeAutoReviewSupported("codex-cli 0.145.9"));
        assertFalse(CodexCliCommandUtils.isNativeAutoReviewSupported("codex-cli 0.99.0"));
        assertFalse(CodexCliCommandUtils.isNativeAutoReviewSupported("codex-cli"));
        assertFalse(CodexCliCommandUtils.isNativeAutoReviewSupported("unknown"));
        assertFalse(CodexCliCommandUtils.isNativeAutoReviewSupported(null));
    }
}
