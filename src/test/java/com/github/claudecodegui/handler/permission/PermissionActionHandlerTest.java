package com.github.claudecodegui.handler.permission;

import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Contract tests for Permission typed handlers.
 */
public class PermissionActionHandlerTest {

    private final PermissionActionHandlers handlers = new PermissionActionHandlers(null);

    @Test
    public void permissionDecisionAction() {
        PermissionDecisionActionHandler handler = new PermissionDecisionActionHandler(handlers);
        assertEquals(UpstreamAction.PERMISSION_DECISION, handler.action());
    }

    @Test
    public void permissionDecisionPayloadType() {
        PermissionDecisionActionHandler handler = new PermissionDecisionActionHandler(handlers);
        assertEquals(String.class, handler.payloadType());
    }

    @Test
    public void permissionDecisionActionValueMatchesLegacy() {
        PermissionDecisionActionHandler handler = new PermissionDecisionActionHandler(handlers);
        assertEquals("permission_decision", handler.action().value());
    }

    @Test
    public void askUserQuestionAction() {
        AskUserQuestionResponseActionHandler handler = new AskUserQuestionResponseActionHandler(handlers);
        assertEquals(UpstreamAction.ASK_USER_QUESTION_RESPONSE, handler.action());
    }

    @Test
    public void askUserQuestionPayloadType() {
        AskUserQuestionResponseActionHandler handler = new AskUserQuestionResponseActionHandler(handlers);
        assertEquals(String.class, handler.payloadType());
    }

    @Test
    public void askUserQuestionActionValueMatchesLegacy() {
        AskUserQuestionResponseActionHandler handler = new AskUserQuestionResponseActionHandler(handlers);
        assertEquals("ask_user_question_response", handler.action().value());
    }

    @Test
    public void planApprovalAction() {
        PlanApprovalResponseActionHandler handler = new PlanApprovalResponseActionHandler(handlers);
        assertEquals(UpstreamAction.PLAN_APPROVAL_RESPONSE, handler.action());
    }

    @Test
    public void planApprovalPayloadType() {
        PlanApprovalResponseActionHandler handler = new PlanApprovalResponseActionHandler(handlers);
        assertEquals(String.class, handler.payloadType());
    }

    @Test
    public void planApprovalActionValueMatchesLegacy() {
        PlanApprovalResponseActionHandler handler = new PlanApprovalResponseActionHandler(handlers);
        assertEquals("plan_approval_response", handler.action().value());
    }

    @Test
    public void distinctActions() {
        PermissionDecisionActionHandler h1 = new PermissionDecisionActionHandler(handlers);
        AskUserQuestionResponseActionHandler h2 = new AskUserQuestionResponseActionHandler(handlers);
        PlanApprovalResponseActionHandler h3 = new PlanApprovalResponseActionHandler(handlers);

        assertNotEquals(h1.action(), h2.action());
        assertNotEquals(h2.action(), h3.action());
        assertNotEquals(h1.action(), h3.action());
    }

    @Test
    public void allImplementFrontendActionHandler() {
        PermissionDecisionActionHandler h1 = new PermissionDecisionActionHandler(handlers);
        AskUserQuestionResponseActionHandler h2 = new AskUserQuestionResponseActionHandler(handlers);
        PlanApprovalResponseActionHandler h3 = new PlanApprovalResponseActionHandler(handlers);

        assertTrue(h1 instanceof FrontendActionHandler);
        assertTrue(h2 instanceof FrontendActionHandler);
        assertTrue(h3 instanceof FrontendActionHandler);
    }
}
