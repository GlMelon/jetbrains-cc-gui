package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.settings.ModelRegistryResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Shared downstream-event assembly for model registry handlers (DRY).
 * Builds the {@code model_registry_updated} response payload and dispatches it.
 */
final class ModelRegistryEvents {
    private ModelRegistryEvents() {
    }

    /** Dispatch {@code model_registry_updated} with success/registry or errors shape. */
    static void dispatchUpdated(HandlerContext ctx, ModelRegistryResult result) {
        JsonObject response = new JsonObject();
        response.addProperty("success", result.success());
        if (result.reset()) {
            response.addProperty("reset", true);
        }
        if (result.success() && result.registry() != null) {
            response.add("registry", result.registry());
        }
        if (!result.success()) {
            JsonArray errors = new JsonArray();
            result.errors().forEach(errors::add);
            response.add("errors", errors);
        }
        ctx.dispatchEvent(DownstreamEvent.MODEL_REGISTRY_UPDATED.value(),
                ctx.escapeJs(response.toString()));
    }

    /** Dispatch the full {@code model_registry} snapshot. */
    static void dispatchRegistry(HandlerContext ctx, ModelRegistryResult result) {
        if (result.registry() == null) {
            return;
        }
        ctx.dispatchEvent(DownstreamEvent.MODEL_REGISTRY.value(),
                ctx.escapeJs(result.registry().toString()));
    }
}
