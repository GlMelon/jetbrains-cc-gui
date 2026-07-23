package com.github.claudecodegui.skill;

import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SkillDocumentPathPolicyTest {

    @Test
    public void resolveAcceptsRegularSkillUnderAllowedRoot() throws Exception {
        Path root = Files.createTempDirectory("skill-root");
        Path skill = Files.createDirectories(root.resolve("sample"));
        Path file = Files.writeString(skill.resolve("SKILL.md"), "---\nname: sample\n---\n");

        SkillDocumentTarget target = SkillDocumentPathPolicy.resolve(skill, List.of(root));

        assertEquals(file.toRealPath(), target.file());
        assertEquals(root.toRealPath(), target.root());
    }

    @Test
    public void resolveRejectsTraversalSegments() throws Exception {
        Path root = Files.createTempDirectory("skill-traversal-root");
        Path outside = Files.createTempDirectory("skill-outside");
        Files.writeString(outside.resolve("SKILL.md"), "---\nname: outside\n---\n");
        Path traversal = root.resolve("child").resolve("..").resolve("..").resolve(outside.getFileName());

        assertRejected(traversal, root, "traversal");
    }

    @Test
    public void resolveRejectsSkillFileSymlink() throws Exception {
        Path root = Files.createTempDirectory("skill-link-root");
        Path target = Files.createTempFile("real-skill", ".md");
        Path skill = Files.createDirectories(root.resolve("sample"));
        Path link = skill.resolve("SKILL.md");
        createSymlinkOrSkip(link, target);

        assertRejected(skill, root, "Symbolic links");
    }

    @Test
    public void resolveRejectsIntermediateDirectorySymlink() throws Exception {
        Path root = Files.createTempDirectory("skill-dir-link-root");
        Path outside = Files.createTempDirectory("skill-dir-link-target");
        Files.writeString(outside.resolve("SKILL.md"), "---\nname: sample\n---\n");
        Path link = root.resolve("sample");
        createSymlinkOrSkip(link, outside);

        assertRejected(link.resolve("SKILL.md"), root, "Symbolic links");
    }

    private void assertRejected(Path path, Path root, String expectedMessage) throws Exception {
        try {
            SkillDocumentPathPolicy.resolve(path, List.of(root));
            fail("unsafe path must be rejected");
        } catch (SkillDocumentAccessException expected) {
            assertTrue(expected.getMessage().contains(expectedMessage));
        }
    }

    private void createSymlinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assume.assumeNoException("Symbolic links are unavailable on this environment", e);
        }
    }
}
