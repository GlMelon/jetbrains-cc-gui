package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionDispatcher;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.handler.core.LegacyMessageHandlerAdapter;
import com.github.claudecodegui.handler.core.MessageHandler;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.settings.AppearanceConfigService;
import com.github.claudecodegui.settings.ModelRegistryService;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SettingsHandlerTypedWiringTest {

    /**
     * Migrated actions must remain resolvable UpstreamAction values so the typed handlers can
     * claim them (and so they are absent from the slimmed SettingsHandler's SUPPORTED_TYPES).
     */
    @Test
    public void migratedActionsRemainResolvable() {
        for (String migrated : new String[]{
                "get_model_registry", "set_model_registry", "reset_model_registry",
                "get_model_registry_schema", "set_appearance_config"
        }) {
            assertTrue(UpstreamAction.fromValue(migrated).isPresent());
        }
    }

    /**
     * The wired dispatcher must: (a) construct without a duplicate-action exception — proving the
     * 5 typed handlers do not collide with each other; (b) route a legacy MessageHandler's actions
     * through LegacyMessageHandlerAdapter with raw content forwarded; (c) miss unknown actions.
     *
     * We deliberately use a dummy legacy handler instead of a real SettingsHandler: constructing
     * SettingsHandler requires a live IDE environment (ApplicationManager + sub-handlers), and
     * dispatching its actions touches the settings service. Typed-handler dispatch behaviour is
     * covered by ModelRegistryActionHandlerTest / AppearanceConfigActionHandlerTest.
     */
    @Test
    public void wiredDispatcherConstructsAndRoutesLegacyWithoutDuplicates() {
        HandlerContext ctx = new HandlerContext(null, null, null, null, new HandlerContext.JsCallback() {
            @Override public void callJavaScript(String functionName, String... args) { }
            @Override public String escapeJs(String str) { return str; }
        });
        ModelRegistryService modelRegistryService = new ModelRegistryService(null);
        AppearanceConfigService appearanceConfigService = new AppearanceConfigService(null);

        AtomicReference<String> legacySeen = new AtomicReference<>();
        MessageHandler dummyLegacy = new MessageHandler() {
            @Override public boolean handle(String type, String content) {
                legacySeen.set(type + "|" + content);
                return true;
            }
            @Override public String[] getSupportedTypes() {
                return new String[]{"set_model", "get_runtime_policy"};
            }
        };

        List<FrontendActionHandler<?>> typed = new ArrayList<>();
        typed.add(new GetModelRegistryActionHandler(modelRegistryService));
        typed.add(new SetModelRegistryActionHandler(modelRegistryService));
        typed.add(new ResetModelRegistryActionHandler(modelRegistryService));
        typed.add(new GetModelRegistrySchemaActionHandler(modelRegistryService));
        typed.add(new SetAppearanceConfigActionHandler(appearanceConfigService));
        typed.addAll(LegacyMessageHandlerAdapter.from(dummyLegacy));

        // 构造不抛 IllegalArgumentException = 5 个 typed action 互不重复,且 dummy 的 set_model /
        // get_runtime_policy 与 typed 不重叠(它们仍在 UpstreamAction 枚举中,故 adapter 会包装)
        FrontendActionDispatcher dispatcher = new FrontendActionDispatcher(typed, ctx);

        // legacy action 经 adapter 命中 dummy handler,且透传原始 content
        assertTrue(dispatcher.dispatch("set_model", "claude-role-sonnet"));
        assertEquals("set_model|claude-role-sonnet", legacySeen.get());
        assertTrue(dispatcher.dispatch("get_runtime_policy", ""));
        // 未知 action miss
        assertFalse(dispatcher.dispatch("not_a_real_action", ""));
    }
}
