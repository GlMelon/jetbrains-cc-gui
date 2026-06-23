package com.github.claudecodegui.handler.diff;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;

import java.util.List;

/**
 * Container for diff action handlers (B2 迁移).
 *
 * <p>Assembles the diff responsibility-chain dispatcher (refresh / simple-display /
 * editable / interactive) and exposes a typed {@link #dispatch(String, String)} entry
 * point consumed by the per-action {@code FrontendActionHandler} adapters.
 */
public class DiffActionHandlers {

    private final DiffRequestDispatcher dispatcher;

    public DiffActionHandlers(HandlerContext context) {
        Gson gson = GsonHolder.GSON;
        DiffBrowserBridge browserBridge = new DiffBrowserBridge(context, gson);
        DiffFileOperations fileOperations = new DiffFileOperations(context);
        this.dispatcher = new DiffRequestDispatcher(List.of(
                new RefreshFileHandler(context, gson, fileOperations),
                new SimpleDiffDisplayHandler(context, gson, fileOperations),
                new EditableDiffHandler(context, gson, browserBridge, fileOperations),
                new InteractiveDiffMessageHandler(context, gson, browserBridge, fileOperations)
        ));
    }

    /**
     * Route a diff action by its protocol type string to the first supporting
     * sub-handler. Returns silently if no handler claims the type (the typed
     * adapter layer guarantees a known type, so this is defensive only).
     */
    public void dispatch(String type, String content) {
        dispatcher.dispatch(type, content);
    }
}
