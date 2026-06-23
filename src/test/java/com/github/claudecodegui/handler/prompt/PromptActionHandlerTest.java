package com.github.claudecodegui.handler.prompt;

import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Test;

import static org.junit.Assert.*;

public class PromptActionHandlerTest {

    @Test
    public void getPromptsActionShouldReturnCorrectUpstreamAction() {
        GetPromptsActionHandler handler = new GetPromptsActionHandler(null);
        assertEquals(UpstreamAction.GET_PROMPTS, handler.action());
    }

    @Test
    public void getPromptsActionShouldAcceptStringPayload() {
        GetPromptsActionHandler handler = new GetPromptsActionHandler(null);
        assertEquals(String.class, handler.payloadType());
    }

    @Test
    public void getPromptsActionShouldImplementFrontendActionHandler() {
        GetPromptsActionHandler handler = new GetPromptsActionHandler(null);
        assertTrue(handler instanceof FrontendActionHandler<?>);
    }

    @Test
    public void getProjectInfoActionShouldReturnCorrectUpstreamAction() {
        GetProjectInfoActionHandler handler = new GetProjectInfoActionHandler(null);
        assertEquals(UpstreamAction.GET_PROJECT_INFO, handler.action());
    }

    @Test
    public void getProjectInfoActionShouldAcceptStringPayload() {
        GetProjectInfoActionHandler handler = new GetProjectInfoActionHandler(null);
        assertEquals(String.class, handler.payloadType());
    }

    @Test
    public void addPromptActionShouldReturnCorrectUpstreamAction() {
        AddPromptActionHandler handler = new AddPromptActionHandler(null);
        assertEquals(UpstreamAction.ADD_PROMPT, handler.action());
    }

    @Test
    public void addPromptActionShouldAcceptStringPayload() {
        AddPromptActionHandler handler = new AddPromptActionHandler(null);
        assertEquals(String.class, handler.payloadType());
    }

    @Test
    public void updatePromptActionShouldReturnCorrectUpstreamAction() {
        UpdatePromptActionHandler handler = new UpdatePromptActionHandler(null);
        assertEquals(UpstreamAction.UPDATE_PROMPT, handler.action());
    }

    @Test
    public void updatePromptActionShouldAcceptStringPayload() {
        UpdatePromptActionHandler handler = new UpdatePromptActionHandler(null);
        assertEquals(String.class, handler.payloadType());
    }

    @Test
    public void deletePromptActionShouldReturnCorrectUpstreamAction() {
        DeletePromptActionHandler handler = new DeletePromptActionHandler(null);
        assertEquals(UpstreamAction.DELETE_PROMPT, handler.action());
    }

    @Test
    public void deletePromptActionShouldAcceptStringPayload() {
        DeletePromptActionHandler handler = new DeletePromptActionHandler(null);
        assertEquals(String.class, handler.payloadType());
    }

    @Test
    public void exportPromptsActionShouldReturnCorrectUpstreamAction() {
        ExportPromptsActionHandler handler = new ExportPromptsActionHandler(null);
        assertEquals(UpstreamAction.EXPORT_PROMPTS, handler.action());
    }

    @Test
    public void exportPromptsActionShouldAcceptStringPayload() {
        ExportPromptsActionHandler handler = new ExportPromptsActionHandler(null);
        assertEquals(String.class, handler.payloadType());
    }

    @Test
    public void importPromptsFileActionShouldReturnCorrectUpstreamAction() {
        ImportPromptsFileActionHandler handler = new ImportPromptsFileActionHandler(null);
        assertEquals(UpstreamAction.IMPORT_PROMPTS_FILE, handler.action());
    }

    @Test
    public void importPromptsFileActionShouldAcceptStringPayload() {
        ImportPromptsFileActionHandler handler = new ImportPromptsFileActionHandler(null);
        assertEquals(String.class, handler.payloadType());
    }

    @Test
    public void saveImportedPromptsActionShouldReturnCorrectUpstreamAction() {
        SaveImportedPromptsActionHandler handler = new SaveImportedPromptsActionHandler(null);
        assertEquals(UpstreamAction.SAVE_IMPORTED_PROMPTS, handler.action());
    }

    @Test
    public void saveImportedPromptsActionShouldAcceptStringPayload() {
        SaveImportedPromptsActionHandler handler = new SaveImportedPromptsActionHandler(null);
        assertEquals(String.class, handler.payloadType());
    }

    @Test
    public void allHandlersShouldHaveUniqueActions() {
        GetPromptsActionHandler h1 = new GetPromptsActionHandler(null);
        GetProjectInfoActionHandler h2 = new GetProjectInfoActionHandler(null);
        AddPromptActionHandler h3 = new AddPromptActionHandler(null);
        UpdatePromptActionHandler h4 = new UpdatePromptActionHandler(null);
        DeletePromptActionHandler h5 = new DeletePromptActionHandler(null);
        ExportPromptsActionHandler h6 = new ExportPromptsActionHandler(null);
        ImportPromptsFileActionHandler h7 = new ImportPromptsFileActionHandler(null);
        SaveImportedPromptsActionHandler h8 = new SaveImportedPromptsActionHandler(null);

        assertEquals("get_prompts", h1.action().value());
        assertEquals("get_project_info", h2.action().value());
        assertEquals("add_prompt", h3.action().value());
        assertEquals("update_prompt", h4.action().value());
        assertEquals("delete_prompt", h5.action().value());
        assertEquals("export_prompts", h6.action().value());
        assertEquals("import_prompts_file", h7.action().value());
        assertEquals("save_imported_prompts", h8.action().value());
    }
}
