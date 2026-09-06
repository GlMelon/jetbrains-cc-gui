package com.github.claudecodegui.permission;

import com.github.claudecodegui.util.HashingUtil;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores remembered permission decisions at tool and tool+input granularity.
 */
class PermissionDecisionStore {

    // Upper bound on remembered parameter decisions; oldest (least recently used)
    // entries are evicted beyond this so a long session cannot grow memory unboundedly.
    private static final int MAX_PARAMETER_MEMORY_ENTRIES = 256;
    // Inputs JSON longer than this is replaced by its SHA-256 in the memory key, so an
    // "always allow" on a huge Edit/Write payload does not pin the full text in memory.
    // Hash (not truncation) keeps match semantics exact: identical inputs always hit,
    // distinct inputs miss except for a cryptographically negligible collision.
    private static final int PARAMETER_KEY_RAW_MAX_LENGTH = 2048;

    private final Map<String, Integer> parameterDecisionMemory = Collections.synchronizedMap(
            new LinkedHashMap<String, Integer>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                    return size() > MAX_PARAMETER_MEMORY_ENTRIES;
                }
            });
    private final Map<String, Boolean> toolDecisionMemory = new ConcurrentHashMap<>();

    PermissionService.PermissionResponse getToolDecision(String toolName) {
        Boolean allow = toolDecisionMemory.get(toolName);
        if (allow == null) {
            return null;
        }
        return allow
                ? PermissionService.PermissionResponse.ALLOW_ALWAYS
                : PermissionService.PermissionResponse.DENY;
    }

    /**
     * Command-execution tools (Bash; Codex shell commands also map to "Bash"; plus Agent
     * launches) whose parameter-level memory key is built from the command string alone.
     * See buildMemoryKey.
     */
    static boolean isCommandExecutionTool(String toolName) {
        return "Bash".equals(toolName) || "Agent".equals(toolName);
    }

    PermissionService.PermissionResponse getParameterDecision(String toolName, JsonObject inputs) {
        Integer remembered = parameterDecisionMemory.get(buildMemoryKey(toolName, inputs));
        if (remembered == null) {
            return null;
        }
        return PermissionService.PermissionResponse.fromValue(remembered);
    }

    String buildMemoryKey(String toolName, JsonObject inputs) {
        // For command-execution tools, key only on the command string. The rest of the input
        // (e.g. Bash "description", which the model regenerates every call) is volatile and
        // would otherwise make a remembered command-level decision almost never match on the
        // next, differently-described invocation of the very same command.
        if (isCommandExecutionTool(toolName) && inputs != null
                && inputs.has("command") && inputs.get("command").isJsonPrimitive()) {
            return toolName + ":cmd:" + fingerprint(inputs.get("command").getAsString());
        }
        return toolName + ":" + fingerprint(inputs != null ? inputs.toString() : "null");
    }

    private static String fingerprint(String value) {
        if (value.length() <= PARAMETER_KEY_RAW_MAX_LENGTH) {
            return value;
        }
        return "sha256:" + HashingUtil.sha256Hex(value);
    }

    void rememberToolDecision(String toolName, PermissionService.PermissionResponse decision) {
        if (toolName == null || decision == null) {
            return;
        }
        if (decision == PermissionService.PermissionResponse.ALLOW_ALWAYS) {
            toolDecisionMemory.put(toolName, true);
        } else if (decision == PermissionService.PermissionResponse.DENY) {
            toolDecisionMemory.put(toolName, false);
        }
    }

    void rememberParameterDecision(String toolName, JsonObject inputs, PermissionService.PermissionResponse decision) {
        if (toolName == null || decision == null) {
            return;
        }
        parameterDecisionMemory.put(buildMemoryKey(toolName, inputs), decision.getValue());
    }

    void clear() {
        parameterDecisionMemory.clear();
        toolDecisionMemory.clear();
    }

    int getParameterMemorySize() {
        return parameterDecisionMemory.size();
    }

    int getToolMemorySize() {
        return toolDecisionMemory.size();
    }
}
