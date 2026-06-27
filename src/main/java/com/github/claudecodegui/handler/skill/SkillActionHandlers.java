package com.github.claudecodegui.handler.skill;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.skill.SkillId;
import com.github.claudecodegui.skill.UnifiedSkillServiceRegistry;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Container for skill action handlers (B2 迁移).
 * Holds shared logic for skill CRUD + toggle operations.
 * <p>
 * Provider dispatch is centralized through {@link UnifiedSkillServiceRegistry}; this handler
 * no longer branches on the provider for routing. The only retained provider-specific checks
 * are non-routing concerns: the Codex FileChooser mode (folders only) and the per-provider
 * fallback JSON shape (Codex uses {@code user}/{@code repo}, Claude/OpenCode use {@code global}/{@code local}).
 */
public class SkillActionHandlers {

    private static final Logger LOG = Logger.getInstance(SkillActionHandlers.class);
    private static final Gson GSON = GsonHolder.GSON;

    private final HandlerContext context;

    public SkillActionHandlers(HandlerContext context) {
        this.context = context;
    }

    // ── dispatch helpers ──

    private void dispatchEvent(String event, String data) {
        context.dispatchEvent(event, data);
    }

    private String escapeJs(String s) {
        return context.escapeJs(s);
    }

    // ── business logic ──

    public void handleGetAllSkills() {
        String provider = context.getCurrentProvider();
        try {
            String workspaceRoot = context.getProject().getBasePath();
            JsonObject skills = UnifiedSkillServiceRegistry.forProvider(provider).getAllSkills(workspaceRoot);
            String skillsJson = GSON.toJson(skills);
            ApplicationManager.getApplication().invokeLater(() -> {
                dispatchEvent(DownstreamEvent.SKILL_LIST.value(), escapeJs(skillsJson));
            });
        } catch (Exception e) {
            LOG.error("[SkillHandler] Failed to get all skills: " + e.getMessage(), e);
            String fallbackJson = CommonConstants.PROVIDER_CODEX.equalsIgnoreCase(provider)
                    ? "{\"user\":{},\"repo\":{}}" : "{\"global\":{},\"local\":{}}";
            ApplicationManager.getApplication().invokeLater(() -> {
                dispatchEvent(DownstreamEvent.SKILL_LIST.value(), escapeJs(fallbackJson));
            });
        }
    }

    public void handleImportSkill(String content) {
        try {
            JsonObject json = GSON.fromJson(content, JsonObject.class);
            String scope = json.has("scope") ? json.get("scope").getAsString() : "global";
            // Codex FileChooser is folders-only; the others allow files or folders. Not a routing concern.
            boolean isCodex = CommonConstants.PROVIDER_CODEX.equalsIgnoreCase(context.getCurrentProvider());
            ApplicationManager.getApplication().invokeLater(() -> {
                FileChooserDescriptor descriptor;
                if (isCodex) {
                    descriptor = new FileChooserDescriptor(false, true, false, false, false, true);
                    descriptor.setTitle("选择 Codex Skill 文件夹");
                } else {
                    descriptor = new FileChooserDescriptor(true, true, false, false, false, true);
                    descriptor.setTitle("选择 Skill 文件或文件夹");
                }
                VirtualFile initialDir = null;
                String projectPath = context.getProject().getBasePath();
                if (projectPath != null) {
                    initialDir = LocalFileSystem.getInstance().findFileByPath(projectPath);
                }
                VirtualFile[] selectedFiles = FileChooser.chooseFiles(descriptor, context.getProject(), initialDir);
                if (selectedFiles.length > 0) {
                    List<String> paths = new ArrayList<>();
                    for (VirtualFile vf : selectedFiles) {
                        paths.add(vf.getPath());
                    }
                    String provider = context.getCurrentProvider();
                    CompletableFuture.runAsync(() -> {
                        try {
                            String workspaceRoot = context.getProject().getBasePath();
                            JsonObject importResult = UnifiedSkillServiceRegistry.forProvider(provider)
                                    .importSkills(paths, scope, workspaceRoot);
                            String resultJson = GSON.toJson(importResult);
                            ApplicationManager.getApplication().invokeLater(() -> {
                                dispatchEvent(DownstreamEvent.SKILL_IMPORT_RESULT.value(), escapeJs(resultJson));
                            });
                        } catch (Exception e) {
                            LOG.error("[SkillHandler] Import skill failed: " + e.getMessage(), e);
                            JsonObject errorResult = new JsonObject();
                            errorResult.addProperty("success", false);
                            errorResult.addProperty("error", e.getMessage());
                            ApplicationManager.getApplication().invokeLater(() -> {
                                dispatchEvent(DownstreamEvent.SKILL_IMPORT_RESULT.value(), escapeJs(GSON.toJson(errorResult)));
                            });
                        }
                    }, AppExecutorUtil.getAppExecutorService());
                }
            });
        } catch (Exception e) {
            LOG.error("[SkillHandler] Failed to handle import skill: " + e.getMessage(), e);
        }
    }

    public void handleDeleteSkill(String content) {
        try {
            JsonObject json = GSON.fromJson(content, JsonObject.class);
            String skillName = json.get("name").getAsString();
            String scope = json.has("scope") ? json.get("scope").getAsString() : "global";
            boolean enabled = json.has("enabled") ? json.get("enabled").getAsBoolean() : true;
            String skillPath = json.has("skillPath") ? json.get("skillPath").getAsString() : null;
            String workspaceRoot = context.getProject().getBasePath();
            String provider = context.getCurrentProvider();
            CompletableFuture.runAsync(() -> {
                try {
                    JsonObject result;
                    if (skillPath != null && !isPathClean(skillPath)) {
                        result = new JsonObject();
                        result.addProperty("success", false);
                        result.addProperty("error", "Invalid skill path");
                    } else {
                        SkillId id = new SkillId(scope, skillName, skillPath);
                        result = UnifiedSkillServiceRegistry.forProvider(provider)
                                .deleteSkill(id, enabled, workspaceRoot);
                    }
                    String resultJson = GSON.toJson(result);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        dispatchEvent(DownstreamEvent.SKILL_DELETE_RESULT.value(), escapeJs(resultJson));
                    });
                } catch (Exception e) {
                    LOG.error("[SkillHandler] Delete skill failed: " + e.getMessage(), e);
                    JsonObject errorResult = new JsonObject();
                    errorResult.addProperty("success", false);
                    errorResult.addProperty("error", e.getMessage());
                    ApplicationManager.getApplication().invokeLater(() -> {
                        dispatchEvent(DownstreamEvent.SKILL_DELETE_RESULT.value(), escapeJs(GSON.toJson(errorResult)));
                    });
                }
            }, AppExecutorUtil.getAppExecutorService());
        } catch (Exception e) {
            LOG.error("[SkillHandler] Failed to delete skill: " + e.getMessage(), e);
            JsonObject errorResult = new JsonObject();
            errorResult.addProperty("success", false);
            errorResult.addProperty("error", e.getMessage());
            ApplicationManager.getApplication().invokeLater(() -> {
                dispatchEvent(DownstreamEvent.SKILL_DELETE_RESULT.value(), escapeJs(GSON.toJson(errorResult)));
            });
        }
    }

    public void handleToggleSkill(String content) {
        try {
            JsonObject json = GSON.fromJson(content, JsonObject.class);
            String skillName = json.get("name").getAsString();
            String scope = json.has("scope") ? json.get("scope").getAsString() : "global";
            boolean currentEnabled = json.has("enabled") ? json.get("enabled").getAsBoolean() : true;
            String skillPath = json.has("skillPath") ? json.get("skillPath").getAsString() : null;
            String workspaceRoot = context.getProject().getBasePath();
            String provider = context.getCurrentProvider();
            CompletableFuture.runAsync(() -> {
                try {
                    JsonObject result;
                    if (skillPath != null && !isPathClean(skillPath)) {
                        result = new JsonObject();
                        result.addProperty("success", false);
                        result.addProperty("error", "Invalid skill path");
                    } else {
                        SkillId id = new SkillId(scope, skillName, skillPath);
                        result = UnifiedSkillServiceRegistry.forProvider(provider)
                                .toggleSkill(id, currentEnabled, workspaceRoot);
                    }
                    String resultJson = GSON.toJson(result);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        dispatchEvent(DownstreamEvent.SKILL_TOGGLE_RESULT.value(), escapeJs(resultJson));
                    });
                } catch (Exception e) {
                    LOG.error("[SkillHandler] Toggle skill failed: " + e.getMessage(), e);
                    JsonObject errorResult = new JsonObject();
                    errorResult.addProperty("success", false);
                    errorResult.addProperty("error", e.getMessage());
                    ApplicationManager.getApplication().invokeLater(() -> {
                        dispatchEvent(DownstreamEvent.SKILL_TOGGLE_RESULT.value(), escapeJs(GSON.toJson(errorResult)));
                    });
                }
            }, AppExecutorUtil.getAppExecutorService());
        } catch (Exception e) {
            LOG.error("[SkillHandler] Failed to toggle skill: " + e.getMessage(), e);
            JsonObject errorResult = new JsonObject();
            errorResult.addProperty("success", false);
            errorResult.addProperty("error", e.getMessage());
            ApplicationManager.getApplication().invokeLater(() -> {
                dispatchEvent(DownstreamEvent.SKILL_TOGGLE_RESULT.value(), escapeJs(GSON.toJson(errorResult)));
            });
        }
    }

    public void handleOpenSkill(String content) {
        try {
            JsonObject json = GSON.fromJson(content, JsonObject.class);
            String skillPath = json.get("path").getAsString();
            if (skillPath.contains("..") || skillPath.contains("\0")) {
                LOG.warn("[SkillHandler] Rejected open request with suspicious path: " + skillPath);
                return;
            }
            if (!isInsideSkillsDirectory(skillPath)) {
                LOG.warn("[SkillHandler] Rejected open request for path outside skills directories");
                return;
            }
            File skillFile = new File(skillPath);
            String targetPath = skillPath;
            if (skillFile.isDirectory()) {
                File skillMd = new File(skillFile, "skill.md");
                if (!skillMd.exists()) {
                    skillMd = new File(skillFile, "SKILL.md");
                }
                if (skillMd.exists()) {
                    targetPath = skillMd.getAbsolutePath();
                }
            }
            final String fileToOpen = targetPath;
            ReadAction
                .nonBlocking(() -> LocalFileSystem.getInstance().findFileByPath(fileToOpen))
                .finishOnUiThread(com.intellij.openapi.application.ModalityState.defaultModalityState(), virtualFile -> {
                    if (virtualFile != null) {
                        FileEditorManager.getInstance(context.getProject()).openFile(virtualFile, true);
                    } else {
                        LOG.error("[SkillHandler] Cannot find file: " + fileToOpen);
                    }
                })
                .submit(AppExecutorUtil.getAppExecutorService());
        } catch (Exception e) {
            LOG.error("[SkillHandler] Failed to open skill: " + e.getMessage(), e);
        }
    }

    // ── helpers ──

    private static boolean isPathClean(String path) {
        if (path == null || path.isEmpty()) { return false; }
        if (path.contains("\0")) { return false; }
        try {
            Path original = Paths.get(path).toAbsolutePath();
            Path normalized = original.normalize();
            return original.toString().equals(normalized.toString());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isInsideSkillsDirectory(String path) {
        try {
            Path normalized = Paths.get(path).toAbsolutePath().normalize();
            String userHome = com.github.claudecodegui.util.PlatformUtils.getHomeDirectory();
            String projectBase = context.getProject().getBasePath();
            List<Path> validBases = new ArrayList<>();
            validBases.add(Paths.get(userHome, ".claude", "skills"));
            validBases.add(Paths.get(userHome, ".claude", "commands"));
            validBases.add(Paths.get(userHome, ".codemoss", "skills"));
            validBases.add(Paths.get(userHome, ".agents", "skills"));
            validBases.add(Paths.get(userHome, ".codex", "skills"));
            if (projectBase != null) {
                validBases.add(Paths.get(projectBase, ".claude", "skills"));
                validBases.add(Paths.get(projectBase, ".claude", "commands"));
                validBases.add(Paths.get(projectBase, ".agents", "skills"));
            }
            for (Path base : validBases) {
                if (normalized.startsWith(base.toAbsolutePath().normalize())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
