package com.github.claudecodegui.handler.context;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.IntentionManager;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.MarkupModelEx;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Context collector using IntelliJ Extension Point for language-specific providers (A5).
 *
 * <p>Loads {@link SemanticContextProvider} EPs registered in plugin.xml.
 * Falls back to platform-level context collection when no provider EP is available.</p>
 */
public class ContextCollector {

    private static final Logger LOG = Logger.getInstance(ContextCollector.class);

    private static final ExtensionPointName<SemanticContextProvider> EP_NAME =
            SemanticContextProvider.EP_NAME;

    // Constants for context collection limits
    private static final int CODE_WINDOW_LINES_RANGE = 40;
    private static final int HIGHLIGHT_LINES_RANGE = 10;
    private static final int INJECTION_SEARCH_RANGE = 500;

    private static final Set<String> IGNORED_DIRS = new HashSet<>(List.of(
        "node_modules", "build", "out", "target", "vendor", ".gradle", ".idea", ".git", ".vh", "dist", "bin"
    ));

    public @NotNull JsonObject collectSemanticContext(@NotNull Editor editor, @NotNull Project project) {
        JsonObject semanticData = new JsonObject();
        try {
            Document document = editor.getDocument();
            PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document);
            if (psiFile == null) {
                return semanticData;
            }

            collectDataRobustly(semanticData, editor, project, psiFile, document);

        } catch (Throwable t) {
            LOG.warn("Critical failure in collectSemanticContext: " + t.getMessage(), t);
        }
        return semanticData;
    }

    private void collectDataRobustly(JsonObject semanticData, Editor editor, Project project, PsiFile psiFile, Document document) {
        // 1. 通过 EP 收集语言特定的语义上下文(Java/Python 等)
        collectProviderContext(EP_NAME.getExtensionList(), semanticData, editor, project, psiFile, document);

        int offset = editor.getCaretModel().getOffset();

        // 2. Comments (platform-independent)
        try {
            JsonObject comments = getNearbyComments(psiFile, offset);
            if (comments.size() > 0) { semanticData.add("comments", comments); }
        } catch (Throwable t) {
            LOG.debug("Failed to collect comments: " + t.getMessage());
        }

        // 3. Highlight Information (platform-independent)
        try {
            JsonArray highlights = getHighlightInfo(editor, document);
            if (highlights.size() > 0) { semanticData.add("highlights", highlights); }
        } catch (Throwable t) {
            LOG.debug("Failed to collect highlights: " + t.getMessage());
        }

        // 4. Injected Languages (platform-independent)
        try {
            JsonArray injected = getInjectedLanguages(psiFile, offset, project);
            if (injected.size() > 0) { semanticData.add("injectedLanguages", injected); }
        } catch (Throwable t) {
            LOG.debug("Failed to collect injected languages: " + t.getMessage());
        }

        // 5. Syntax Errors (platform-independent)
        try {
            JsonArray errors = getSyntaxErrors(psiFile);
            if (errors.size() > 0) { semanticData.add("errors", errors); }
        } catch (Throwable t) {
            LOG.debug("Failed to collect syntax errors: " + t.getMessage());
        }

        // 6. Quick Fixes (platform-independent)
        try {
            JsonArray quickFixes = getQuickFixes(editor, psiFile, project);
            if (quickFixes.size() > 0) { semanticData.add("quickFixes", quickFixes); }
        } catch (Throwable t) {
            LOG.debug("Failed to collect quick fixes: " + t.getMessage());
        }

        // 7. Focused Context (code window)
        try {
            boolean focusedCollected = false;

            // 检查是否有 EP 收集了 focused context
            if (semanticData.has("selectedFunctions")) {
                focusedCollected = true;
            }

            // Always provide code window as fallback or primary context
            if (!focusedCollected) {
                semanticData.add("currentWindow", getCodeWindow(editor, document));
            }
        } catch (Throwable t) {
            LOG.debug("Failed to collect focused context: " + t.getMessage());
        }
    }

    private JsonObject getCodeWindow(Editor editor, Document document) {
        JsonObject window = new JsonObject();
        try {
            int cursorLine = document.getLineNumber(editor.getCaretModel().getOffset());
            int totalLines = document.getLineCount();

            int startLine = Math.max(0, cursorLine - CODE_WINDOW_LINES_RANGE);
            int endLine = Math.min(totalLines - 1, cursorLine + CODE_WINDOW_LINES_RANGE);

            int startOffset = document.getLineStartOffset(startLine);
            int endOffset = document.getLineEndOffset(endLine);

            String content = document.getText(new TextRange(startOffset, endOffset));

            window.addProperty("startLine", startLine + 1);
            window.addProperty("endLine", endLine + 1);
            window.addProperty("content", content);

        } catch (Exception e) {
            LOG.warn("Failed to get code window: " + e.getMessage());
        }
        return window;
    }

    void collectProviderContext(
            @NotNull List<SemanticContextProvider> providers,
            @NotNull JsonObject semanticData,
            Editor editor,
            Project project,
            PsiFile psiFile,
            Document document) {
        if (providers.isEmpty()) {
            LOG.debug("No SemanticContextProvider EPs available, using platform-only context");
            return;
        }

        for (SemanticContextProvider provider : providers) {
            try {
                provider.collectSemanticContext(semanticData, editor, project, psiFile, document);
            } catch (Throwable t) {
                LOG.debug("SemanticContextProvider EP failed: " + provider.getClass().getSimpleName()
                        + ": " + t.getMessage());
            }
        }
    }

    private JsonObject getNearbyComments(PsiFile psiFile, int offset) {
        JsonObject comments = new JsonObject();
        Document document = PsiDocumentManager.getInstance(psiFile.getProject()).getDocument(psiFile);
        if (document == null) { return comments; }

        int currentLine = document.getLineNumber(offset);
        int searchRange = 5;
        int startLine = Math.max(0, currentLine - searchRange);
        int endLine = Math.min(document.getLineCount() - 1, currentLine + searchRange);

        JsonArray before = new JsonArray();
        JsonArray after = new JsonArray();

        for (int line = startLine; line <= endLine; line++) {
            int lineStart = document.getLineStartOffset(line);
            int lineEnd = document.getLineEndOffset(line);

            PsiElement elem = psiFile.findElementAt(lineStart);
            while (elem != null && elem.getTextRange().getStartOffset() < lineEnd) {
                if (elem instanceof PsiComment) {
                    JsonObject c = new JsonObject();
                    c.addProperty("line", line + 1);
                    c.addProperty("text", elem.getText().trim());
                    if (line < currentLine) { before.add(c); }
                    else if (line > currentLine) { after.add(c); }
                }
                elem = PsiTreeUtil.nextVisibleLeaf(elem);
            }
        }

        if (before.size() > 0) { comments.add("before", before); }
        if (after.size() > 0) { comments.add("after", after); }

        return comments;
    }

    private JsonArray getHighlightInfo(Editor editor, Document document) {
        JsonArray highlights = new JsonArray();
        try {
            int offset = editor.getCaretModel().getOffset();
            Project project = editor.getProject();
            if (project == null) { return highlights; }

            MarkupModelEx markupModel = (MarkupModelEx) DocumentMarkupModel.forDocument(document, project, false);
            if (markupModel == null) { return highlights; }

            int cursorLine = document.getLineNumber(offset);
            int startLine = Math.max(0, cursorLine - HIGHLIGHT_LINES_RANGE);
            int endLine = Math.min(document.getLineCount() - 1, cursorLine + HIGHLIGHT_LINES_RANGE);

            int searchStart = document.getLineStartOffset(startLine);
            int searchEnd = document.getLineEndOffset(endLine);

            for (RangeHighlighter highlighter : markupModel.getAllHighlighters()) {
                if (highlighter.getStartOffset() >= searchEnd || highlighter.getEndOffset() <= searchStart) {
                    continue;
                }

                HighlightInfo info = HighlightInfo.fromRangeHighlighter(highlighter);
                if (info == null) { continue; }

                String description = info.getDescription();
                String severityName = info.getSeverity().getName();

                if ("INFO".equals(severityName) && (description == null || description.isEmpty() || "Editor highlight".equals(description))) {
                    continue;
                }

                if (info.getSeverity().compareTo(HighlightSeverity.INFORMATION) < 0) {
                    continue;
                }

                JsonObject h = new JsonObject();
                int line = document.getLineNumber(info.getStartOffset()) + 1;
                h.addProperty("line", line);
                h.addProperty("severity", severityName);
                h.addProperty("description", description != null ? description : "highlighted element");
                if (info.getToolTip() != null) {
                    h.addProperty("toolTip", info.getToolTip());
                }
                highlights.add(h);
            }
        } catch (Exception e) {
            LOG.warn("Failed to get rich highlight info: " + e.getMessage());
        }
        return highlights;
    }

    private JsonArray getInjectedLanguages(PsiFile psiFile, int offset, Project project) {
        JsonArray injected = new JsonArray();
        try {
            InjectedLanguageManager manager = InjectedLanguageManager.getInstance(project);
            Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
            if (document == null) { return injected; }

            int start = Math.max(0, offset - INJECTION_SEARCH_RANGE);
            int end = Math.min(psiFile.getTextLength(), offset + INJECTION_SEARCH_RANGE);

            manager.enumerateEx(psiFile, psiFile, false, (injectedFile, places) -> {
                boolean inRange = false;
                for (PsiLanguageInjectionHost.Shred shred : places) {
                    TextRange hostRange = shred.getHost().getTextRange();
                    if (hostRange.getStartOffset() < end && hostRange.getEndOffset() > start) {
                        inRange = true;
                        break;
                    }
                }

                if (inRange) {
                    JsonObject info = new JsonObject();
                    info.addProperty("language", injectedFile.getLanguage().getID());
                    info.addProperty("content", injectedFile.getText());

                    if (!places.isEmpty()) {
                        PsiElement host = places.get(0).getHost();
                        int line = document.getLineNumber(host.getTextRange().getStartOffset()) + 1;
                        info.addProperty("hostLine", line);
                        info.addProperty("hostLanguage", psiFile.getLanguage().getID());
                    }

                    injected.add(info);
                }
            });

        } catch (Exception e) {
            LOG.warn("Failed to get injected languages: " + e.getMessage());
        }
        return injected;
    }

    private JsonArray getSyntaxErrors(PsiFile psiFile) {
        JsonArray errors = new JsonArray();
        try {
            Document document = PsiDocumentManager.getInstance(psiFile.getProject()).getDocument(psiFile);
            if (document == null) { return errors; }

            Collection<PsiErrorElement> errorElements = PsiTreeUtil.findChildrenOfType(psiFile, PsiErrorElement.class);
            for (PsiErrorElement error : errorElements) {
                JsonObject err = new JsonObject();
                int line = document.getLineNumber(error.getTextRange().getStartOffset()) + 1;
                err.addProperty("line", line);
                err.addProperty("message", error.getErrorDescription());
                errors.add(err);
            }
        } catch (Exception e) {
            LOG.warn("Failed to get syntax errors: " + e.getMessage());
        }
        return errors;
    }

    private JsonArray getQuickFixes(Editor editor, PsiFile psiFile, Project project) {
        JsonArray quickFixes = new JsonArray();
        try {
            Document document = editor.getDocument();
            int cursorOffset = editor.getCaretModel().getOffset();

            MarkupModelEx markupModel = (MarkupModelEx) DocumentMarkupModel.forDocument(document, project, false);
            if (markupModel == null) { return quickFixes; }

            Set<String> addedFixes = new HashSet<>();

            for (RangeHighlighter highlighter : markupModel.getAllHighlighters()) {
                if (highlighter.getStartOffset() > cursorOffset || highlighter.getEndOffset() < cursorOffset) {
                    continue;
                }

                HighlightInfo info = HighlightInfo.fromRangeHighlighter(highlighter);
                if (info == null) { continue; }

                info.findRegisteredQuickFix((descriptor, range) -> {
                    if (descriptor != null && descriptor.getAction() != null) {
                        IntentionAction action = descriptor.getAction();
                        String fixKey = action.getText() + "|" + action.getFamilyName();

                        if (!addedFixes.contains(fixKey)) {
                            addedFixes.add(fixKey);
                            JsonObject fix = new JsonObject();
                            fix.addProperty("name", action.getText());
                            fix.addProperty("family", action.getFamilyName());
                            if (info.getDescription() != null) {
                                fix.addProperty("problem", info.getDescription());
                            }
                            quickFixes.add(fix);
                        }
                    }
                    return null;
                });
            }

            PsiElement elementAtCursor = psiFile.findElementAt(cursorOffset);
            if (elementAtCursor != null) {
                List<IntentionAction> availableIntentions = IntentionManager.getInstance().getAvailableIntentions();
                for (IntentionAction intention : availableIntentions) {
                    try {
                        if (intention.isAvailable(project, editor, psiFile)) {
                            String fixKey = intention.getText() + "|" + intention.getFamilyName();
                            if (!addedFixes.contains(fixKey)) {
                                addedFixes.add(fixKey);
                                JsonObject fix = new JsonObject();
                                fix.addProperty("name", intention.getText());
                                fix.addProperty("family", intention.getFamilyName());
                                fix.addProperty("type", "intention");
                                quickFixes.add(fix);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

        } catch (Exception e) {
            LOG.warn("Failed to get quick fixes: " + e.getMessage());
        }
        return quickFixes;
    }
}
