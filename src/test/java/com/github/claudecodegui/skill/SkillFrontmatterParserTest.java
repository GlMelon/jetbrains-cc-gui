package com.github.claudecodegui.skill;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link SkillFrontmatterParser}.
 * <p>
 * Covers lenient parsing scenarios: UTF-8 BOM stripping, missing-field fallbacks.
 */
public class SkillFrontmatterParserTest {

    /**
     * SKILL.md saved by Windows editors (e.g. Notepad) often carries a UTF-8 BOM.
     * The parser must strip it so frontmatter is still recognized.
     */
    @Test
    public void parseHandlesUtf8Bom() throws IOException {
        Path dir = Files.createTempDirectory("skill-bom");
        String content = "---\nname: my-skill\ndescription: A skill with BOM\n---\n\nBody.\n";
        byte[] utf8Bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(utf8Bom);
        bos.write(content.getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("SKILL.md"), bos.toByteArray());

        SkillFrontmatterParser.SkillMetadata metadata = SkillFrontmatterParser.parse(dir);

        assertNotNull("metadata should be parsed despite UTF-8 BOM", metadata);
        assertEquals("my-skill", metadata.name());
        assertEquals("A skill with BOM", metadata.description());
    }

    /**
     * BOM should also be tolerated when extracting only the description field.
     */
    @Test
    public void extractDescriptionHandlesUtf8Bom() throws IOException {
        Path dir = Files.createTempDirectory("skill-bom-desc");
        String content = "---\nname: bom-skill\ndescription: Desc with BOM\n---\n\nBody.\n";
        byte[] utf8Bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(utf8Bom);
        bos.write(content.getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("SKILL.md"), bos.toByteArray());

        String description = SkillFrontmatterParser.extractDescription(dir);

        assertEquals("Desc with BOM", description);
    }

    /**
     * Sanity: a directory with no SKILL.md still returns null (no spurious fallback).
     */
    @Test
    public void parseReturnsNullWhenNoSkillMd() throws IOException {
        Path dir = Files.createTempDirectory("skill-empty");
        SkillFrontmatterParser.SkillMetadata metadata = SkillFrontmatterParser.parse(dir);
        assertNull(metadata);
    }

    /**
     * Skill discovery re-parses the same files on every refresh, so the invalid-name
     * warning must be emitted only on the first pass per file.
     */
    @Test
    public void invalidNameWarnsOnlyOnTheFirstPassForEachFile() {
        Path first = Path.of("/tmp/skills/alpha-warn-once/SKILL.md");
        Path second = Path.of("/tmp/skills/beta-warn-once/SKILL.md");

        assertTrue("first sighting must be reported", SkillFrontmatterParser.shouldReportInvalidName(first));
        assertFalse("re-scanning the same file must stay quiet", SkillFrontmatterParser.shouldReportInvalidName(first));
        assertTrue("a different file is its own warning", SkillFrontmatterParser.shouldReportInvalidName(second));
        assertFalse(SkillFrontmatterParser.shouldReportInvalidName(second));
    }

    /**
     * The warn-once guard only gates the log line - the parsed metadata must be
     * identical on every pass, otherwise skill discovery would drift.
     */
    @Test
    public void parseFallsBackToDirectoryNameAndStaysIdempotent() throws IOException {
        Path skillDir = Files.createTempDirectory("skill-invalid-name").resolve("my-skill");
        Files.createDirectories(skillDir);
        Files.writeString(
                skillDir.resolve("SKILL.md"),
                "---\nname: Not A Valid Name\ndescription: does something\n---\n\nBody.\n",
                StandardCharsets.UTF_8
        );

        SkillFrontmatterParser.SkillMetadata first = SkillFrontmatterParser.parse(skillDir);
        SkillFrontmatterParser.SkillMetadata second = SkillFrontmatterParser.parse(skillDir);

        assertEquals("my-skill", first.name());
        assertEquals(first.name(), second.name());
        assertEquals("does something", first.description());
    }
}
