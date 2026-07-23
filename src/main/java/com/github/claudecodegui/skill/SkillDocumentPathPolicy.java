package com.github.claudecodegui.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/** Filesystem safety policy shared by provider-specific skill adapters. */
final class SkillDocumentPathPolicy {

    private static final String UPPER_FILE_NAME = "SKILL.md";
    private static final String LOWER_FILE_NAME = "skill.md";
    private static final String PARENT_SEGMENT = "..";

    private SkillDocumentPathPolicy() {
    }

    static SkillDocumentTarget resolve(Path requested, List<Path> allowedRoots)
            throws SkillDocumentAccessException {
        if (requested == null) {
            throw new SkillDocumentAccessException("Skill document path is required");
        }
        rejectTraversalSegments(requested);

        Path candidate = requested.toAbsolutePath();
        if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            candidate = locateSkillFile(candidate);
        }
        if (candidate == null || !isSkillFileName(candidate)) {
            throw new SkillDocumentAccessException("Skill document must be SKILL.md");
        }
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new SkillDocumentAccessException("Skill document does not exist or is not a regular file");
        }

        Path normalizedCandidate = candidate.normalize();
        for (Path allowedRoot : allowedRoots) {
            SkillDocumentTarget target = validateAgainstRoot(normalizedCandidate, allowedRoot);
            if (target != null) {
                return target;
            }
        }
        throw new SkillDocumentAccessException("Skill document is outside provider-owned skill directories");
    }

    private static SkillDocumentTarget validateAgainstRoot(Path candidate, Path root)
            throws SkillDocumentAccessException {
        if (root == null) {
            return null;
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!candidate.startsWith(normalizedRoot) || candidate.equals(normalizedRoot)) {
            return null;
        }
        if (!Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }

        rejectSymlinks(normalizedRoot, candidate);
        try {
            Path realRoot = normalizedRoot.toRealPath();
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realRoot) || realCandidate.equals(realRoot)) {
                throw new SkillDocumentAccessException("Skill document resolves outside provider-owned directory");
            }
            return new SkillDocumentTarget(realCandidate, realRoot);
        } catch (IOException e) {
            throw new SkillDocumentAccessException("Failed to resolve skill document path", e);
        }
    }

    private static void rejectTraversalSegments(Path path) throws SkillDocumentAccessException {
        for (Path segment : path) {
            if (PARENT_SEGMENT.equals(segment.toString())) {
                throw new SkillDocumentAccessException("Skill document path contains traversal segments");
            }
        }
    }

    private static void rejectSymlinks(Path root, Path candidate) throws SkillDocumentAccessException {
        Path current = root;
        if (Files.isSymbolicLink(current)) {
            throw new SkillDocumentAccessException("Provider skill root must not be a symbolic link");
        }
        Path relative = root.relativize(candidate);
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new SkillDocumentAccessException("Symbolic links are not allowed in skill document paths");
            }
        }
    }

    private static Path locateSkillFile(Path directory) {
        Path upper = directory.resolve(UPPER_FILE_NAME);
        if (Files.exists(upper, LinkOption.NOFOLLOW_LINKS)) {
            return upper;
        }
        Path lower = directory.resolve(LOWER_FILE_NAME);
        if (Files.exists(lower, LinkOption.NOFOLLOW_LINKS)) {
            return lower;
        }
        return null;
    }

    private static boolean isSkillFileName(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return false;
        }
        String value = fileName.toString();
        return UPPER_FILE_NAME.equals(value) || LOWER_FILE_NAME.equals(value);
    }
}
