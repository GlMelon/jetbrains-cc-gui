package com.github.claudecodegui.handler.dependency;

import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Assert;
import org.junit.Test;

/**
 * Contract tests for dependency action handlers.
 * Verifies action/payloadType contract only — business logic lives in DependencyActionHandlers.
 */
public class DependencyActionHandlerTest {

    @Test
    public void testGetDependencyStatusActionContract() {
        GetDependencyStatusActionHandler h = new GetDependencyStatusActionHandler(null);
        Assert.assertEquals(UpstreamAction.GET_DEPENDENCY_STATUS, h.action());
        Assert.assertEquals("get_dependency_status", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
        Assert.assertTrue(h instanceof FrontendActionHandler<?>);
    }

    @Test
    public void testInstallDependencyActionContract() {
        InstallDependencyActionHandler h = new InstallDependencyActionHandler(null);
        Assert.assertEquals(UpstreamAction.INSTALL_DEPENDENCY, h.action());
        Assert.assertEquals("install_dependency", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testUninstallDependencyActionContract() {
        UninstallDependencyActionHandler h = new UninstallDependencyActionHandler(null);
        Assert.assertEquals(UpstreamAction.UNINSTALL_DEPENDENCY, h.action());
        Assert.assertEquals("uninstall_dependency", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testUpdateDependencyActionContract() {
        UpdateDependencyActionHandler h = new UpdateDependencyActionHandler(null);
        Assert.assertEquals(UpstreamAction.UPDATE_DEPENDENCY, h.action());
        Assert.assertEquals("update_dependency", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testCheckDependencyUpdatesActionContract() {
        CheckDependencyUpdatesActionHandler h = new CheckDependencyUpdatesActionHandler(null);
        Assert.assertEquals(UpstreamAction.CHECK_DEPENDENCY_UPDATES, h.action());
        Assert.assertEquals("check_dependency_updates", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testGetDependencyVersionsActionContract() {
        GetDependencyVersionsActionHandler h = new GetDependencyVersionsActionHandler(null);
        Assert.assertEquals(UpstreamAction.GET_DEPENDENCY_VERSIONS, h.action());
        Assert.assertEquals("get_dependency_versions", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testCheckNodeEnvironmentActionContract() {
        CheckNodeEnvironmentActionHandler h = new CheckNodeEnvironmentActionHandler(null);
        Assert.assertEquals(UpstreamAction.CHECK_NODE_ENVIRONMENT, h.action());
        Assert.assertEquals("check_node_environment", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }
}
