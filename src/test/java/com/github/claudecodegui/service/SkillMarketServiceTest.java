package com.github.claudecodegui.service;

import com.github.claudecodegui.service.SkillMarketService.SkillMarketSource;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link SkillMarketService} 纯函数单测(URL 构造 + Contents API 解析 + skill 目录定位)。
 * <p>不测 HTTP 集成(需网络,留端到端);覆盖 findSource/buildContentsUrl/buildTarballUrl/
 * parseContentsResponse/lastSegment/locateSkillDir 的边界与容错。
 * <p>解压用 BridgeArchiveExtractor(单测见 {@code BridgeArchiveExtractorTest}),此处不重复。
 */
public class SkillMarketServiceTest {

    private static final SkillMarketSource ANTHROPICS = SkillMarketService.findSource("anthropics");
    private static final SkillMarketSource SUPERPOWERS = SkillMarketService.findSource("superpowers");
    private static final SkillMarketSource VERCEL = SkillMarketService.findSource("vercel");

    // ── findSource ──

    @Test
    public void findSourceKnownIds() {
        assertNotNull(ANTHROPICS);
        assertNotNull(SUPERPOWERS);
        assertNotNull(VERCEL);
    }

    @Test
    public void findSourceUnknownReturnsNull() {
        assertNull(SkillMarketService.findSource("nonexistent"));
    }

    @Test
    public void findSourceNullReturnsNull() {
        assertNull(SkillMarketService.findSource(null));
    }

    @Test
    public void sourcesListContainsAllThree() {
        assertEquals(3, SkillMarketService.SOURCES.size());
    }

    // ── buildContentsUrl ──

    @Test
    public void buildContentsUrlWithSkillsPath() {
        String url = SkillMarketService.buildContentsUrl(ANTHROPICS, ANTHROPICS.skillsPath());
        assertEquals("https://api.github.com/repos/anthropics/skills/contents/skills?ref=main", url);
    }

    @Test
    public void buildContentsUrlEmptyPathOmitsSegment() {
        String url = SkillMarketService.buildContentsUrl(ANTHROPICS, "");
        assertEquals("https://api.github.com/repos/anthropics/skills/contents?ref=main", url);
    }

    @Test
    public void buildContentsUrlSuperpowersRepo() {
        String url = SkillMarketService.buildContentsUrl(SUPERPOWERS, "skills");
        assertEquals("https://api.github.com/repos/obra/superpowers/contents/skills?ref=main", url);
    }

    // ── buildTarballUrl ──

    @Test
    public void buildTarballUrlCodeloadFormat() {
        assertEquals("https://codeload.github.com/obra/superpowers/tar.gz/refs/heads/main",
            SkillMarketService.buildTarballUrl(SUPERPOWERS));
    }

    @Test
    public void buildTarballUrlVercelRepo() {
        assertEquals("https://codeload.github.com/vercel-labs/agent-skills/tar.gz/refs/heads/main",
            SkillMarketService.buildTarballUrl(VERCEL));
    }

    // ── buildRawUrl ──

    @Test
    public void buildRawUrlAnthropicsSkillMd() {
        assertEquals("https://raw.githubusercontent.com/anthropics/skills/main/skills/pdf/SKILL.md",
            SkillMarketService.buildRawUrl(ANTHROPICS, "skills/pdf", "SKILL.md"));
    }

    @Test
    public void buildRawUrlSuperpowersRepo() {
        assertEquals("https://raw.githubusercontent.com/obra/superpowers/main/skills/using-superpowers/skill.md",
            SkillMarketService.buildRawUrl(SUPERPOWERS, "skills/using-superpowers", "skill.md"));
    }

    @Test
    public void buildRawUrlEmptyPathRootLevel() {
        // 空路径 → 根级文件(无多余斜杠)
        assertEquals("https://raw.githubusercontent.com/anthropics/skills/main/SKILL.md",
            SkillMarketService.buildRawUrl(ANTHROPICS, "", "SKILL.md"));
    }

    @Test
    public void buildRawUrlStripsLeadingTrailingSlashes() {
        assertEquals("https://raw.githubusercontent.com/anthropics/skills/main/skills/pdf/SKILL.md",
            SkillMarketService.buildRawUrl(ANTHROPICS, "/skills/pdf/", "SKILL.md"));
    }

    @Test
    public void buildRawUrlNormalizesBackslashes() {
        // Windows 反斜杠 → 正斜杠
        assertEquals("https://raw.githubusercontent.com/anthropics/skills/main/skills/pdf/SKILL.md",
            SkillMarketService.buildRawUrl(ANTHROPICS, "skills\\pdf", "SKILL.md"));
    }

    // ── parseContentsResponse ──

    @Test
    public void parseContentsResponseFiltersDirsExcludesHiddenAndFiles() {
        JsonArray arr = new JsonArray();
        JsonObject dir = new JsonObject();
        dir.addProperty("type", "dir");
        dir.addProperty("name", "pdf");
        dir.addProperty("path", "skills/pdf");
        arr.add(dir);
        JsonObject file = new JsonObject();
        file.addProperty("type", "file");
        file.addProperty("name", "README.md");
        file.addProperty("path", "skills/README.md");
        arr.add(file);
        JsonObject hidden = new JsonObject();
        hidden.addProperty("type", "dir");
        hidden.addProperty("name", ".github");
        hidden.addProperty("path", "skills/.github");
        arr.add(hidden);

        JsonObject result = SkillMarketService.parseContentsResponse(arr.toString(), ANTHROPICS);
        assertEquals("anthropics", result.get("source").getAsString());
        assertEquals("Anthropic Skills", result.get("sourceLabel").getAsString());
        assertEquals(1, result.getAsJsonArray("skills").size());
        JsonObject skill = result.getAsJsonArray("skills").get(0).getAsJsonObject();
        assertEquals("pdf", skill.get("name").getAsString());
        assertEquals("skills/pdf", skill.get("path").getAsString());
    }

    @Test
    public void parseContentsResponseMultipleDirs() {
        JsonArray arr = new JsonArray();
        JsonObject d1 = new JsonObject();
        d1.addProperty("type", "dir");
        d1.addProperty("name", "pdf");
        d1.addProperty("path", "skills/pdf");
        arr.add(d1);
        JsonObject d2 = new JsonObject();
        d2.addProperty("type", "dir");
        d2.addProperty("name", "docx");
        d2.addProperty("path", "skills/docx");
        arr.add(d2);

        JsonObject result = SkillMarketService.parseContentsResponse(arr.toString(), ANTHROPICS);
        assertEquals(2, result.getAsJsonArray("skills").size());
    }

    @Test
    public void parseContentsResponseIllegalJsonReturnsEmptySkills() {
        JsonObject result = SkillMarketService.parseContentsResponse("not json", ANTHROPICS);
        assertEquals(0, result.getAsJsonArray("skills").size());
    }

    @Test
    public void parseContentsResponseNullBodyReturnsEmptySkills() {
        JsonObject result = SkillMarketService.parseContentsResponse(null, ANTHROPICS);
        assertEquals(0, result.getAsJsonArray("skills").size());
    }

    @Test
    public void parseContentsResponseEmptyBodyReturnsEmptySkills() {
        JsonObject result = SkillMarketService.parseContentsResponse("", ANTHROPICS);
        assertEquals(0, result.getAsJsonArray("skills").size());
    }

    @Test
    public void parseContentsResponseRootObjectReturnsEmptySkills() {
        // 根是对象(非数组)→ 容错空 skills
        JsonObject result = SkillMarketService.parseContentsResponse("{\"foo\":\"bar\"}", ANTHROPICS);
        assertEquals(0, result.getAsJsonArray("skills").size());
    }

    // ── lastSegment ──

    @Test
    public void lastSegmentExtractsSkillDirName() {
        assertEquals("pdf", SkillMarketService.lastSegment("skills/pdf"));
        assertEquals("pdf", SkillMarketService.lastSegment("skills/pdf/"));
        assertEquals("pdf", SkillMarketService.lastSegment("skills\\pdf"));
        assertEquals("pdf", SkillMarketService.lastSegment("pdf"));
    }

    @Test
    public void lastSegmentEmptyOrNullReturnsEmpty() {
        assertEquals("", SkillMarketService.lastSegment(""));
        assertEquals("", SkillMarketService.lastSegment(null));
        assertEquals("", SkillMarketService.lastSegment("///"));
    }

    // ── locateSkillDir ──

    @Test
    public void locateSkillDirFindsByFullPath() throws IOException {
        File extractDir = Files.createTempDirectory("skill-extract").toFile();
        try {
            // tarball 结构:{repo}-{branch}/skills/pdf/SKILL.md
            File top = new File(extractDir, "skills-main");
            File skillDir = new File(top, "skills/pdf");
            assertTrue(skillDir.mkdirs());
            assertTrue(new File(skillDir, "SKILL.md").createNewFile());

            File found = SkillMarketService.locateSkillDir(extractDir, "skills/pdf");
            assertNotNull(found);
            assertEquals("pdf", found.getName());
        } finally {
            deleteRecursively(extractDir);
        }
    }

    @Test
    public void locateSkillDirFindsByDeepNestedPathFallback() throws IOException {
        // path 完整路径不匹配,递归找同名目录(fallback 容错 path 格式差异)
        File extractDir = Files.createTempDirectory("skill-extract").toFile();
        try {
            File top = new File(extractDir, "skills-main");
            File skillDir = new File(top, "inner/skills/pdf"); // 深层
            assertTrue(skillDir.mkdirs());
            assertTrue(new File(skillDir, "SKILL.md").createNewFile());

            // path="skills/pdf" 但 top/skills/pdf 不存在 → fallback 递归找 name="pdf"
            File found = SkillMarketService.locateSkillDir(extractDir, "skills/pdf");
            assertNotNull(found);
            assertEquals("pdf", found.getName());
        } finally {
            deleteRecursively(extractDir);
        }
    }

    @Test
    public void locateSkillDirAcceptsLowercaseSkillMd() throws IOException {
        // 兼容 skill.md 小写
        File extractDir = Files.createTempDirectory("skill-extract").toFile();
        try {
            File top = new File(extractDir, "skills-main");
            File skillDir = new File(top, "skills/pdf");
            assertTrue(skillDir.mkdirs());
            assertTrue(new File(skillDir, "skill.md").createNewFile());

            File found = SkillMarketService.locateSkillDir(extractDir, "skills/pdf");
            assertNotNull(found);
        } finally {
            deleteRecursively(extractDir);
        }
    }

    @Test
    public void locateSkillDirNoSkillMdReturnsNull() throws IOException {
        File extractDir = Files.createTempDirectory("skill-extract").toFile();
        try {
            File top = new File(extractDir, "skills-main");
            assertTrue(new File(top, "skills/pdf").mkdirs()); // 无 SKILL.md

            assertNull(SkillMarketService.locateSkillDir(extractDir, "skills/pdf"));
        } finally {
            deleteRecursively(extractDir);
        }
    }

    @Test
    public void locateSkillDirNullExtractDirReturnsNull() {
        assertNull(SkillMarketService.locateSkillDir(null, "skills/pdf"));
    }

    private static void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
