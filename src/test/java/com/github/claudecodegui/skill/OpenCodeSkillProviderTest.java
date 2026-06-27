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
}
