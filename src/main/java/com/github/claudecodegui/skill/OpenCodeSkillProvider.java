package com.github.claudecodegui.skill;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * OpenCode provider.
 * <p>
 * OpenCode discovers skills from several compatible directories and toggles
 * enabled state via {@code permission.skill} patterns in {@code opencode.json}.
 * <ul>
 *   <li>{@code local} (project-level) — {@code {cwd}/.opencode/skills}</li>
 *   <li>{@code global} (user-level) — {@code ~/.config/opencode/skills}</li>
 *   <li>shared compatible trees — {@code .claude/skills} / {@code .agents/skills}
 *       (scanned the same way as Codex for cross-tool skill reuse)</li>
 * </ul>
 * A skill is disabled when its name matches a {@code "deny"} pattern under
 * {@code permission.skill} (exact name or glob, e.g. {@code internal-*}).
 * <p>
 * {@link #getAllSkills}/{@link #toggleSkill} are thin wrappers over the
 * package-private, test-injected helpers below. Per-skill scanning (directory
 * walk + frontmatter parsing) reuses {@link CodexSkillService#scanSkillsDirectory}
 * so SKILL.md semantics stay consistent across Codex/OpenCode.
 */
public final class OpenCodeSkillProvider implements UnifiedSkillService {

    private static final Logger LOG = Logger.getInstance(OpenCodeSkillProvider.class);

    /** A scan directory paired with the bucket it maps into ({@code global} or {@code local}). */
    record ScanDir(String path, String scope) {
    }

    @Override
    public ProviderType provider() {
        return ProviderType.OPENCODE;
    }

    @Override
    public JsonObject getAllSkills(String cwd) {
        List<ScanDir> dirs = resolveOpenCodeScanDirs(cwd, PlatformUtils.getHomeDirectory());
        return scanIntoBuckets(dirs, readDeniedPatterns(resolveOpenCodeConfigJson()));
    }

    @Override
    public SkillDocumentSchema skillDocumentSchema() {
        return SkillDocumentSchema.full();
    }

    @Override
    public SkillDocumentTarget resolveSkillDocument(SkillDocumentIdentity identity, String cwd)
            throws SkillDocumentAccessException {
        if (identity.skillPath() == null || identity.skillPath().isBlank()) {
            throw new SkillDocumentAccessException("OpenCode skillPath is required");
        }
        List<Path> roots = resolveValidBaseDirs(cwd, PlatformUtils.getHomeDirectory());
        return SkillDocumentPathPolicy.resolve(Path.of(identity.skillPath()), roots);
    }

    @Override
    public JsonObject importSkills(List<String> sourcePaths, String scope, String cwd) {
        return importSkillsInto(sourcePaths, scope, cwd, PlatformUtils.getHomeDirectory());
    }

    /**
     * 导入 skill 源目录到指定 scope 的 OpenCode skills 目录。
     * <p>目标目录:global→{@code {homeDir}/.config/opencode/skills},local→{@code {cwd}/.opencode/skills}
     * (与 {@link #resolveOpenCodeScanDirs} 扫描顺序首个命中一致,保持所有权清晰)。
     * 复用 {@link CodexSkillService} 安全栈:名称校验、路径包含检查、符号链接跳过的目录复制。
     *
     * @param homeDir home 目录(注入以便测试;生产取 {@link PlatformUtils#getHomeDirectory()})
     */
    static JsonObject importSkillsInto(List<String> sourcePaths, String scope, String cwd, String homeDir) {
        JsonObject result = new JsonObject();
        JsonArray imported = new JsonArray();
        JsonArray errors = new JsonArray();

        boolean isGlobal = "global".equalsIgnoreCase(scope);
        String targetDir;
        if (isGlobal) {
            targetDir = Paths.get(homeDir, ".config", "opencode", "skills").toString();
        } else {
            if (cwd == null || cwd.isEmpty()) {
                result.addProperty("success", false);
                result.addProperty("error", "No working directory for local scope");
                return result;
            }
            targetDir = Paths.get(cwd, ".opencode", "skills").toString();
        }

        File targetDirFile = new File(targetDir);
        if (!targetDirFile.exists() && !targetDirFile.mkdirs()) {
            result.addProperty("success", false);
            result.addProperty("error", "Cannot create directory: " + targetDir);
            return result;
        }

        for (String sourcePath : sourcePaths) {
            File source = new File(sourcePath);
            if (!source.exists()) {
                addImportError(errors, sourcePath, "Source path does not exist");
                continue;
            }
            String name = source.getName();
            // OpenCode skills 必须是目录(对称 Codex);plain file 拒绝
            if (!source.isDirectory()) {
                addImportError(errors, sourcePath, "OpenCode skill must be a directory containing SKILL.md");
                continue;
            }
            if (!CodexSkillService.isSafeSkillName(name)) {
                addImportError(errors, sourcePath, "Invalid skill name: " + name);
                continue;
            }
            File targetPath = new File(targetDir, name);
            if (!CodexSkillService.isPathSafe(targetPath.toPath(), Path.of(targetDir))) {
                addImportError(errors, sourcePath, "Target path escapes skills directory");
                continue;
            }
            if (targetPath.exists()) {
                addImportError(errors, sourcePath, "Skill already exists: " + name);
                continue;
            }
            try {
                CodexSkillService.copyDirectory(source.toPath(), targetPath.toPath());
                JsonObject skill = new JsonObject();
                skill.addProperty("id", scope + ":" + CodexSkillService.normalizePath(targetPath.getAbsolutePath()));
                skill.addProperty("name", name);
                skill.addProperty("scope", scope);
                skill.addProperty("path", targetPath.getAbsolutePath());
                imported.add(skill);
                LOG.info("[OpenCodeSkills] Imported skill: " + name + " to " + scope);
            } catch (IOException e) {
                addImportError(errors, sourcePath, "Copy failed: " + e.getMessage());
            }
        }

        result.addProperty("success", imported.size() > 0);
        result.addProperty("count", imported.size());
        result.add("imported", imported);
        if (errors.size() > 0) {
            result.add("errors", errors);
        }
        return result;
    }

    private static void addImportError(JsonArray errors, String path, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("path", path);
        err.addProperty("error", message);
        errors.add(err);
    }

    @Override
    public JsonObject deleteSkill(SkillId id, boolean enabled, String cwd) {
        return deleteSkillFrom(id, enabled, cwd, PlatformUtils.getHomeDirectory());
    }

    /**
     * 删除 OpenCode skill 目录,并清除 opencode.json 中该 skill 名的 permission.skill deny 条目。
     * <p>合法基目录取 {@link #resolveValidBaseDirs}(全局3+本地3,对齐 {@link #resolveOpenCodeScanDirs});
     * 符号链接安全删除(只删链接不删目标);删除后调 {@link #setSkillEnabled}(enable=true) 清 deny。
     *
     * @param homeDir home 目录(注入以便测试;生产取 {@link PlatformUtils#getHomeDirectory()})
     */
    static JsonObject deleteSkillFrom(SkillId id, boolean enabled, String cwd, String homeDir) {
        JsonObject result = new JsonObject();
        String name = id.name();
        String scope = id.scope();
        String skillPath = id.skillPath();

        // 1. 解析 skillDir:优先从 skillPath(SKILL.md 父目录),否则 fallback 到 scope+name
        File skillDir;
        if (skillPath != null && !skillPath.isEmpty()) {
            Path normalizedSkillPath = Paths.get(skillPath).toAbsolutePath().normalize();
            String fileName = normalizedSkillPath.getFileName().toString();
            if (!"SKILL.md".equals(fileName) && !"skill.md".equals(fileName)) {
                result.addProperty("success", false);
                result.addProperty("error", "Skill path must point to a SKILL.md file");
                return result;
            }
            File parentDir = normalizedSkillPath.getParent().toFile();
            if (name != null && !name.isEmpty() && !parentDir.getName().equals(name)) {
                result.addProperty("success", false);
                result.addProperty("error", "Skill path does not match skill name");
                return result;
            }
            skillDir = parentDir;
        } else {
            if (!CodexSkillService.isSafeSkillName(name)) {
                result.addProperty("success", false);
                result.addProperty("error", "Invalid skill name: " + name);
                return result;
            }
            String baseDir;
            if ("global".equalsIgnoreCase(scope)) {
                baseDir = Paths.get(homeDir, ".config", "opencode", "skills").toString();
            } else {
                if (cwd == null || cwd.isEmpty()) {
                    result.addProperty("success", false);
                    result.addProperty("error", "Working directory is required for local scope deletion");
                    return result;
                }
                baseDir = Paths.get(cwd, ".opencode", "skills").toString();
            }
            skillDir = new File(baseDir, name);
        }

        if (!skillDir.exists()) {
            result.addProperty("success", false);
            result.addProperty("error", "Skill directory does not exist");
            return result;
        }

        // 2. 合法基目录包含检查(防路径遍历/逃逸)
        List<Path> validBaseDirs = resolveValidBaseDirs(cwd, homeDir);
        Path normalizedSkillDir = skillDir.toPath().toAbsolutePath().normalize();
        boolean isInsideValidDir = validBaseDirs.stream()
                .anyMatch(base -> CodexSkillService.isPathSafe(normalizedSkillDir, base));
        if (!isInsideValidDir) {
            result.addProperty("success", false);
            result.addProperty("error", "Skill directory is not inside a valid skills directory");
            LOG.warn("[OpenCodeSkills] Blocked deletion of path outside skills directories: " + normalizedSkillDir);
            return result;
        }

        // 3. 删除(符号链接安全:只删链接)
        try {
            if (Files.isSymbolicLink(skillDir.toPath())) {
                Files.delete(skillDir.toPath());
                LOG.info("[OpenCodeSkills] Deleted symbolic link skill: " + skillDir);
            } else {
                CodexSkillService.deleteDirectory(skillDir.toPath());
                LOG.info("[OpenCodeSkills] Deleted skill directory: " + skillDir);
            }
        } catch (IOException e) {
            result.addProperty("success", false);
            result.addProperty("error", "Delete failed: " + e.getMessage());
            return result;
        }

        // 4. 清除 permission.skill deny 条目(若该 skill 名曾被 deny)
        if (name != null && !name.isEmpty()) {
            setSkillEnabled(resolveOpenCodeConfigJson(homeDir), name, true);
        }

        result.addProperty("success", true);
        return result;
    }

    /**
     * OpenCode 删除操作的合法 skills 基目录(全局3 + 本地3,对齐 {@link #resolveOpenCodeScanDirs})。
     * 不要求目录存在——仅用于 isPathSafe 的 startsWith 包含判断。
     */
    static List<Path> resolveValidBaseDirs(String cwd, String homeDir) {
        List<Path> dirs = new ArrayList<>();
        if (homeDir != null && !homeDir.isEmpty()) {
            dirs.add(Paths.get(homeDir, ".config", "opencode", "skills"));
            dirs.add(Paths.get(homeDir, ".claude", "skills"));
            dirs.add(Paths.get(homeDir, ".agents", "skills"));
        }
        if (cwd != null && !cwd.isEmpty()) {
            dirs.add(Paths.get(cwd, ".opencode", "skills"));
            dirs.add(Paths.get(cwd, ".claude", "skills"));
            dirs.add(Paths.get(cwd, ".agents", "skills"));
        }
        return dirs;
    }

    @Override
    public JsonObject toggleSkill(SkillId id, boolean currentEnabled, String cwd) {
        return setSkillEnabled(resolveOpenCodeConfigJson(), id.name(), !currentEnabled);
    }

    // ── package-private, testable helpers ──

    /** Resolves the {@code ~/.config/opencode/opencode.json} config path from the real home dir. */
    static Path resolveOpenCodeConfigJson() {
        return resolveOpenCodeConfigJson(PlatformUtils.getHomeDirectory());
    }

    /** Resolves the opencode.json path from an injected home dir (testable). */
    static Path resolveOpenCodeConfigJson(String homeDir) {
        return Paths.get(homeDir, ".config", "opencode", "opencode.json");
    }

    /**
     * Resolves the directories to scan for OpenCode skills.
     * <ul>
     *   <li>{@code local} (project-level): {@code {cwd}/.opencode/skills} plus the shared
     *       {@code {cwd}/.claude/skills} and {@code {cwd}/.agents/skills} trees.</li>
     *   <li>{@code global} (user-level): {@code {homeDir}/.config/opencode/skills} plus the
     *       shared {@code {homeDir}/.claude/skills} and {@code {homeDir}/.agents/skills} trees.</li>
     * </ul>
     *
     * @param cwd     current working directory
     * @param homeDir home directory (injected for testability)
     */
    static List<ScanDir> resolveOpenCodeScanDirs(String cwd, String homeDir) {
        List<ScanDir> dirs = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        if (cwd != null && !cwd.isEmpty()) {
            addIfDirectory(dirs, seen, Paths.get(cwd, ".opencode", "skills").toString(), "local");
            addIfDirectory(dirs, seen, Paths.get(cwd, ".claude", "skills").toString(), "local");
            addIfDirectory(dirs, seen, Paths.get(cwd, ".agents", "skills").toString(), "local");
        }

        if (homeDir != null && !homeDir.isEmpty()) {
            addIfDirectory(dirs, seen, Paths.get(homeDir, ".config", "opencode", "skills").toString(), "global");
            addIfDirectory(dirs, seen, Paths.get(homeDir, ".claude", "skills").toString(), "global");
            addIfDirectory(dirs, seen, Paths.get(homeDir, ".agents", "skills").toString(), "global");
        }

        return dirs;
    }

    private static void addIfDirectory(List<ScanDir> dirs, Set<String> seen, String path, String scope) {
        Path p = Paths.get(path);
        if (Files.isDirectory(p) && seen.add(p.toAbsolutePath().normalize().toString())) {
            dirs.add(new ScanDir(p.toString(), scope));
        }
    }

    /**
     * Scans each directory, merges into {@code {global, local}} buckets, and marks any
     * skill whose name matches a denied glob pattern as disabled.
     */
    static JsonObject scanIntoBuckets(List<ScanDir> dirs, Set<String> deniedPatterns) {
        JsonObject global = new JsonObject();
        JsonObject local = new JsonObject();
        for (ScanDir d : dirs) {
            JsonObject scanned = CodexSkillService.scanSkillsDirectory(d.path(), d.scope());
            for (String key : scanned.keySet()) {
                JsonObject skill = scanned.getAsJsonObject(key);
                String name = skill.has("name") ? skill.get("name").getAsString() : key;
                if (matchesAnyDenyPattern(name, deniedPatterns)) {
                    skill.addProperty("enabled", false);
                }
                JsonObject bucket = "global".equals(d.scope()) ? global : local;
                bucket.add(key, skill);
            }
        }
        JsonObject result = new JsonObject();
        result.add("global", global);
        result.add("local", local);
        return result;
    }

    /**
     * Returns true if {@code name} exactly equals a denied pattern or matches one as a glob
     * ({@code *} → {@code .*}, {@code ?} → {@code .}). Patterns use OpenCode skill-name
     * characters so a naive translation is sufficient.
     */
    private static boolean matchesAnyDenyPattern(String name, Set<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (String pattern : patterns) {
            if (pattern.equals(name)) {
                return true;
            }
            String regex = pattern.replace("*", ".*").replace("?", ".");
            if (name.matches(regex)) {
                return true;
            }
        }
        return false;
    }

    /** Reads denied skill patterns (value {@code "deny"}) from {@code permission.skill}. */
    static Set<String> readDeniedPatterns(Path opencodeJson) {
        Set<String> denied = new HashSet<>();
        JsonObject skill = readPermissionSkill(opencodeJson);
        if (skill == null) {
            return denied;
        }
        for (String key : skill.keySet()) {
            if (skill.has(key) && skill.get(key).isJsonPrimitive()
                    && "deny".equals(skill.get(key).getAsString())) {
                denied.add(key);
            }
        }
        return denied;
    }

    /**
     * Enables (removes the deny entry) or disables (writes {@code "deny"}) a skill name
     * in {@code permission.skill}, preserving all other entries.
     */
    static JsonObject setSkillEnabled(Path opencodeJson, String skillName, boolean enable) {
        JsonObject result = new JsonObject();
        try {
            JsonObject root = readRootOrCreate(opencodeJson);
            JsonObject permission = asObjectOrCreate(root, "permission");
            JsonObject skill = asObjectOrCreate(permission, "skill");

            if (enable) {
                skill.remove(skillName);
            } else {
                skill.addProperty(skillName, "deny");
            }

            permission.add("skill", skill);
            root.add("permission", permission);

            if (opencodeJson.getParent() != null) {
                Files.createDirectories(opencodeJson.getParent());
            }
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);
            Files.writeString(opencodeJson, json);

            result.addProperty("success", true);
            result.addProperty("enabled", enable);
        } catch (Exception e) {
            LOG.error("[OpenCodeSkills] Failed to update permission.skill for '" + skillName + "': " + e.getMessage(), e);
            result.addProperty("success", false);
            result.addProperty("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
        return result;
    }

    private static JsonObject readPermissionSkill(Path opencodeJson) {
        if (!Files.isRegularFile(opencodeJson)) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(opencodeJson)).getAsJsonObject();
            JsonObject permission = asObjectOrNull(root, "permission");
            if (permission == null) {
                return null;
            }
            return asObjectOrNull(permission, "skill");
        } catch (Exception e) {
            LOG.warn("[OpenCodeSkills] Failed to read permission.skill from " + opencodeJson + ": " + e.getMessage());
            return null;
        }
    }

    private static JsonObject readRootOrCreate(Path opencodeJson) throws Exception {
        if (Files.isRegularFile(opencodeJson)) {
            return JsonParser.parseString(Files.readString(opencodeJson)).getAsJsonObject();
        }
        return new JsonObject();
    }

    private static JsonObject asObjectOrNull(JsonObject parent, String key) {
        if (parent != null && parent.has(key) && parent.get(key).isJsonObject()) {
            return parent.getAsJsonObject(key);
        }
        return null;
    }

    private static JsonObject asObjectOrCreate(JsonObject parent, String key) {
        JsonObject child = asObjectOrNull(parent, key);
        return child != null ? child : new JsonObject();
    }
}
