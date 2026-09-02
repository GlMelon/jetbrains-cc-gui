package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.session.ClaudeSession;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * 统一请求 DTO，合并 CLI 请求字段。
 * <p>
 * 作为 SessionRuntime.send() 的入参，由 SessionSendService 构造，
 * 各实现类按需取字段转发给底层 CLI session。
 */
public record SessionRequest(
        RuntimeKey key,
        ProviderType provider,
        String message,
        String sessionId,
        String cwd,
        List<ClaudeSession.Attachment> attachments,
        JsonObject openedFiles,
        List<String> fileTagPaths,
        String agentPrompt,
        String permissionMode,
        String model,
        String actualModel,
        String reasoningEffort,
        String codexServiceTier,
        String permissionSessionId,
        Boolean streaming,
        Boolean disableThinking,
        Boolean thinkingOutputEnabled,
        Map<String, String> env,
        long responseTurnEpoch
) {
    public SessionRequest(
            RuntimeKey key,
            ProviderType provider,
            String message,
            String sessionId,
            String cwd,
            List<ClaudeSession.Attachment> attachments,
            JsonObject openedFiles,
            List<String> fileTagPaths,
            String agentPrompt,
            String permissionMode,
            String model,
            String actualModel,
            String reasoningEffort,
            String permissionSessionId,
            Boolean streaming,
            Boolean disableThinking,
            Map<String, String> env
    ) {
        this(
                key,
                provider,
                message,
                sessionId,
                cwd,
                attachments,
                openedFiles,
                fileTagPaths,
                agentPrompt,
                permissionMode,
                model,
                actualModel,
                reasoningEffort,
                null,
                permissionSessionId,
                streaming,
                disableThinking,
                Boolean.TRUE,
                env,
                0L
        );
    }

    public SessionRequest {
        if (key == null) {
            throw new IllegalArgumentException("key is required");
        }
        if (provider == null) {
            throw new IllegalArgumentException("provider is required");
        }
        message = message != null ? message : "";
        attachments = attachments != null ? List.copyOf(attachments) : List.of();
        fileTagPaths = fileTagPaths != null ? List.copyOf(fileTagPaths) : List.of();
        env = env != null ? Map.copyOf(env) : Map.of();
        actualModel = actualModel != null && !actualModel.isBlank() ? actualModel : null;
        codexServiceTier = codexServiceTier != null && !codexServiceTier.isBlank() ? codexServiceTier : null;
        thinkingOutputEnabled = thinkingOutputEnabled != null ? thinkingOutputEnabled : Boolean.TRUE;
    }
}
