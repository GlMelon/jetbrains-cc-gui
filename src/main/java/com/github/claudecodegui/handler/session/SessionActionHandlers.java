package com.github.claudecodegui.handler.session;

import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.dependency.DependencyManager;
import com.github.claudecodegui.dependency.SdkDefinition;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.model.NodeDetectionResult;
import com.github.claudecodegui.notifications.ClaudeNotifier;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.session.SessionState;
import com.github.claudecodegui.session.runtime.EffectiveRuntimeResolver;
import com.github.claudecodegui.util.AttachmentStorageService;
import com.github.claudecodegui.util.GsonHolder;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Session action handlers container.
 * Holds shared logic for session message handling (send, interrupt, restart).
 */
public class SessionActionHandlers {

    private static final Logger LOG = Logger.getInstance(SessionActionHandlers.class);

    private final HandlerContext context;
    private final DependencyManager dependencyManager;

    public SessionActionHandlers(HandlerContext context) {
        this.context = context;
        this.dependencyManager = new DependencyManager(NodeDetector.getInstance());
    }

    // --- Response-handling methods (called by typed handlers) ---

    void handleSendMessage(String content) {
        String requestedInvocationMode = extractInvocationMode(content);
        boolean requiresNodeRuntime = !isCliModeActive(requestedInvocationMode);
        String nodeVersion = requiresNodeRuntime ? this.resolveNodeVersion() : null;
        if (requiresNodeRuntime && nodeVersion == null) {
            ApplicationManager.getApplication().invokeLater(() -> {
                context.callJavaScript("addErrorMessage", context.escapeJs("未检测到有效的 Node.js 版本，请在设置中配置或重新打开工具窗口。"));
            });
            return;
        }
        if (requiresNodeRuntime && !NodeDetector.isVersionSupported(nodeVersion)) {
            int minVersion = NodeDetector.MIN_NODE_MAJOR_VERSION;
            ApplicationManager.getApplication().invokeLater(() -> {
                context.callJavaScript("addErrorMessage", context.escapeJs(
                        "Node.js 版本过低 (" + nodeVersion + ")，插件需要 v" + minVersion + " 或更高版本才能正常运行。请在设置中配置正确的 Node.js 路径。"));
            });
            return;
        }

        String sdkValidationMessage = validateRequiredSdk(requestedInvocationMode);
        if (sdkValidationMessage != null) {
            ApplicationManager.getApplication().invokeLater(() -> {
                context.callJavaScript("addErrorMessage", context.escapeJs(sdkValidationMessage));
            });
            return;
        }

        // [FIX] Parse JSON format to extract text, agent info and file tags
        String prompt;
        String agentPrompt = null;
        List<String> fileTagPaths = null;
        String requestedPermissionMode = null;
        String resolvedRequestedInvocationMode = requestedInvocationMode;
        try {
            Gson gson = GsonHolder.GSON;
            JsonObject payload = gson.fromJson(content, JsonObject.class);
            prompt = payload != null && payload.has("text") && !payload.get("text").isJsonNull()
                             ? payload.get("text").getAsString()
                             : content; // Fallback to raw content if not JSON

            // Extract agent prompt from the message
            if (payload != null && payload.has("agent") && !payload.get("agent").isJsonNull()) {
                JsonObject agent = payload.getAsJsonObject("agent");
                if (agent.has("prompt") && !agent.get("prompt").isJsonNull()) {
                    agentPrompt = agent.get("prompt").getAsString();
                    String agentName = agent.has("name") ? agent.get("name").getAsString() : "Unknown";
                    LOG.info("[SessionActionHandlers] Using agent from message: " + agentName);
                }
            }

            // [FIX] Extract file tags from the message (for Codex context injection)
            if (payload != null && payload.has("fileTags") && payload.get("fileTags").isJsonArray()) {
                JsonArray fileTagsArray = payload.getAsJsonArray("fileTags");
                fileTagPaths = new ArrayList<>();
                for (int i = 0; i < fileTagsArray.size(); i++) {
                    JsonObject fileTag = fileTagsArray.get(i).getAsJsonObject();
                    if (fileTag.has("absolutePath") && !fileTag.get("absolutePath").isJsonNull()) {
                        fileTagPaths.add(fileTag.get("absolutePath").getAsString());
                    }
                }
                if (!fileTagPaths.isEmpty()) {
                    LOG.info("[SessionActionHandlers] Extracted " + fileTagPaths.size() + " file tags for context injection");
                }
            }

            // Legacy compatibility only. Normal webview sends do not use permissionMode;
            // SessionSendService resolves session mode before requested mode.
            if (payload != null && payload.has("permissionMode") && !payload.get("permissionMode").isJsonNull()) {
                String mode = payload.get("permissionMode").getAsString();
                if (SessionState.isValidPermissionMode(mode)) {
                    requestedPermissionMode = mode;
                } else {
                    LOG.warn("[SessionActionHandlers] Ignoring invalid permissionMode from payload: " + mode);
                }
            }

            if (payload != null && payload.has("invocationMode") && !payload.get("invocationMode").isJsonNull()) {
                String mode = payload.get("invocationMode").getAsString();
                if (SessionState.isValidClaudeInvocationMode(mode)) {
                    resolvedRequestedInvocationMode = mode;
                } else {
                    LOG.warn("[SessionActionHandlers] Ignoring invalid invocationMode from payload: " + mode);
                }
            }
        } catch (Exception e) {
            // If parsing fails, treat content as plain text (backward compatibility)
            LOG.debug("[SessionActionHandlers] Message is plain text, not JSON: " + e.getMessage());
            prompt = content;
        }

        final String finalPrompt = prompt;
        final String finalAgentPrompt = agentPrompt;
        final List<String> finalFileTagPaths = fileTagPaths;
        final String finalRequestedPermissionMode = requestedPermissionMode;
        final String finalRequestedInvocationMode = resolvedRequestedInvocationMode;
        ClaudeSession currentSession = context.getSession();
        LOG.debug(String.format(
                "[CliConcurrencyDiag][SessionActionHandlers] accepted send_message: provider=%s, requestedInvocationMode=%s, sessionId=%s, channelId=%s, promptChars=%d, thread=%s",
                currentSession != null ? currentSession.getProvider() : context.getCurrentProvider(),
                finalRequestedInvocationMode != null ? finalRequestedInvocationMode : "(none)",
                currentSession != null ? currentSession.getSessionId() : "(none)",
                currentSession != null ? currentSession.getChannelId() : "(none)",
                finalPrompt.length(),
                Thread.currentThread().getName()));

        CompletableFuture.runAsync(() -> {
            long dispatchStartNanos = System.nanoTime();
            String currentWorkingDir = determineWorkingDirectory();
            String previousCwd = context.getSession().getCwd();

            if (!currentWorkingDir.equals(previousCwd)) {
                context.getSession().setCwd(currentWorkingDir);
                LOG.info("[SessionActionHandlers] Updated working directory: " + currentWorkingDir);
            }

            // Capture project for use in async callbacks
            var project = context.getProject();
            if (project != null) {
                ClaudeNotifier.setWaiting(project);
            }

            // [FIX] Pass agent prompt and file tags directly to session
            LOG.info(String.format(
                    "[CliConcurrencyDiag][SessionActionHandlers] invoking session.send: provider=%s, invocationMode=%s, sessionId=%s, channelId=%s, elapsedMs=%d, thread=%s",
                    context.getSession().getProvider(),
                    finalRequestedInvocationMode != null ? finalRequestedInvocationMode : context.getSession().getClaudeInvocationMode(),
                    context.getSession().getSessionId(),
                    context.getSession().getChannelId(),
                    (System.nanoTime() - dispatchStartNanos) / 1_000_000,
                    Thread.currentThread().getName()));
            context.getSession().send(finalPrompt, finalAgentPrompt, finalFileTagPaths,
                    finalRequestedPermissionMode, finalRequestedInvocationMode)
                .thenRun(() -> {
                })
                .exceptionally(ex -> {
                    LOG.error("Failed to send message", ex);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        context.callJavaScript("addErrorMessage", context.escapeJs("发送失败: " + ex.getMessage()));
                    });
                    return null;
                    });
        });
    }

    void handleSendMessageWithAttachments(String content) {
        try {
            Gson gson = GsonHolder.GSON;
            JsonObject payload = gson.fromJson(content, JsonObject.class);
            String text = payload != null && payload.has("text") && !payload.get("text").isJsonNull()
                                  ? payload.get("text").getAsString()
                                  : "";

            List<ClaudeSession.Attachment> atts = new ArrayList<>();
            if (payload != null && payload.has("attachments") && payload.get("attachments").isJsonArray()) {
                JsonArray arr = payload.getAsJsonArray("attachments");
                LOG.debug("[ClaudeImageDiag][SessionActionHandlers] received attachment payload: count=" + arr.size() + ", textChars=" + text.length());
                String provider = context.getSession() != null ? context.getSession().getProvider() : context.getCurrentProvider();
                String currentSessionId = context.getSession() != null ? context.getSession().getSessionId() : null;
                String runtimeEpoch = context.getSession() != null ? context.getSession().getRuntimeSessionEpoch() : null;
                String sessionKey = currentSessionId != null && !currentSessionId.isBlank()
                        ? currentSessionId
                        : "epoch-" + (runtimeEpoch != null && !runtimeEpoch.isBlank() ? runtimeEpoch : System.currentTimeMillis());
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject a = arr.get(i).getAsJsonObject();
                    String fileName = a.has("fileName") && !a.get("fileName").isJsonNull()
                                              ? a.get("fileName").getAsString()
                                              : ("attachment-" + System.currentTimeMillis());
                    String mediaType = a.has("mediaType") && !a.get("mediaType").isJsonNull()
                                               ? a.get("mediaType").getAsString()
                                               : "application/octet-stream";
                    String data = a.has("data") && !a.get("data").isJsonNull()
                                          ? a.get("data").getAsString()
                                          : "";
                    LOG.debug(String.format(
                            "[ClaudeImageDiag][SessionActionHandlers] payload att[%d]: fileName=%s, mediaType=%s, dataChars=%d, provider=%s, sessionKey=%s",
                            i, fileName, mediaType,
                            data != null ? data.length() : 0,
                            provider, sessionKey));
                    ClaudeSession.Attachment attachment = new ClaudeSession.Attachment(fileName, mediaType, data);
                    if (mediaType.startsWith("image/") && !data.isBlank()) {
                        AttachmentStorageService.PersistedAttachment persisted = AttachmentStorageService.getInstance()
                                .persistImageAttachment(provider, sessionKey, fileName, mediaType, data);
                        if (persisted != null) {
                            attachment.localPath = persisted.localPath();
                            attachment.resourceUrl = persisted.resourceUrl();
                            attachment.thumbnailUrl = persisted.thumbnailUrl();
                            attachment.attachmentHash = persisted.hash();
                            // Image is now on disk — free the base64 string from the pipeline.
                            // Downstream (SDK/CLI) reads from localPath; display uses resourceUrl.
                            attachment.data = null;
                            LOG.debug(String.format(
                                    "[ClaudeImageDiag][SessionActionHandlers] persisted image att[%d]: localPath=%s, resourceUrl=%s, thumbnailUrl=%s, hash=%s",
                                    i, attachment.localPath, attachment.resourceUrl,
                                    attachment.thumbnailUrl, attachment.attachmentHash));
                        } else {
                            LOG.debug("[ClaudeImageDiag][SessionActionHandlers] image persistence returned null for att[" + i + "]: fileName=" + fileName + ", mediaType=" + mediaType);
                        }
                    } else if (mediaType.startsWith("image/")) {
                        LOG.debug("[ClaudeImageDiag][SessionActionHandlers] image attachment has no base64 data: att[" + i + "], fileName=" + fileName);
                    }
                    atts.add(attachment);
                }
            } else {
                LOG.debug("[ClaudeImageDiag][SessionActionHandlers] no attachments array in payload for send_message_with_attachments");
            }

            // [FIX] Extract agent prompt from the payload for per-tab agent selection
            String agentPrompt = null;
            String requestedPermissionMode = null;
            String requestedInvocationMode = null;
            if (payload != null && payload.has("agent") && !payload.get("agent").isJsonNull()) {
                JsonObject agent = payload.getAsJsonObject("agent");
                if (agent.has("prompt") && !agent.get("prompt").isJsonNull()) {
                    agentPrompt = agent.get("prompt").getAsString();
                    String agentName = agent.has("name") ? agent.get("name").getAsString() : "Unknown";
                    LOG.info("[SessionActionHandlers] Using agent from attachment message: " + agentName);
                }
            }

            // [FIX] Extract file tags from the payload (for Codex context injection)
            List<String> fileTagPaths = null;
            if (payload != null && payload.has("fileTags") && payload.get("fileTags").isJsonArray()) {
                JsonArray fileTagsArray = payload.getAsJsonArray("fileTags");
                fileTagPaths = new ArrayList<>();
                for (int i = 0; i < fileTagsArray.size(); i++) {
                    JsonObject fileTag = fileTagsArray.get(i).getAsJsonObject();
                    if (fileTag.has("absolutePath") && !fileTag.get("absolutePath").isJsonNull()) {
                        fileTagPaths.add(fileTag.get("absolutePath").getAsString());
                    }
                }
                if (!fileTagPaths.isEmpty()) {
                    LOG.info("[SessionActionHandlers] Extracted " + fileTagPaths.size() + " file tags for attachment message");
                }
            }

            // Legacy compatibility only. Normal webview sends do not use permissionMode;
            // SessionSendService resolves session mode before requested mode.
            if (payload != null && payload.has("permissionMode") && !payload.get("permissionMode").isJsonNull()) {
                String mode = payload.get("permissionMode").getAsString();
                if (SessionState.isValidPermissionMode(mode)) {
                    requestedPermissionMode = mode;
                } else {
                    LOG.warn("[SessionActionHandlers] Ignoring invalid permissionMode from attachment payload: " + mode);
                }
            }

            if (payload != null && payload.has("invocationMode") && !payload.get("invocationMode").isJsonNull()) {
                String mode = payload.get("invocationMode").getAsString();
                if (SessionState.isValidClaudeInvocationMode(mode)) {
                    requestedInvocationMode = mode;
                } else {
                    LOG.warn("[SessionActionHandlers] Ignoring invalid invocationMode from attachment payload: " + mode);
                }
            }

            sendMessageWithAttachments(text, atts, agentPrompt, fileTagPaths, requestedPermissionMode, requestedInvocationMode);
        } catch (Exception e) {
            LOG.error("[SessionActionHandlers] 解析附件负载失败: " + e.getMessage(), e);
            handleSendMessage(content);
        }
    }

    void handleInterruptSession() {
        context.getSession().interrupt().thenRun(() -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                // [FIX] Notify frontend that stream has ended and reset loading state
                // This ensures streamActive flag is reset and loading=false takes effect
                context.callJavaScript("onStreamEnd");
                context.callJavaScript("showLoading", "false");
            });
        });
    }

    void handleRestartSession() {
        context.getSession().restart().thenRun(() -> {
            ApplicationManager.getApplication().invokeLater(() -> {});
        });
    }

    // --- Private helpers ---

    private void sendMessageWithAttachments(
        String prompt,
        List<ClaudeSession.Attachment> attachments,
        String agentPrompt,
        List<String> fileTagPaths,
        String requestedPermissionMode,
        String requestedInvocationMode
    ) {
        // Version check (consistent with handleSendMessage)
        boolean requiresNodeRuntime = !isCliModeActive(requestedInvocationMode);
        String nodeVersion = requiresNodeRuntime ? this.resolveNodeVersion() : null;
        if (requiresNodeRuntime && nodeVersion == null) {
            ApplicationManager.getApplication().invokeLater(() -> {
                context.callJavaScript("addErrorMessage", context.escapeJs("未检测到有效的 Node.js 版本，请在设置中配置或重新打开工具窗口。"));
            });
            return;
        }

        String sdkValidationMessage = validateRequiredSdk(requestedInvocationMode);
        if (sdkValidationMessage != null) {
            ApplicationManager.getApplication().invokeLater(() -> {
                context.callJavaScript("addErrorMessage", context.escapeJs(sdkValidationMessage));
            });
            return;
        }
        if (requiresNodeRuntime && !NodeDetector.isVersionSupported(nodeVersion)) {
            int minVersion = NodeDetector.MIN_NODE_MAJOR_VERSION;
            ApplicationManager.getApplication().invokeLater(() -> {
                context.callJavaScript("addErrorMessage", context.escapeJs(
                        "Node.js 版本过低 (" + nodeVersion + ")，插件需要 v" + minVersion + " 或更高版本才能正常运行。请在设置中配置正确的 Node.js 路径。"));
            });
            return;
        }

        final String finalAgentPrompt = agentPrompt;
        final List<String> finalFileTagPaths = fileTagPaths;
        final String finalRequestedPermissionMode = requestedPermissionMode;
        final String finalRequestedInvocationMode = requestedInvocationMode;
        ClaudeSession currentSession = context.getSession();
        LOG.debug(String.format(
                "[CliConcurrencyDiag][SessionActionHandlers] accepted send_msg_atts: provider=%s, invMode=%s, sid=%s, chId=%s, chars=%d, atts=%d, thread=%s",
                currentSession != null ? currentSession.getProvider() : context.getCurrentProvider(),
                finalRequestedInvocationMode != null ? finalRequestedInvocationMode : "(none)",
                currentSession != null ? currentSession.getSessionId() : "(none)",
                currentSession != null ? currentSession.getChannelId() : "(none)",
                prompt.length(),
                attachments != null ? attachments.size() : 0,
                Thread.currentThread().getName()));

        CompletableFuture.runAsync(() -> {
            long dispatchStartNanos = System.nanoTime();
            String currentWorkingDir = determineWorkingDirectory();
            String previousCwd = context.getSession().getCwd();
            if (!currentWorkingDir.equals(previousCwd)) {
                context.getSession().setCwd(currentWorkingDir);
                LOG.info("[SessionActionHandlers] Updated working directory: " + currentWorkingDir);
            }

            // Capture project for use in async callbacks
            var project = context.getProject();
            if (project != null) {
                ClaudeNotifier.setWaiting(project);
            }

            // [FIX] Pass agent prompt and file tags directly to session
            LOG.info(String.format(
                    "[CliConcurrencyDiag][SessionActionHandlers] invoking session.send atts: provider=%s, invMode=%s, sid=%s, chId=%s, elapsed=%dms, thread=%s",
                    context.getSession().getProvider(),
                    finalRequestedInvocationMode != null ? finalRequestedInvocationMode : context.getSession().getClaudeInvocationMode(),
                    context.getSession().getSessionId(),
                    context.getSession().getChannelId(),
                    (System.nanoTime() - dispatchStartNanos) / 1_000_000,
                    Thread.currentThread().getName()));
            context.getSession().send(prompt, attachments, finalAgentPrompt, finalFileTagPaths,
                    finalRequestedPermissionMode, finalRequestedInvocationMode)
                .thenRun(() -> {
                })
                .exceptionally(ex -> {
                    LOG.error("Failed to send message with attachments", ex);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        context.callJavaScript("addErrorMessage", context.escapeJs("发送失败: " + ex.getMessage()));
                    });
                    return null;
                    });
        });
    }

    private String resolveNodeVersion() {
        String nodeVersion = context.getClaudeSDKBridge().getCachedNodeVersion();
        if (nodeVersion != null) {
            return nodeVersion;
        }
        // Version absent — try to recover using the cached path (path may still be valid).
        String cachedPath = context.getClaudeSDKBridge().getCachedNodePath();
        if (cachedPath == null || cachedPath.isEmpty()) {
            return null;
        }
        LOG.info("[SessionActionHandlers] Node version cache miss, re-verifying path: " + cachedPath);
        NodeDetectionResult recovery = context.getClaudeSDKBridge().verifyAndCacheNodePath(cachedPath);
        if (recovery != null && recovery.isFound()) {
            return recovery.getNodeVersion();
        }
        return null;
    }

    private String extractInvocationMode(String content) {
        try {
            JsonObject payload = GsonHolder.GSON.fromJson(content, JsonObject.class);
            if (payload != null && payload.has("invocationMode") && !payload.get("invocationMode").isJsonNull()) {
                String mode = payload.get("invocationMode").getAsString();
                if (SessionState.isValidClaudeInvocationMode(mode)) {
                    return mode;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean isCliModeActive(String requestedInvocationMode) {
        try {
            ClaudeSession currentSession = context.getSession();
            String provider = currentSession != null ? currentSession.getProvider() : context.getCurrentProvider();
            String sessionMode = currentSession != null ? currentSession.getClaudeInvocationMode() : null;
            return EffectiveRuntimeResolver
                    .isCliMode(
                            provider,
                            requestedInvocationMode,
                            sessionMode,
                            context.getSettingsService().getRuntimePolicy()
                    );
        } catch (Exception e) {
            // 与 ClaudeSession.isCliRuntime 保持一致:解析失败(policy 缺失/禁用)默认非 CLI,
            // 走 SDK 清理路径,避免 IllegalStateException 冒泡中断单次消息处理。
            LOG.warn("[Runtime] Failed to resolve CLI mode, defaulting to false: " + e.getMessage());
            return false;
        }
    }

    private String validateRequiredSdk(String requestedInvocationMode) {
        ClaudeSession currentSession = context.getSession();
        String provider = currentSession != null ? currentSession.getProvider() : context.getCurrentProvider();

        if (provider == null || provider.isBlank()) {
            provider = CommonConstants.PROVIDER_CLAUDE;
        }

        if (CommonConstants.PROVIDER_CLAUDE.equals(provider) && isCliModeActive(requestedInvocationMode)) {
            return null;
        }

        SdkDefinition sdkDefinition = SdkDefinition.fromProvider(provider);
        if (sdkDefinition == null) {
            return null;
        }

        try {
            if (dependencyManager.isInstalled(sdkDefinition.getId())) {
                return null;
            }
        } catch (Exception e) {
            LOG.warn("[SessionActionHandlers] Failed to verify SDK installation for provider " + provider + ": " + e.getMessage(), e);
        }

        return sdkDefinition.getDisplayName() + " 未安装或不可用，请前往设置中的 Dependencies 页面安装后再发送消息。";
    }

    private String determineWorkingDirectory() {
        String projectPath = context.getProject().getBasePath();

        // Prefer the user-configured working directory first
        // (relative paths are resolved only when projectPath is valid).
        if (projectPath != null && new File(projectPath).exists()) {
            try {
                com.github.claudecodegui.settings.CodemossSettingsService settingsService =
                        new com.github.claudecodegui.settings.CodemossSettingsService();
                String customWorkingDir = settingsService.getCustomWorkingDirectory(projectPath);

                if (customWorkingDir != null && !customWorkingDir.isEmpty()) {
                    // Resolve relative paths against the project root.
                    File workingDirFile = new File(customWorkingDir);
                    if (!workingDirFile.isAbsolute()) {
                        workingDirFile = new File(projectPath, customWorkingDir);
                    }

                    // Validate that the directory exists.
                    if (workingDirFile.exists() && workingDirFile.isDirectory()) {
                        String resolvedPath = workingDirFile.getAbsolutePath();
                        LOG.info("[SessionActionHandlers] Using custom working directory: " + resolvedPath);
                        return resolvedPath;
                    } else {
                        LOG.warn("[SessionActionHandlers] Custom working directory does not exist: " + workingDirFile.getAbsolutePath() + ", falling back");
                    }
                }
            } catch (Exception e) {
                LOG.warn("[SessionActionHandlers] Failed to read custom working directory: " + e.getMessage());
            }
        }

        // When projectPath is invalid (null or missing), try the active file's
        // parent directory first — typical case: single-file temporary project
        // (projectPath in /tmp) while the actual file is under the user's home.
        if (projectPath == null || !new File(projectPath).exists()) {
            String activeFileDir = resolveWorkingDirectoryFromActiveFile(projectPath);
            if (activeFileDir != null && !activeFileDir.isEmpty()) {
                return activeFileDir;
            }
            String userHome = PlatformUtils.getHomeDirectory();
            LOG.warn("[SessionActionHandlers] Using user home directory as fallback: " + userHome);
            return userHome;
        }

        // Use project root as the default working directory.
        return projectPath;
    }

    private String resolveWorkingDirectoryFromActiveFile(String projectPath) {
        try {
            VirtualFile[] selectedFiles = ApplicationManager.getApplication().runReadAction(
                    (com.intellij.openapi.util.Computable<VirtualFile[]>) () ->
                            FileEditorManager.getInstance(context.getProject()).getSelectedFiles()
            );
            if (selectedFiles == null || selectedFiles.length == 0) {
                return null;
            }

            for (VirtualFile selectedFile : selectedFiles) {
                if (selectedFile == null || !selectedFile.isInLocalFileSystem()) {
                    continue;
                }

                String selectedPath = selectedFile.getPath();
                if (selectedPath == null || selectedPath.isEmpty()) {
                    continue;
                }

                File localFile = new File(selectedPath);
                if (!localFile.exists()) {
                    continue;
                }

                String filePath = localFile.getAbsolutePath();
                String candidateDir = localFile.isDirectory()
                        ? filePath
                        : localFile.getParent();
                if (candidateDir == null || candidateDir.isEmpty()) {
                    continue;
                }

                if (projectPath != null && !projectPath.isEmpty() && isPathWithin(filePath, projectPath)) {
                    continue;
                }

                LOG.info("[SessionActionHandlers] Active file is outside project root, using its parent as working directory: "
                        + candidateDir + " (activeFile=" + filePath + ", projectPath=" + projectPath + ")");
                return candidateDir;
            }
        } catch (Exception e) {
            LOG.debug("[SessionActionHandlers] Failed to resolve working directory from active file: " + e.getMessage());
        }

        return null;
    }

    private boolean isPathWithin(String childPath, String basePath) {
        if (childPath == null || basePath == null) {
            return false;
        }

        try {
            Path child = Paths.get(childPath).toAbsolutePath().normalize();
            Path base = Paths.get(basePath).toAbsolutePath().normalize();
            return child.startsWith(base);
        } catch (Exception ignored) {
            return childPath.startsWith(basePath);
        }
    }
}
