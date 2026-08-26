package com.github.claudecodegui.skill;

import com.github.claudecodegui.protocol.ProtocolValue;

/**
 * Local slash command actions handled by the plugin itself, never forwarded to the CLI.
 *
 * <p>SSOT for the {@code localAction} field of the slash command payload: the backend
 * annotates each command via {@link SlashCommandRegistry}, and the webview maps these
 * values to UI actions (new session, open history, ...). The webview must not invent
 * its own action values; both sides agree on {@link #value()} as the wire format.
 */
public enum LocalSlashAction implements ProtocolValue {

    /** Clear the conversation and start a new session (/clear, /new, /reset). */
    NEW_SESSION("new_session"),

    /** Open the history view to resume a previous conversation (/resume, /continue). */
    OPEN_HISTORY("open_history"),

    /** Switch to plan mode (/plan, Claude only). */
    PLAN_MODE("plan_mode"),

    /** Open the context usage dialog (/context, Claude only). */
    CONTEXT_USAGE("context_usage"),

    /** Open the model selector (/model). */
    MODEL_PICKER("model_picker"),

    /** Show the available commands overview (/help). */
    HELP("help"),
    ;

    private final String value;

    LocalSlashAction(String value) {
        this.value = value;
    }

    /** 协议线上实际传输的字符串值(slash command payload 的 localAction 字段) */
    @Override
    public String value() {
        return value;
    }
}
