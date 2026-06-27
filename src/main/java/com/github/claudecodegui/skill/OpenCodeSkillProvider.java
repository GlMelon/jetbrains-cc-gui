package com.github.claudecodegui.skill;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

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
    public JsonObject importSkills(List<String> sourcePaths, String scope, String cwd) {
        // TODO: copy sources into {cwd}/.opencode/skills or ~/.config/opencode/skills by scope.
        JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("error", "OpenCode skill import is not yet implemented");
        return result;
    }

    @Override
    public JsonObject deleteSkill(SkillId id, boolean enabled, String cwd) {
        // TODO: delete skill directory + clear any permission.skill deny entry for its name.
        JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("error", "OpenCode skill delete is not yet implemented");
        return result;
    }

    @Override
    public JsonObject toggleSkill(SkillId id, boolean currentEnabled, String cwd) {
        return setSkillEnabled(resolveOpenCodeConfigJson(), id.name(), !currentEnabled);
    }

    // ── package-private, testable helpers ──

    /** Resolves the {@code ~/.config/opencode/opencode.json} config path from the real home dir. */
    static Path resolveOpenCodeConfigJson() {
        return Paths.get(PlatformUtils.getHomeDirectory(), ".config", "opencode", "opencode.json");
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
