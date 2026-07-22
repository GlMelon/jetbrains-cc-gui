package com.github.claudecodegui.handler.file;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/** Optional language-specific class navigation capability. */
public interface ClassNavigationProvider {

    ExtensionPointName<ClassNavigationProvider> EP_NAME = ExtensionPointName.create(
            "com.github.idea-claude-code-gui.classNavigationProvider"
    );

    boolean navigate(
            @NotNull Project project,
            @NotNull String qualifiedName,
            @NotNull Consumer<String> onFailure
    );
}
