package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionDispatcher;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.settings.AppearanceConfigService;
import com.github.claudecodegui.settings.ModelRegistryService;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SettingsHandlerTypedWiringTest {

    /**
     * Every migrated action must remain a resolvable UpstreamAction value so the typed
     * FrontendActionHandler that claims it can route through FrontendActionDispatcher.
     *
     * <p>The legacy SettingsHandler string-dispatch (SUPPORTED_TYPES array + 49-case switch) has
     * been fully retired (B3): these actions are now served exclusively by dedicated typed
     * handlers under {@code handler/settings} / {@code handler/provider}, and the SettingsHandler
     * class itself has been deleted.
     */
    @Test
    public void migratedActionsRemainResolvable() {
        for (String migrated : new String[]{
                // Pre-B3 typed slices (model registry / appearance / provider utility)
                "get_model_registry", "set_model_registry",
                "get_model_registry_schema", "set_appearance_config",
                "get_codex_subscription_quota",
                "get_node_path", "set_node_path",
                // B3 slice: project-config (42)
                "get_usage_statistics", "get_working_directory", "set_working_directory",
                "get_editor_font_config", "get_ui_font_config", "set_ui_font_config",
                "browse_ui_font_file", "get_code_font_config", "set_code_font_config",
                "browse_code_font_file", "get_streaming_enabled", "set_streaming_enabled",
                "get_show_thinking_enabled", "set_show_thinking_enabled",
                "get_session_runtime_state",
                "get_codex_sandbox_mode", "set_codex_sandbox_mode",
                "get_send_shortcut", "set_send_shortcut",
                "get_auto_open_file_enabled", "set_auto_open_file_enabled",
                "get_permission_dialog_timeout", "set_permission_dialog_timeout",
                "get_commit_generation_enabled", "set_commit_generation_enabled",
                "get_status_bar_widget_enabled", "set_status_bar_widget_enabled",
                "get_task_completion_notification_enabled",
                "set_task_completion_notification_enabled",
                "get_ai_title_generation_enabled", "set_ai_title_generation_enabled",
                "get_ide_theme", "get_commit_prompt", "set_commit_prompt",
                "get_commit_ai_config", "set_commit_ai_config",
                "get_prompt_enhancer_config", "set_prompt_enhancer_config",
                "get_project_commit_prompt", "set_project_commit_prompt",
                // B3 slice: user-language (3)
                "set_user_language", "get_user_language", "clear_user_language"
        }) {
            assertTrue("migrated action '" + migrated
                    + "' must resolve to an UpstreamAction (B3 typed wiring)",
                    UpstreamAction.fromValue(migrated).isPresent());
        }
    }

    /**
     * The wired dispatcher must: (a) construct without a duplicate-action exception — proving the
     * typed handlers do not collide; (b) miss unknown actions.
     *
     * <p>The legacy SettingsHandler string-dispatch (SUPPORTED_TYPES + 49-case switch) and the
     * BaseMessageHandler / LegacyMessageHandlerAdapter framework have both been retired. Actions
     * are now served exclusively by dedicated typed handlers; typed-handler dispatch behaviour is
     * covered by ModelRegistryActionHandlerTest / AppearanceConfigActionHandlerTest.
     */
    @Test
    public void wiredDispatcherConstructsWithoutDuplicatesAndMissesUnknown() {
        HandlerContext ctx = new HandlerContext(null, null, new HandlerContext.JsCallback() {
            @Override public void callJavaScript(String functionName, String... args) { }
            @Override public String escapeJs(String str) { return str; }
        });
        ModelRegistryService modelRegistryService = new ModelRegistryService(null);
        AppearanceConfigService appearanceConfigService = new AppearanceConfigService(null);

        List<FrontendActionHandler<?>> typed = new ArrayList<>();
        typed.add(new GetModelRegistryActionHandler(modelRegistryService));
        typed.add(new SetModelRegistryActionHandler(modelRegistryService));
        typed.add(new GetModelRegistrySchemaActionHandler(modelRegistryService));
        typed.add(new SetAppearanceConfigActionHandler(appearanceConfigService));

        // 构造不抛 IllegalArgumentException = 4 个 typed action 互不重复
        FrontendActionDispatcher dispatcher = new FrontendActionDispatcher(typed, ctx);

        // 未知 action miss
        assertFalse(dispatcher.dispatch("not_a_real_action", ""));
    }
}
