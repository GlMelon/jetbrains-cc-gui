package com.github.claudecodegui.skill;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link OpenCodeSkillProvider} package-private scan/toggle logic.
 * Tests drive the provider-internal helpers (directory resolution, bucketed scan,
 * opencode.json permission parsing/writing). The public {@code getAllSkills}/
 * {@code toggleSkill} are thin wrappers over these and are not unit-tested directly
 * (they depend on the real home dir).
 */
public class OpenCodeSkillProviderTest {

    private static Path writeSkillMd(Path skillsDir, String name, String description) throws Exception {
        Path skillDir = skillsDir.resolve(name);
        Files.createDirectories(skillDir);
        String content = "---\nname: " + name + "\ndescription: " + description + "\n---\nbody";
        Files.write(skillDir.resolve("SKILL.md"), content.getBytes(StandardCharsets.UTF_8));
        return skillDir;
    }

    private static String norm(String p) {
        return Path.of(p).toAbsolutePath().normalize().toString();
    }

    @Test
    public void resolveScanDirsIncludesProjectLocalAndUserGlobal() throws Exception {
        Path tmp = Files.createTempDirectory("oc-resolve");
        Path cwd = tmp.resolve("proj");
        Path local = cwd.resolve(".opencode").resolve("skills");
        Files.createDirectories(local);
        Path home = tmp.resolve("home");
        Path global = home.resolve(".config").resolve("opencode").resolve("skills");
        Files.createDirectories(global);

        List<OpenCodeSkillProvider.ScanDir> dirs =
                OpenCodeSkillProvider.resolveOpenCodeScanDirs(cwd.toString(), home.toString());

        assertTrue("project local dir present",
                dirs.stream().anyMatch(d -> norm(d.path()).equals(norm(local.toString())) && "local".equals(d.scope())));
        assertTrue("user global dir present",
                dirs.stream().anyMatch(d -> norm(d.path()).equals(norm(global.toString())) && "global".equals(d.scope())));
    }

    @Test
    public void scanIntoBucketsReturnsLocalSkillEnabledByDefault() throws Exception {
        Path tmp = Files.createTempDirectory("oc-scan");
        Path localDir = tmp.resolve(".opencode").resolve("skills");
        writeSkillMd(localDir, "foo", "a skill");

        JsonObject result = OpenCodeSkillProvider.scanIntoBuckets(
                List.of(new OpenCodeSkillProvider.ScanDir(localDir.toString(), "local")),
                Set.of());

        assertTrue("global bucket present", result.has("global"));
        assertTrue("local bucket has one skill",
                result.has("local") && result.getAsJsonObject("local").size() == 1);
        JsonObject skill = result.getAsJsonObject("local").entrySet().iterator().next().getValue().getAsJsonObject();
        assertEquals("foo", skill.get("name").getAsString());
        assertTrue("enabled by default", skill.get("enabled").getAsBoolean());
    }

    @Test
    public void scanIntoBucketsMarksDeniedSkillDisabled() throws Exception {
        Path tmp = Files.createTempDirectory("oc-scan-deny");
        Path localDir = tmp.resolve(".opencode").resolve("skills");
        writeSkillMd(localDir, "foo", "a skill");

        JsonObject result = OpenCodeSkillProvider.scanIntoBuckets(
                List.of(new OpenCodeSkillProvider.ScanDir(localDir.toString(), "local")),
                Set.of("foo"));

        JsonObject skill = result.getAsJsonObject("local").entrySet().iterator().next().getValue().getAsJsonObject();
        assertFalse(skill.get("enabled").getAsBoolean());
    }

    @Test
    public void scanIntoBucketsMatchesGlobDenyPattern() throws Exception {
        Path tmp = Files.createTempDirectory("oc-scan-glob");
        Path localDir = tmp.resolve(".opencode").resolve("skills");
        writeSkillMd(localDir, "internal-secret", "sys skill");

        JsonObject result = OpenCodeSkillProvider.scanIntoBuckets(
                List.of(new OpenCodeSkillProvider.ScanDir(localDir.toString(), "local")),
                Set.of("internal-*"));

        JsonObject skill = result.getAsJsonObject("local").entrySet().iterator().next().getValue().getAsJsonObject();
        assertFalse("glob pattern internal-* should disable internal-secret", skill.get("enabled").getAsBoolean());
    }

    @Test
    public void readDeniedPatternsParsesDenyEntries() throws Exception {
        Path tmp = Files.createTempDirectory("oc-read");
        Path json = tmp.resolve("opencode.json");
        Files.writeString(json, "{\"permission\":{\"skill\":{\"foo\":\"deny\",\"bar\":\"allow\",\"baz\":\"ask\"}}}");

        Set<String> denied = OpenCodeSkillProvider.readDeniedPatterns(json);

        assertTrue(denied.contains("foo"));
        assertFalse("allow entries excluded", denied.contains("bar"));
        assertFalse("ask entries excluded", denied.contains("baz"));
    }

    @Test
    public void readDeniedPatternsEmptyWhenFileMissing() throws Exception {
        Path tmp = Files.createTempDirectory("oc-read-missing");
        Set<String> denied = OpenCodeSkillProvider.readDeniedPatterns(tmp.resolve("opencode.json"));
        assertTrue(denied.isEmpty());
    }

    @Test
    public void setSkillEnabledFalseWritesDenyEntry() throws Exception {
        Path tmp = Files.createTempDirectory("oc-disable");
        Path json = tmp.resolve("opencode.json");
        Files.writeString(json, "{\"permission\":{\"skill\":{\"other\":\"deny\"}}}");

        OpenCodeSkillProvider.setSkillEnabled(json, "foo", false);

        JsonObject parsed = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
        JsonObject skill = parsed.getAsJsonObject("permission").getAsJsonObject("skill");
        assertEquals("deny", skill.get("foo").getAsString());
        assertEquals("deny", skill.get("other").getAsString()); // preserved
    }

    @Test
    public void setSkillEnabledTrueRemovesDenyEntry() throws Exception {
        Path tmp = Files.createTempDirectory("oc-enable");
        Path json = tmp.resolve("opencode.json");
        Files.writeString(json, "{\"permission\":{\"skill\":{\"foo\":\"deny\",\"other\":\"deny\"}}}");

        OpenCodeSkillProvider.setSkillEnabled(json, "foo", true);

        JsonObject parsed = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
        JsonObject skill = parsed.getAsJsonObject("permission").getAsJsonObject("skill");
        assertFalse("foo deny removed", skill.has("foo"));
        assertTrue("other preserved", skill.has("other"));
    }

    // ── importSkills ──

    @Test
    public void importLocalScopeCopiesSkillToProjectDir() throws Exception {
        Path tmp = Files.createTempDirectory("oc-import-local");
        Path cwd = tmp.resolve("proj");
        Path src = writeSkillMd(tmp.resolve("src"), "foo", "a skill");

        JsonObject result = OpenCodeSkillProvider.importSkillsInto(
                List.of(src.toString()), "local", cwd.toString(), tmp.resolve("home").toString());

        assertTrue("import succeeds", result.get("success").getAsBoolean());
        Path target = cwd.resolve(".opencode").resolve("skills").resolve("foo");
        assertTrue("skill copied to project local dir", Files.exists(target.resolve("SKILL.md")));
        assertEquals("one imported", 1, result.getAsJsonArray("imported").size());
    }

    @Test
    public void importGlobalScopeCopiesToConfigDir() throws Exception {
        Path tmp = Files.createTempDirectory("oc-import-global");
        Path home = tmp.resolve("home");
        Path src = writeSkillMd(tmp.resolve("src"), "bar", "global skill");

        JsonObject result = OpenCodeSkillProvider.importSkillsInto(
                List.of(src.toString()), "global", null, home.toString());

        assertTrue(result.get("success").getAsBoolean());
        Path target = home.resolve(".config").resolve("opencode").resolve("skills").resolve("bar");
        assertTrue("skill copied to user global dir", Files.exists(target.resolve("SKILL.md")));
    }

    @Test
    public void importRejectsPlainFileSource() throws Exception {
        Path tmp = Files.createTempDirectory("oc-import-file");
        Path file = tmp.resolve("notadir.txt");
        Files.write(file, "hello".getBytes(StandardCharsets.UTF_8));

        JsonObject result = OpenCodeSkillProvider.importSkillsInto(
                List.of(file.toString()), "global", null, tmp.resolve("home").toString());

        assertFalse("plain file rejected", result.get("success").getAsBoolean());
        assertTrue("error recorded", result.has("errors"));
    }

    @Test
    public void importRejectsUnsafeName() throws Exception {
        Path tmp = Files.createTempDirectory("oc-import-unsafe");
        Path evil = tmp.resolve("ev..il");
        Files.createDirectories(evil);

        JsonObject result = OpenCodeSkillProvider.importSkillsInto(
                List.of(evil.toString()), "global", null, tmp.resolve("home").toString());

        assertFalse("unsafe name rejected", result.get("success").getAsBoolean());
        assertTrue(result.has("errors"));
    }

    @Test
    public void importReportsExistingSkill() throws Exception {
        Path tmp = Files.createTempDirectory("oc-import-exists");
        Path home = tmp.resolve("home");
        Path src = writeSkillMd(tmp.resolve("src"), "dup", "skill");
        Path target = home.resolve(".config").resolve("opencode").resolve("skills").resolve("dup");
        Files.createDirectories(target);

        JsonObject result = OpenCodeSkillProvider.importSkillsInto(
                List.of(src.toString()), "global", null, home.toString());

        assertFalse("existing skill not overwritten", result.get("success").getAsBoolean());
        assertTrue(result.has("errors"));
    }

    @Test
    public void importLocalScopeRequiresCwd() throws Exception {
        Path tmp = Files.createTempDirectory("oc-import-nocwd");
        Path src = writeSkillMd(tmp.resolve("src"), "foo", "skill");

        JsonObject result = OpenCodeSkillProvider.importSkillsInto(
                List.of(src.toString()), "local", null, tmp.resolve("home").toString());

        assertFalse("local scope without cwd fails", result.get("success").getAsBoolean());
    }

    // ── deleteSkill ──

    @Test
    public void deleteLocalSkillRemovesDirectory() throws Exception {
        Path tmp = Files.createTempDirectory("oc-del-local");
        Path cwd = tmp.resolve("proj");
        Path skillDir = writeSkillMd(cwd.resolve(".opencode").resolve("skills"), "foo", "skill");
        SkillId id = new SkillId("local", "foo", skillDir.resolve("SKILL.md").toString());

        JsonObject result = OpenCodeSkillProvider.deleteSkillFrom(
                id, true, cwd.toString(), tmp.resolve("home").toString());

        assertTrue("delete succeeds", result.get("success").getAsBoolean());
        assertFalse("skill dir removed", Files.exists(skillDir));
    }

    @Test
    public void deleteClearsPermissionDenyEntry() throws Exception {
        Path tmp = Files.createTempDirectory("oc-del-deny");
        Path home = tmp.resolve("home");
        Path opencodeJson = home.resolve(".config").resolve("opencode").resolve("opencode.json");
        Files.createDirectories(opencodeJson.getParent());
        Files.writeString(opencodeJson, "{\"permission\":{\"skill\":{\"foo\":\"deny\"}}}");
        Path cwd = tmp.resolve("proj");
        Path skillDir = writeSkillMd(cwd.resolve(".opencode").resolve("skills"), "foo", "skill");
        SkillId id = new SkillId("local", "foo", skillDir.resolve("SKILL.md").toString());

        JsonObject result = OpenCodeSkillProvider.deleteSkillFrom(id, false, cwd.toString(), home.toString());

        assertTrue(result.get("success").getAsBoolean());
        JsonObject parsed = JsonParser.parseString(Files.readString(opencodeJson)).getAsJsonObject();
        JsonObject skill = parsed.getAsJsonObject("permission").getAsJsonObject("skill");
        assertFalse("deny entry cleared after delete", skill.has("foo"));
    }

    @Test
    public void deleteRejectsPathOutsideSkillsDirs() throws Exception {
        Path tmp = Files.createTempDirectory("oc-del-outside");
        Path skillMd = writeSkillMd(tmp.resolve("evil"), "evil", "skill").resolve("SKILL.md");
        SkillId id = new SkillId("local", "evil", skillMd.toString());

        JsonObject result = OpenCodeSkillProvider.deleteSkillFrom(
                id, true, tmp.resolve("proj").toString(), tmp.resolve("home").toString());

        assertFalse("delete outside skills dirs blocked", result.get("success").getAsBoolean());
        assertTrue("error mentions invalid dir",
                result.get("error").getAsString().toLowerCase().contains("valid skills"));
    }

    @Test
    public void deleteRejectsSkillPathNotSkillMd() throws Exception {
        Path tmp = Files.createTempDirectory("oc-del-notmd");
        Path cwd = tmp.resolve("proj");
        Path skillDir = writeSkillMd(cwd.resolve(".opencode").resolve("skills"), "foo", "skill");
        SkillId id = new SkillId("local", "foo", skillDir.resolve("README.md").toString());

        JsonObject result = OpenCodeSkillProvider.deleteSkillFrom(
                id, true, cwd.toString(), tmp.resolve("home").toString());

        assertFalse("non-SKILL.md path rejected", result.get("success").getAsBoolean());
    }

    @Test
    public void deleteFallbackFromScopeAndName() throws Exception {
        Path tmp = Files.createTempDirectory("oc-del-fallback");
        Path cwd = tmp.resolve("proj");
        Path skillDir = writeSkillMd(cwd.resolve(".opencode").resolve("skills"), "foo", "skill");
        SkillId id = new SkillId("local", "foo", null);

        JsonObject result = OpenCodeSkillProvider.deleteSkillFrom(
                id, true, cwd.toString(), tmp.resolve("home").toString());

        assertTrue("fallback delete succeeds", result.get("success").getAsBoolean());
        assertFalse(Files.exists(skillDir));
    }
}
