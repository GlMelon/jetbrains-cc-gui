package com.github.claudecodegui.handler.skill;

import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Assert;
import org.junit.Test;

/**
 * Contract tests for skill action handlers.
 * Verifies action/payloadType contract only — business logic lives in SkillActionHandlers.
 */
public class SkillActionHandlerTest {

    @Test
    public void testGetAllSkillsActionContract() {
        GetAllSkillsActionHandler h = new GetAllSkillsActionHandler(null);
        Assert.assertEquals(UpstreamAction.GET_ALL_SKILLS, h.action());
        Assert.assertEquals("get_all_skills", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testImportSkillActionContract() {
        ImportSkillActionHandler h = new ImportSkillActionHandler(null);
        Assert.assertEquals(UpstreamAction.IMPORT_SKILL, h.action());
        Assert.assertEquals("import_skill", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testDeleteSkillActionContract() {
        DeleteSkillActionHandler h = new DeleteSkillActionHandler(null);
        Assert.assertEquals(UpstreamAction.DELETE_SKILL, h.action());
        Assert.assertEquals("delete_skill", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testOpenSkillActionContract() {
        OpenSkillActionHandler h = new OpenSkillActionHandler(null);
        Assert.assertEquals(UpstreamAction.OPEN_SKILL, h.action());
        Assert.assertEquals("open_skill", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testToggleSkillActionContract() {
        ToggleSkillActionHandler h = new ToggleSkillActionHandler(null);
        Assert.assertEquals(UpstreamAction.TOGGLE_SKILL, h.action());
        Assert.assertEquals("toggle_skill", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }
}
