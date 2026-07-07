package com.github.claudecodegui.cli.common.normalizer;

import com.github.claudecodegui.common.CommonConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Builds existing assistant raw-message snapshots from already-normalized blocks.
 */
public final class NormalizedMessageBlockBuilder {

    public JsonObject assistantWithBlock(JsonObject block) {
        JsonObject raw = new JsonObject();
        raw.addProperty(CommonConstants.JSON_KEY_TYPE, CommonConstants.MSG_TYPE_ASSISTANT);

        JsonObject message = new JsonObject();
        message.addProperty(CommonConstants.JSON_KEY_ROLE, CommonConstants.MSG_TYPE_ASSISTANT);

        JsonArray content = new JsonArray();
        content.add(block != null ? block : new JsonObject());
        message.add(CommonConstants.JSON_KEY_CONTENT, content);

        raw.add(CommonConstants.JSON_KEY_MESSAGE, message);
        return raw;
    }
}
