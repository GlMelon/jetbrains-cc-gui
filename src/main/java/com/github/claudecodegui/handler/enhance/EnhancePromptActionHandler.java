package com.github.claudecodegui.handler.enhance;

import com.github.claudecodegui.bridge.EnvironmentConfigurator;
import com.github.claudecodegui.bridge.ProcessManager;
import com.github.claudecodegui.handler.PromptEnhancerProcessRunner;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.service.GitCommitMessageService;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import static java.util.Map.entry;

/**
 * Prompt enhancement typed handler.
 * Calls the AI service to optimize and rewrite the user's prompt.
 *
 * Supports automatic collection of editor context information:
 * - User's selected code snippet
 * - Current open file info (path, content, language type)
 * - Cursor position and surrounding code
 *
 * @see com.github.claudecodegui.handler.PromptEnhancerHandler 旧实现（待删除）
 */
public final class EnhancePromptActionHandler implements FrontendActionHandler<String>, Disposable {

    private static final Logger LOG = Logger.getInstance(EnhancePromptActionHandler.class);
    private static final Gson gson = GsonHolder.GSON;

    private final Set<FutureTask<Void>> pendingTasks = ConcurrentHashMap.newKeySet();
    private volatile boolean disposed;

    // Hard timeout for the enhancement Node.js process. Without this, a network-stalled
    // SDK call would block the calling thread forever and leak the child process.
    private static final long ENHANCE_TIMEOUT_SECONDS = 60;
    // Grace window after the process exits, for the async reader thread to drain stdout.
    private static final long READER_DRAIN_SECONDS = 5;

    // Number of context lines to capture before and after the cursor
    private static final int CURSOR_CONTEXT_LINES = 10;

    private static final String DEFAULT_LANGUAGE = "text";

    /** 文件扩展名 → 编程语言名映射（未知扩展名回退 text）。 */
    private static final Map<String, String> EXTENSION_TO_LANGUAGE = Map.ofEntries(
            entry("java", "java"),
            entry("kt", "kotlin"), entry("kts", "kotlin"),
            entry("js", "javascript"), entry("jsx", "javascript"),
            entry("ts", "typescript"), entry("tsx", "typescript"),
            entry("py", "python"),
            entry("go", "go"),
            entry("rs", "rust"),
            entry("rb", "ruby"),
            entry("php", "php"),
            entry("c", "c"), entry("h", "c"),
            entry("cpp", "cpp"), entry("cc", "cpp"), entry("hpp", "cpp"),
            entry("cs", "csharp"),
            entry("swift", "swift"),
            entry("scala", "scala"),
            entry("vue", "vue"),
            entry("html", "html"), entry("htm", "html"),
            entry("css", "css"),
            entry("scss", "scss"),
            entry("less", "less"),
            entry("json", "json"),
            entry("xml", "xml"),
            entry("yaml", "yaml"), entry("yml", "yaml"),
            entry("md", "markdown"), entry("markdown", "markdown"),
            entry("sql", "sql"),
            entry("sh", "bash"), entry("bash", "bash"), entry("zsh", "bash")
    );

    // System prompt for prompt enhancement
    // Note: Must emphasize "only output the enhanced prompt" to prevent the AI from adding explanatory text
    // Includes guidance on leveraging editor context information
    private static final String ENHANCE_SYSTEM_PROMPT =
        "You are a prompt optimization expert. The user will send a prompt to be optimized in the format:\n" +
        "\"Please optimize the following prompt:\n[Original prompt]\"\n\n" +
        "The user may also provide relevant context information, including:\n" +
        "- [User's Selected Code]: Code snippet selected by the user in the editor\n" +
        "- [Code Around Cursor]: Context around the user's current editing position\n" +
        "- [Current File]: Path of the file the user is editing\n" +
        "- [Language Type]: Programming language of the current file\n" +
        "- [File Content Preview]: Partial content of the current file\n" +
        "- [Related Files]: Other files related to the current file\n" +
        "- [Project Type]: Type of the project (e.g., Java, React, etc.)\n\n" +
        "Your task is to optimize this prompt, making it clearer, more specific, and less ambiguous.\n\n" +
        "[IMPORTANT] Output Rules:\n" +
        "- Output ONLY the optimized prompt itself, with no additional content\n" +
        "- Do NOT add any explanations, prefixes, suffixes, or comments\n" +
        "- Do NOT use prefixes like \"Optimized prompt:\"\n" +
        "- Do NOT use Markdown headings or formatting\n" +
        "- Do NOT ask the user any questions\n" +
        "- Output the prompt text directly, ready to be copied and used\n" +
        "- [KEY] The optimized prompt MUST be in the same language as the user's original prompt. "
        + "If the original is in English, output in English; if in Chinese, output in Chinese; "
        + "if in Japanese, output in Japanese. Always match the language of the original prompt.\n\n" +
        "[How to Utilize Context Information]:\n" +
        "1. If the user's prompt contains vague references (e.g., \"this code\", \"this file\", \"here\"), replace them with specific descriptions based on the context\n" +
        "2. Add relevant professional terminology and best practices based on the code language type\n" +
        "3. Infer the user's possible intent from the selected code content and reflect it in the prompt\n" +
        "4. If file path information is available, reference specific file names or module names in the prompt\n" +
        "5. Do NOT include code snippets directly in the optimized prompt; instead, describe the code's characteristics or location\n\n" +
        "Optimization Principles:\n" +
        "1. Preserve the user's original intent\n" +
        "2. Add necessary context and details\n" +
        "3. Use clear, professional language\n" +
        "4. Correct grammar errors or typos\n" +
        "5. If the original prompt is too vague, add reasonable assumptions and constraints\n" +
        "6. Keep it concise; do not over-expand\n\n" +
        "Example 1 (without context):\n" +
        "User input: Please optimize the following prompt:\\n\\nAnalyze the logic\n" +
        "Your output: Please analyze the business logic of the current code file, including the main functionality, data flow, and key processing steps.\n\n" +
        "Example 2 (with context):\n" +
        "User input: Please optimize the following prompt:\\n\\nWhat's wrong with this code\\n\\n---\\n"
        + "Below is the relevant context information:\\n\\n[User's Selected Code]\\n"
        + "```java\\npublic void process() { ... }\\n```\\n\\n[Current File] UserService.java\\n"
        + "[Language Type] java\n" +
        "Your output: Please analyze the process() method in UserService.java, "
        + "checking for potential issues including but not limited to: null pointer exception risks, "
        + "resource leaks, thread safety concerns, performance bottlenecks, "
        + "and provide improvement suggestions.";

    @Override
    public UpstreamAction action() {
        return UpstreamAction.ENHANCE_PROMPT;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        if (!isActive(ctx)) {
            return;
        }

        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                if (!isActive(ctx)) {
                    return null;
                }
                JsonObject payloadObj = gson.fromJson(payload, JsonObject.class);
                String originalPrompt = payloadObj.has("prompt") ? payloadObj.get("prompt").getAsString() : "";
                String legacyModel = payloadObj.has("model") ? payloadObj.get("model").getAsString() : null;

                if (originalPrompt.isEmpty()) {
                    sendEnhanceResult(ctx, false, "", "Prompt is empty");
                    return null;
                }

                LOG.info("[EnhancePromptActionHandler] Starting prompt enhancement: " + originalPrompt.substring(0, Math.min(50, originalPrompt.length())) + "...");
                if (legacyModel != null) {
                    LOG.info("[EnhancePromptActionHandler] Received legacy model from frontend: " + legacyModel);
                }

                // Automatically collect context information from the editor
                JsonObject contextObj = collectEditorContext(ctx);

                // Log context information
                if (contextObj != null) {
                    LOG.info("[EnhancePromptActionHandler] Editor context collected:");
                    if (contextObj.has("selectedCode")) {
                        String selectedCode = contextObj.get("selectedCode").getAsString();
                        LOG.info("  - Selected code: " + selectedCode.length() + " characters");
                    }
                    if (contextObj.has("currentFile")) {
                        JsonObject currentFile = contextObj.getAsJsonObject("currentFile");
                        if (currentFile.has("path")) {
                            LOG.info("  - Current file: " + currentFile.get("path").getAsString());
                        }
                        if (currentFile.has("language")) {
                            LOG.info("  - Language type: " + currentFile.get("language").getAsString());
                        }
                    }
                    if (contextObj.has("cursorPosition")) {
                        JsonObject cursorPos = contextObj.getAsJsonObject("cursorPosition");
                        if (cursorPos.has("line")) {
                            LOG.info("  - Cursor position: line " + cursorPos.get("line").getAsInt());
                        }
                    }
                    if (contextObj.has("cursorContext")) {
                        String cursorContext = contextObj.get("cursorContext").getAsString();
                        LOG.info("  - Cursor context: " + cursorContext.length() + " characters");
                    }
                } else {
                    LOG.info("[EnhancePromptActionHandler] Failed to collect editor context");
                }

                if (!isActive(ctx)) {
                    return null;
                }
                // Call AI service for enhancement (passing context information)
                JsonObject promptEnhancerConfig = ctx.getSettingsService().getPromptEnhancerConfig();
                String enhancedPrompt = callAIForEnhancement(ctx, originalPrompt, legacyModel, contextObj, promptEnhancerConfig);

                if (enhancedPrompt != null && !enhancedPrompt.isEmpty()) {
                    LOG.info("[EnhancePromptActionHandler] Enhancement successful");
                    sendEnhanceResult(ctx, true, enhancedPrompt, null);
                } else if (isActive(ctx)) {
                    LOG.warn("[EnhancePromptActionHandler] Enhancement failed: empty result returned");
                    sendEnhanceResult(ctx, false, "", "Enhancement failed: empty result returned");
                }

            } catch (Exception e) {
                if (!disposed && !Thread.currentThread().isInterrupted() && !ctx.isDisposed()) {
                    LOG.error("[EnhancePromptActionHandler] Prompt enhancement failed: " + e.getMessage(), e);
                    sendEnhanceResult(ctx, false, "", "Enhancement failed: " + e.getMessage());
                }
            }
            return null;
        }) {
            @Override
            protected void done() {
                pendingTasks.remove(this);
            }
        };

        pendingTasks.add(task);
        if (!isActive(ctx)) {
            task.cancel(true);
            return;
        }
        try {
            AppExecutorUtil.getAppExecutorService().execute(task);
        } catch (RejectedExecutionException e) {
            pendingTasks.remove(task);
            LOG.debug("[EnhancePromptActionHandler] Enhancement task rejected: " + e.getMessage());
        }
    }

    private boolean isActive(HandlerContext ctx) {
        return !disposed && ctx != null && !ctx.isDisposed();
    }

    @Override
    public void dispose() {
        disposed = true;
        for (FutureTask<Void> task : pendingTasks) {
            task.cancel(true);
        }
        pendingTasks.clear();
    }

    /**
     * Collect context information from the editor.
     * Includes: selected code, current file info, cursor position, and code surrounding the cursor.
     *
     * @return a JsonObject containing context information, or null if unavailable
     */
    private JsonObject collectEditorContext(HandlerContext ctx) {
        AtomicReference<JsonObject> contextRef = new AtomicReference<>(null);

        try {
            // Use ReadAction to safely access the editor from the read thread
            ApplicationManager.getApplication().invokeAndWait(() -> {
                ApplicationManager.getApplication().runReadAction(() -> {
                    try {
                        JsonObject contextObj = new JsonObject();
                        boolean hasContext = false;

                        FileEditorManager fileEditorManager = FileEditorManager.getInstance(ctx.getProject());
                        FileEditor selectedEditor = fileEditorManager.getSelectedEditor();

                        if (selectedEditor instanceof TextEditor) {
                            Editor editor = ((TextEditor) selectedEditor).getEditor();
                            Document document = editor.getDocument();
                            VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);

                            if (virtualFile != null) {
                                // 1. Current file information
                                JsonObject currentFile = new JsonObject();
                                currentFile.addProperty("path", virtualFile.getPath());
                                currentFile.addProperty("language", getLanguageFromExtension(virtualFile.getExtension()));
                                contextObj.add("currentFile", currentFile);
                                hasContext = true;

                                // 2. Selected code
                                SelectionModel selectionModel = editor.getSelectionModel();
                                if (selectionModel.hasSelection()) {
                                    String selectedText = selectionModel.getSelectedText();
                                    if (selectedText != null && !selectedText.trim().isEmpty()) {
                                        contextObj.addProperty("selectedCode", selectedText);

                                        // Line number range of selected code
                                        int startLine = document.getLineNumber(selectionModel.getSelectionStart()) + 1;
                                        int endLine = document.getLineNumber(selectionModel.getSelectionEnd()) + 1;

                                        JsonObject selectionRange = new JsonObject();
                                        selectionRange.addProperty("startLine", startLine);
                                        selectionRange.addProperty("endLine", endLine);
                                        contextObj.add("selectionRange", selectionRange);
                                    }
                                }

                                // 3. Cursor position
                                int caretOffset = editor.getCaretModel().getOffset();
                                int caretLine = document.getLineNumber(caretOffset) + 1;
                                int caretColumn = caretOffset - document.getLineStartOffset(caretLine - 1) + 1;

                                JsonObject cursorPosition = new JsonObject();
                                cursorPosition.addProperty("line", caretLine);
                                cursorPosition.addProperty("column", caretColumn);
                                contextObj.add("cursorPosition", cursorPosition);

                                // 4. Code surrounding the cursor (if no code is selected)
                                if (!selectionModel.hasSelection() || selectionModel.getSelectedText() == null || selectionModel.getSelectedText().trim().isEmpty()) {
                                    String cursorContext = getCursorContext(document, caretLine - 1);
                                    if (cursorContext != null && !cursorContext.isEmpty()) {
                                        contextObj.addProperty("cursorContext", cursorContext);
                                    }
                                }
                            }
                        }

                        if (hasContext) {
                            contextRef.set(contextObj);
                        }
                    } catch (Exception e) {
                        LOG.warn("[EnhancePromptActionHandler] Failed to get editor context: " + e.getMessage());
                    }
                });
            });
        } catch (Exception e) {
            LOG.warn("[EnhancePromptActionHandler] ReadAction invocation failed: " + e.getMessage());
        }

        return contextRef.get();
    }

    /**
     * Get the code context surrounding the cursor.
     *
     * @param document the document object
     * @param caretLine the line where the cursor is located (0-based)
     * @return code snippet surrounding the cursor
     */
    private String getCursorContext(Document document, int caretLine) {
        try {
            int totalLines = document.getLineCount();
            int startLine = Math.max(0, caretLine - CURSOR_CONTEXT_LINES);
            int endLine = Math.min(totalLines - 1, caretLine + CURSOR_CONTEXT_LINES);

            int startOffset = document.getLineStartOffset(startLine);
            int endOffset = document.getLineEndOffset(endLine);

            return document.getText().substring(startOffset, endOffset);
        } catch (Exception e) {
            LOG.warn("[EnhancePromptActionHandler] Failed to get cursor context: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get the language type based on file extension.
     *
     * @param extension the file extension
     * @return language type name
     */
    static String getLanguageFromExtension(String extension) {
        if (extension == null) { return DEFAULT_LANGUAGE; }
        return EXTENSION_TO_LANGUAGE.getOrDefault(extension.toLowerCase(), DEFAULT_LANGUAGE);
    }

    /**
     * Build a compact, redaction-safe description of the prompt enhancer config
     * for logging. Avoids dumping the entire JSON (which may include unrelated
     * availability/resolution metadata).
     */
    private static String describePromptEnhancerConfig(JsonObject promptEnhancerConfig) {
        if (promptEnhancerConfig == null) {
            return "none";
        }
        String provider = null;
        if (promptEnhancerConfig.has("effectiveProvider")
                && !promptEnhancerConfig.get("effectiveProvider").isJsonNull()) {
            provider = promptEnhancerConfig.get("effectiveProvider").getAsString();
        }
        String model = null;
        if (provider != null
                && promptEnhancerConfig.has("models")
                && promptEnhancerConfig.get("models").isJsonObject()) {
            JsonObject models = promptEnhancerConfig.getAsJsonObject("models");
            if (models.has(provider) && !models.get(provider).isJsonNull()) {
                model = models.get(provider).getAsString();
            }
        }
        return (provider != null ? provider : "unresolved")
                + ", model: " + (model != null ? model : "default");
    }

    /**
     * 从 promptEnhancerConfig 提取 effectiveProvider(claude/codex),供 registry 解析 actualModel。
     */
    private static String extractEffectiveProvider(JsonObject promptEnhancerConfig) {
        if (promptEnhancerConfig == null
                || !promptEnhancerConfig.has("effectiveProvider")
                || promptEnhancerConfig.get("effectiveProvider").isJsonNull()) {
            return null;
        }
        String provider = promptEnhancerConfig.get("effectiveProvider").getAsString().trim();
        return provider.isEmpty() ? null : provider;
    }

    /**
     * Call the AI service for prompt enhancement.
     * @param originalPrompt the original prompt
     * @param legacyModel the legacy model to use as a fallback (optional)
     * @param contextObj context information (optional)
     * @param promptEnhancerConfig resolved prompt enhancer configuration
     */
    private String callAIForEnhancement(
            HandlerContext ctx,
            String originalPrompt,
            String legacyModel,
            JsonObject contextObj,
            JsonObject promptEnhancerConfig
    ) {
        LOG.info("[EnhancePromptActionHandler] Starting AI service call for prompt enhancement");
        LOG.info("[EnhancePromptActionHandler] Original prompt: " + originalPrompt);
        LOG.info("[EnhancePromptActionHandler] Using provider: " + describePromptEnhancerConfig(promptEnhancerConfig));

        try {
            // Call AI service using a Node.js script
            String nodeExecutable = ctx.getNodeService().getNodeExecutable();
            if (nodeExecutable == null) {
                LOG.error("[EnhancePromptActionHandler] Node.js is not configured");
                return null;
            }
            LOG.info("[EnhancePromptActionHandler] Node.js path: " + nodeExecutable);

            File bridgeDir = ctx.getNodeService().getSdkTestDir();
            if (bridgeDir == null || !bridgeDir.exists()) {
                LOG.error("[EnhancePromptActionHandler] AI Bridge directory does not exist");
                return null;
            }
            LOG.info("[EnhancePromptActionHandler] AI Bridge directory: " + bridgeDir.getAbsolutePath());

            // Build the command
            List<String> command = new ArrayList<>();
            command.add(nodeExecutable);
            command.add(new File(bridgeDir, "services/prompt-enhancer.js").getAbsolutePath());
            LOG.info("[EnhancePromptActionHandler] Executing command: " + String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(bridgeDir);
            pb.redirectErrorStream(true);

            // Set environment variables (lazy init: constructor calls CodemossSettingsService.getInstance())
            new EnvironmentConfigurator().updateProcessEnvironment(pb, nodeExecutable);

            // Build stdin payload
            JsonObject stdinInput = new JsonObject();
            stdinInput.addProperty("prompt", originalPrompt);
            stdinInput.addProperty("systemPrompt", ENHANCE_SYSTEM_PROMPT);
            if (legacyModel != null && !legacyModel.isEmpty()) {
                stdinInput.addProperty("legacyModel", legacyModel);
            }
            if (contextObj != null) {
                stdinInput.add("context", contextObj);
            }
            if (promptEnhancerConfig != null) {
                stdinInput.add("promptEnhancerConfig", promptEnhancerConfig);
            }
            // 通过 registry 解析 actualModel(与 chat/commitAi 一致),注入 stdin 供 prompt-enhancer.js
            // 优先使用。默认 registry actualModel 空 → null → 不注入 → JS 用现有 role→bucket 映射(零回归);
            // 仅用户自定义 registry actualModel(如 sonnet role → glm-5.2)时生效,避免 promptEnhancer
            // 绕过 registry 回退到 settings.json env vars(cc-switch 写,与 registry 不同源)。
            String effectiveProvider = extractEffectiveProvider(promptEnhancerConfig);
            if (effectiveProvider != null && legacyModel != null && !legacyModel.isEmpty()) {
                String enhanceActualModel = GitCommitMessageService.resolveActualModel(
                        ctx.getSettingsService().getModelRegistry(), effectiveProvider, legacyModel);
                if (enhanceActualModel != null && !enhanceActualModel.isEmpty()) {
                    stdinInput.addProperty("actualModel", enhanceActualModel);
                }
            }

            // Delegate to the runner so that:
            //  1. The process is registered with ProcessManager (cleanup on shutdown).
            //  2. A hard 60s timeout actually kills hung Node processes.
            //  3. The process is unregistered + force-killed in finally on every exit path.
            // The original code lacked all three, leaking child processes forever when
            // the SDK call hung on a stalled network connection.
            ProcessManager processManager = ctx.getNodeService().getProcessManager();
            StringBuilder response = new StringBuilder();
            StringBuilder allOutput = new StringBuilder();
            try {
                int exitCode = PromptEnhancerProcessRunner.runWithProcessManager(
                        pb,
                        processManager,
                        gson.toJson(stdinInput),
                        ENHANCE_TIMEOUT_SECONDS,
                        READER_DRAIN_SECONDS,
                        line -> {
                            allOutput.append(line).append("\n");
                            LOG.info("[EnhancePromptActionHandler] Node.js: " + line);
                            if (line.startsWith("[ENHANCED]")) {
                                String enhancedText = line.substring("[ENHANCED]".length()).trim();
                                enhancedText = enhancedText.replace("{{NEWLINE}}", "\n");
                                response.append(enhancedText);
                            }
                        }
                );
                LOG.info("[EnhancePromptActionHandler] Node.js process exit code: " + exitCode);
            } catch (TimeoutException te) {
                LOG.warn("[EnhancePromptActionHandler] " + te.getMessage());
                return null;
            }

            if (response.length() == 0 && allOutput.length() > 0) {
                LOG.warn("[EnhancePromptActionHandler] [ENHANCED] marker not found, full output:\n" + allOutput);
            }

            return response.toString();

        } catch (Exception e) {
            LOG.error("[EnhancePromptActionHandler] AI service call failed: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Send the enhancement result to the frontend.
     */
    private void sendEnhanceResult(HandlerContext ctx, boolean success, String enhancedPrompt, String error) {
        if (!isActive(ctx)) {
            return;
        }
        JsonObject result = new JsonObject();
        result.addProperty("success", success);
        result.addProperty("enhancedPrompt", enhancedPrompt);
        if (error != null) {
            result.addProperty("error", error);
        }

        String resultJson = gson.toJson(result);

        ApplicationManager.getApplication().invokeLater(() -> {
            if (isActive(ctx)) {
                ctx.dispatchEvent(DownstreamEvent.PROMPT_ENHANCED.value(), ctx.escapeJs(resultJson));
            }
        });
    }
}
