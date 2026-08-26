package com.github.claudecodegui.session;

import com.github.claudecodegui.cli.common.CliOutputLimits;
import com.github.claudecodegui.common.CommonConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Message merger.
 * Merges streaming assistant messages, ensuring previously displayed tool steps are not overwritten.
 */
public class MessageMerger {

    /**
     * Merge streaming assistant messages.
     */
    public JsonObject mergeAssistantMessage(JsonObject existingRaw, JsonObject newRaw) {
        if (newRaw == null) {
            return existingRaw != null ? boundedCopy(existingRaw) : null;
        }

        JsonObject boundedNewRaw = boundedCopy(newRaw);
        if (existingRaw == null) {
            return boundedNewRaw;
        }

        JsonObject merged = boundedCopy(existingRaw);

        // Merge top-level fields (except "message")
        for (Map.Entry<String, JsonElement> entry : boundedNewRaw.entrySet()) {
            if ("message".equals(entry.getKey())) {
                continue;
            }
            merged.add(entry.getKey(), entry.getValue());
        }

        JsonObject incomingMessage = boundedNewRaw.has("message") && boundedNewRaw.get("message").isJsonObject()
            ? boundedNewRaw.getAsJsonObject("message")
            : null;

        if (incomingMessage == null) {
            return merged;
        }

        JsonObject mergedMessage = merged.has("message") && merged.get("message").isJsonObject()
            ? merged.getAsJsonObject("message")
            : new JsonObject();

        // Copy new metadata (keep latest stop_reason, usage, etc.)
        for (Map.Entry<String, JsonElement> entry : incomingMessage.entrySet()) {
            if ("content".equals(entry.getKey())) {
                continue;
            }
            mergedMessage.add(entry.getKey(), entry.getValue());
        }

        mergeAssistantContentArray(mergedMessage, incomingMessage);
        merged.add("message", mergedMessage);
        // Merging can combine an already bounded snapshot with new keyed blocks.
        // Apply the aggregate cap once more so a long-running turn cannot grow
        // the retained raw tree without limit.
        return boundedCopy(merged);
    }

    /**
     * Merge the content array of assistant messages.
     */
    private void mergeAssistantContentArray(JsonObject targetMessage, JsonObject incomingMessage) {
        JsonArray baseContent = targetMessage.has("content") && targetMessage.get("content").isJsonArray()
            ? targetMessage.getAsJsonArray("content")
            : new JsonArray();

        Map<String, Integer> indexByKey = buildContentIndex(baseContent);
        Set<Integer> consumedUnkeyedIndexes = new HashSet<>();

        JsonArray incomingContent = incomingMessage.has("content") && incomingMessage.get("content").isJsonArray()
            ? incomingMessage.getAsJsonArray("content")
            : null;

        if (incomingContent == null) {
            targetMessage.add("content", baseContent);
            return;
        }

        for (int i = 0; i < incomingContent.size(); i++) {
            JsonElement element = incomingContent.get(i);
            JsonElement elementCopy = element.deepCopy();

            if (element.isJsonObject()) {
                JsonObject block = element.getAsJsonObject();
                String key = getContentBlockKey(block);
                if (key != null && indexByKey.containsKey(key)) {
                    int idx = indexByKey.get(key);
                    baseContent.set(idx, elementCopy);
                    continue;
                } else if (key != null) {
                    baseContent.add(elementCopy);
                    indexByKey.put(key, baseContent.size() - 1);
                    continue;
                } else {
                    int idx = findMatchingUnkeyedBlockIndex(baseContent, block, consumedUnkeyedIndexes);
                    if (idx >= 0) {
                        baseContent.set(idx, mergeUnkeyedBlock(baseContent.get(idx).getAsJsonObject(), block));
                        consumedUnkeyedIndexes.add(idx);
                        continue;
                    }

                    // Fallback: merge with last same-type block instead of adding duplicate
                    int lastSameTypeIdx = findLastSameTypeBlockIndex(baseContent, block, consumedUnkeyedIndexes);
                    if (lastSameTypeIdx >= 0) {
                        baseContent.set(lastSameTypeIdx,
                                mergeUnkeyedBlock(baseContent.get(lastSameTypeIdx).getAsJsonObject(), block));
                        continue;
                    }
                }
            }

            baseContent.add(elementCopy);
        }

        targetMessage.add("content", baseContent);
    }

    /**
     * Copy a raw message with structural and string limits. Gson's deepCopy is
     * otherwise unbounded: a provider can keep adding large tool input/output
     * fields or deeply nested metadata to every cumulative assistant snapshot.
     * The returned tree remains valid JSON; only oversized string values,
     * excessive nodes, and excessive nesting are reduced.
     */
    private JsonObject boundedCopy(JsonObject source) {
        JsonElement copy = CliOutputLimits.boundedJsonCopy(source);
        return copy.isJsonObject() ? copy.getAsJsonObject() : new JsonObject();
    }

    /**
     * Build an index of content blocks by their unique keys.
     */
    private Map<String, Integer> buildContentIndex(JsonArray contentArray) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < contentArray.size(); i++) {
            JsonElement element = contentArray.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject block = element.getAsJsonObject();
            String key = getContentBlockKey(block);
            if (key != null && !index.containsKey(key)) {
                index.put(key, i);
            }
        }
        return index;
    }

    /**
     * Get the unique key for a content block.
     */
    private String getContentBlockKey(JsonObject block) {
        if (block.has("id") && block.get("id").isJsonPrimitive()) {
            return block.get("id").getAsString();
        }

        if (block.has("tool_use_id") && block.get("tool_use_id").isJsonPrimitive()) {
            return "tool_result:" + block.get("tool_use_id").getAsString();
        }

        return null;
    }

    private int findMatchingUnkeyedBlockIndex(
            JsonArray baseContent,
            JsonObject incomingBlock,
            Set<Integer> consumedUnkeyedIndexes
    ) {
        String incomingType = getContentBlockType(incomingBlock);
        if (incomingType == null) {
            return -1;
        }

        for (int i = 0; i < baseContent.size(); i++) {
            if (consumedUnkeyedIndexes.contains(i)) {
                continue;
            }

            JsonElement existingElement = baseContent.get(i);
            if (!existingElement.isJsonObject()) {
                continue;
            }

            JsonObject existingBlock = existingElement.getAsJsonObject();
            if (getContentBlockKey(existingBlock) != null) {
                continue;
            }

            if (!incomingType.equals(getContentBlockType(existingBlock))) {
                continue;
            }

            if (blocksLikelyRepresentSameSegment(existingBlock, incomingBlock)) {
                return i;
            }
        }

        return -1;
    }

    /**
     * 合并无 key 的内容块（text / thinking），选择更完整的内容保留。
     * 使用 switch 表达式按块类型分发处理逻辑。
     *
     * @param existingBlock  已有的内容块
     * @param incomingBlock  新到达的内容块
     * @return 合并后的内容块
     */
    private JsonObject mergeUnkeyedBlock(JsonObject existingBlock, JsonObject incomingBlock) {
        String type = getContentBlockType(incomingBlock);
        JsonObject merged = incomingBlock.deepCopy();

        switch (type != null ? type : "") {
            case CommonConstants.BLOCK_TYPE_TEXT:
                merged.addProperty(CommonConstants.JSON_KEY_TEXT, preferMoreCompleteContent(
                        getTextContent(existingBlock),
                        getTextContent(incomingBlock)
                ));
                return merged;

            case CommonConstants.BLOCK_TYPE_THINKING:
                String thinking = preferMoreCompleteContent(
                        getThinkingContent(existingBlock),
                        getThinkingContent(incomingBlock)
                );
                if (thinking != null && !thinking.isEmpty()) {
                    merged.addProperty(CommonConstants.JSON_KEY_THINKING, thinking);
                    merged.addProperty(CommonConstants.JSON_KEY_TEXT, thinking);
                }
                break;

            default:
                break;
        }

        return merged;
    }

    /**
     * 判断两个无 key 的内容块是否属于同一段落（segment）。
     * 使用 switch 表达式按块类型分发：text 块通过文本相关性判断，
     * thinking 块在早期流式阶段基于类型匹配，其他类型直接比较 JSON 内容。
     *
     * @param existingBlock  已有的内容块
     * @param incomingBlock  新到达的内容块
     * @return 如果两个块属于同一段落返回 true
     */
    private boolean blocksLikelyRepresentSameSegment(JsonObject existingBlock, JsonObject incomingBlock) {
        String type = getContentBlockType(incomingBlock);
        if (type == null || !type.equals(getContentBlockType(existingBlock))) {
            return false;
        }

        if (CommonConstants.BLOCK_TYPE_TEXT.equals(type)) {
            // Text blocks matched across a segment boundary must be strictly
            // prefix-related: two segments separated by a tool_use should share no
            // prefix relation, whereas the lenient suffix-prefix overlap would fire
            // on incidental shared boundaries (code fences, Markdown markers) and
            // wrongly merge a new segment into the previous one.
            return contentLooksRelatedStrict(getTextContent(existingBlock), getTextContent(incomingBlock));
        }

        if (CommonConstants.BLOCK_TYPE_THINKING.equals(type)) {
            String existingThinking = getThinkingContent(existingBlock);
            String incomingThinking = getThinkingContent(incomingBlock);
            // During early streaming, thinking content may not yet be populated,
            // so type-based matching alone determines block identity.
            if (existingThinking.isEmpty() || incomingThinking.isEmpty()) {
                return true;
            }
            // Thinking blocks can cross the same segment boundaries as text blocks.
            // A suffix-prefix overlap is especially easy to trigger with Markdown
            // markers (for example, adjacent "**...**" summaries), so only a
            // prefix-related snapshot may update the existing block.
            return contentLooksRelatedStrict(existingThinking, incomingThinking);
        }

        return existingBlock.equals(incomingBlock);
    }

    private int findLastSameTypeBlockIndex(JsonArray baseContent, JsonObject incomingBlock,
                                           Set<Integer> consumedUnkeyedIndexes) {
        String incomingType = getContentBlockType(incomingBlock);
        if (incomingType == null) {
            return -1;
        }
        // Only consider the tail of baseContent — do not cross keyed blocks
        // (tool_use, tool_result) to avoid merging content from different segments.
        // E.g., [text_1, tool_use, text_2] should NOT merge text_2 into text_1.
        for (int i = baseContent.size() - 1; i >= 0; i--) {
            JsonElement element = baseContent.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject existingBlock = element.getAsJsonObject();
            // Stop scanning if we hit a keyed block (tool_use, tool_result)
            if (getContentBlockKey(existingBlock) != null) {
                break;
            }
            // STREAM-03:跳过已被前序 incoming 块(经 findMatchingUnkeyedBlockIndex)合并占用的同类型块。
            // 否则当尾段同类块已被占用时,fallback 会把本不相关的新段并入已占用块,经
            // preferMoreCompleteContent 取较长者,丢较短段内容。跳过后返回 -1,由调用方 baseContent.add 新块。
            if (consumedUnkeyedIndexes.contains(i)) {
                continue;
            }
            if (incomingType.equals(getContentBlockType(existingBlock))) {
                return i;
            }
        }
        return -1;
    }

    private String getContentBlockType(JsonObject block) {
        return block.has("type") && !block.get("type").isJsonNull()
                ? block.get("type").getAsString()
                : null;
    }

    private String getTextContent(JsonObject block) {
        return block.has("text") && !block.get("text").isJsonNull()
                ? block.get("text").getAsString()
                : "";
    }

    private String getThinkingContent(JsonObject block) {
        if (block.has("thinking") && !block.get("thinking").isJsonNull()) {
            return block.get("thinking").getAsString();
        }
        return getTextContent(block);
    }

    // Whether two non-empty block contents are equal or one is a prefix of the other:
    // both relations describe one segment that is being filled by a fuller snapshot.
    private boolean isPrefixRelated(String existing, String incoming) {
        return existing.equals(incoming)
                || existing.startsWith(incoming)
                || incoming.startsWith(existing);
    }

    // Strict prefix-only relatedness for unkeyed text/thinking blocks. Omitting
    // suffix-prefix overlap prevents incidental shared boundaries (for example,
    // Markdown markers) from joining two independent streaming segments.
    private boolean contentLooksRelatedStrict(String existingText, String incomingText) {
        String existing = existingText != null ? existingText : "";
        String incoming = incomingText != null ? incomingText : "";
        // isPrefixRelated already treats an empty string as a prefix of any string,
        // so an empty block and a non-empty one are the same segment (the empty
        // one is the segment's leading edge before content arrives). This lets a
        // later, fuller snapshot fill an empty placeholder instead of duplicating
        // it.
        return isPrefixRelated(existing, incoming);
    }

    private String preferMoreCompleteContent(String existingText, String incomingText) {
        String existing = existingText != null ? existingText : "";
        String incoming = incomingText != null ? incomingText : "";

        if (incoming.isEmpty()) {
            return existing;
        }
        if (existing.isEmpty()) {
            return incoming;
        }
        if (incoming.startsWith(existing)) {
            return incoming;
        }
        if (existing.startsWith(incoming)) {
            return existing;
        }
        return incoming.length() >= existing.length() ? incoming : existing;
    }
}
