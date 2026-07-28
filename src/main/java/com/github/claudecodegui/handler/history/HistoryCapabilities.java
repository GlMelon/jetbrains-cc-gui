package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.protocol.payload.HistoryCapabilitiesPayloadField;
import com.google.gson.JsonObject;

/**
 * Backend-authoritative history capabilities sent to the Webview.
 */
record HistoryCapabilities(boolean canDelete, boolean canArchive) {
    static HistoryCapabilities from(HistoryProviderAdapter adapter) {
        return new HistoryCapabilities(
                adapter.supports(HistoryCapability.DELETE),
                adapter.supports(HistoryCapability.ARCHIVE)
        );
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty(HistoryCapabilitiesPayloadField.CAN_DELETE.wireKey(), canDelete);
        json.addProperty(HistoryCapabilitiesPayloadField.CAN_ARCHIVE.wireKey(), canArchive);
        return json;
    }
}
