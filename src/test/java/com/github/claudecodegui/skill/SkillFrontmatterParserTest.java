package com.github.claudecodegui.skill;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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
}
