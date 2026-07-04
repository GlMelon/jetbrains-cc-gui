package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.intellij.openapi.project.Project;

/**
 * Resolves the project path used by history providers.
 * <p>
 * History providers are invoked through the Node-side bridge when Node is a WSL executable,
 * so Windows project paths must be converted to their Linux form consistently for load,
 * refresh, and delete operations.
 */
final class HistoryProjectPathResolver {

    String resolve(HandlerContext context) {
        if (context == null) {
            return null;
        }
        Project project = context.getProject();
        if (project == null) {
            return null;
        }
        return resolveProjectPath(project.getBasePath(), NodeDetector.getInstance().getCachedNodePath());
    }

    static String resolveProjectPath(String rawPath, String nodePath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        return NodeDetector.isWslPath(nodePath) ? NodeDetector.convertToWslPath(rawPath) : rawPath;
    }
}
