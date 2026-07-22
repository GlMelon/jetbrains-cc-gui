package com.github.claudecodegui.handler.context;

import com.google.gson.JsonObject;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/**
 * IntelliJ Extension Point for language-specific semantic context collection (A5).
 *
 * <p>Implementations collect contextual information from the current editor/PSI file
 * and append it to the target {@link JsonObject}. Registered via {@code plugin.xml}
 * {@code <extensionPoint>} and conditionally loaded through {@code java-features.xml}
 * / {@code python-features.xml}.</p>
 *
 * <p>Only depends on IntelliJ Platform base types (no Java/Python plugin dependency).</p>
 */
public interface SemanticContextProvider {

    ExtensionPointName<SemanticContextProvider> EP_NAME = ExtensionPointName.create(
            "com.github.idea-claude-code-gui.semanticContextProvider"
    );

    /**
     * Collect semantic context for the given editor and PSI file,
     * appending relevant data to {@code target}.
     *
     * @param target   the JSON object to populate (never null)
     * @param editor   the active editor
     * @param project  the current project
     * @param psiFile  the PSI file for the active editor
     * @param document the document for the active editor
     */
    void collectSemanticContext(
            @NotNull JsonObject target,
            @NotNull Editor editor,
            @NotNull Project project,
            @NotNull PsiFile psiFile,
            @NotNull Document document
    );
}
