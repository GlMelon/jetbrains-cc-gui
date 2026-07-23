package com.github.claudecodegui.skill;

import org.junit.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SkillDocumentCodecTest {

    private final SkillDocumentCodec codec = new SkillDocumentCodec();

    @Test
    public void renderPreservesUnknownFieldsCommentsOrderAndBody() throws Exception {
        String source = "---\r\n"
                + "# retained comment\r\n"
                + "name: old-name\r\n"
                + "x-provider-option: keep-me\r\n"
                + "description: old description\r\n"
                + "---\r\n"
                + "\r\n# Body\r\n\r\nKeep this body exactly.\r\n";
        SkillDocumentCodec.ParsedDocument parsed = codec.parse(source);
        Map<SkillFrontmatterField, Object> changes =
                new EnumMap<>(SkillFrontmatterField.class);
        changes.put(SkillFrontmatterField.NAME, "new-name");
        changes.put(SkillFrontmatterField.DESCRIPTION, "new description");

        String rendered = codec.render(parsed, changes, parsed.body());

        assertTrue(rendered.contains("# retained comment\r\n"));
        assertTrue(rendered.contains("x-provider-option: keep-me\r\n"));
        assertTrue(rendered.indexOf("name:") < rendered.indexOf("x-provider-option:"));
        assertTrue(rendered.indexOf("x-provider-option:") < rendered.indexOf("description:"));
        assertTrue(rendered.endsWith("\r\n# Body\r\n\r\nKeep this body exactly.\r\n"));
        assertTrue(rendered.contains("name: \"new-name\""));
        assertTrue(rendered.contains("description: \"new description\""));
    }

    @Test
    public void renderUpdatesPathsWithoutDroppingUnknownYaml() throws Exception {
        String source = "---\nname: sample\npaths:\n  - old/**\ncustom:\n  nested: true\n---\nBody\n";
        SkillDocumentCodec.ParsedDocument parsed = codec.parse(source);
        Map<SkillFrontmatterField, Object> changes =
                new EnumMap<>(SkillFrontmatterField.class);
        changes.put(SkillFrontmatterField.PATHS, List.of("src/**", "test/**"));

        String rendered = codec.render(parsed, changes, parsed.body());

        assertTrue(rendered.contains("paths:\n  - \"src/**\"\n  - \"test/**\""));
        assertTrue(rendered.contains("custom:\n  nested: true"));
        assertFalse(rendered.contains("old/**"));
    }

    @Test
    public void parseRejectsInvalidYaml() {
        String source = "---\nname: [broken\n---\nBody\n";
        try {
            codec.parse(source);
            fail("invalid YAML must be rejected");
        } catch (SkillDocumentFormatException expected) {
            assertTrue(expected.getMessage().contains("Invalid YAML"));
        }
    }

    @Test
    public void parseRejectsDuplicateEditableField() {
        String source = "---\nname: first\nname: second\n---\nBody\n";
        try {
            codec.parse(source);
            fail("duplicate editable fields must be rejected");
        } catch (SkillDocumentFormatException expected) {
            assertEquals("Duplicate editable frontmatter field: name", expected.getMessage());
        }
    }
}
