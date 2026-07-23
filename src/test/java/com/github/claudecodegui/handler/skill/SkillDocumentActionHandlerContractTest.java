package com.github.claudecodegui.handler.skill;

import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Contract tests for the typed SKILL.md document handlers and payloads. */
public class SkillDocumentActionHandlerContractTest {

    @Test
    public void getHandlerDeclaresTypedContractAndDelegatesPayload() {
        CapturingSkillActionHandlers handlers = new CapturingSkillActionHandlers();
        GetSkillDocumentActionHandler handler = new GetSkillDocumentActionHandler(handlers);
        GetSkillDocumentRequest request = new GetSkillDocumentRequest(
                "request-1", "global", "sample", "/skills/sample",
                "/skills/sample/SKILL.md", true);

        assertEquals(UpstreamAction.GET_SKILL_DOCUMENT, handler.action());
        assertEquals("get_skill_document", handler.action().value());
        assertEquals(GetSkillDocumentRequest.class, handler.payloadType());
        assertTrue(handler instanceof FrontendActionHandler<?>);

        handler.handle(request, null);

        assertSame(request, handlers.getRequest);
        assertEquals("request-1", request.requestId());
        assertEquals("global", request.toIdentity().scope());
        assertEquals("/skills/sample/SKILL.md", request.toIdentity().skillPath());
        assertTrue(request.toIdentity().enabled());
    }

    @Test
    public void saveHandlerDeclaresTypedContractAndDelegatesPayload() {
        CapturingSkillActionHandlers handlers = new CapturingSkillActionHandlers();
        SaveSkillDocumentActionHandler handler = new SaveSkillDocumentActionHandler(handlers);
        JsonObject changes = new JsonObject();
        changes.addProperty("description", "Updated");
        SaveSkillDocumentRequest request = new SaveSkillDocumentRequest(
                "request-2", "repo", "sample", "/repo/.agents/skills/sample",
                "/repo/.agents/skills/sample/SKILL.md", false, "revision-1",
                changes, "# Body\n");

        assertEquals(UpstreamAction.SAVE_SKILL_DOCUMENT, handler.action());
        assertEquals("save_skill_document", handler.action().value());
        assertEquals(SaveSkillDocumentRequest.class, handler.payloadType());
        assertTrue(handler instanceof FrontendActionHandler<?>);

        handler.handle(request, null);

        assertSame(request, handlers.saveRequest);
        assertEquals("request-2", request.requestId());
        assertEquals("repo", request.toIdentity().scope());
        assertEquals("revision-1", request.revision());
        assertSame(changes, request.changes());
        assertEquals("# Body\n", request.body());
    }

    private static final class CapturingSkillActionHandlers extends SkillActionHandlers {
        private GetSkillDocumentRequest getRequest;
        private SaveSkillDocumentRequest saveRequest;

        private CapturingSkillActionHandlers() {
            super(null);
        }

        @Override
        public void handleGetSkillDocument(GetSkillDocumentRequest request) {
            getRequest = request;
        }

        @Override
        public void handleSaveSkillDocument(SaveSkillDocumentRequest request) {
            saveRequest = request;
        }
    }
}
