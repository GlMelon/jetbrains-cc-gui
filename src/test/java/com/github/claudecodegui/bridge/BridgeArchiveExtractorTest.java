package com.github.claudecodegui.bridge;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.Test;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link BridgeArchiveExtractor#extractTarGz} 的单元测试。
 * <p>覆盖:正常解压(含嵌套目录+多文件)、目标目录自动创建、ZipSlip 防御(../ 逃逸拦截)。
 * tar.gz 用 commons-compress 的 {@link TarArchiveOutputStream} +
 * {@link GzipCompressorOutputStream} 构造,避免依赖系统 tar。
 */
public class BridgeArchiveExtractorTest {

    /** 创建含若干文件条目的 tar.gz(path -> 内容)。文件父目录由解压侧自动创建。 */
    private static Path writeTarGz(Path archive, Map<String, String> entries) throws Exception {
        try (TarArchiveOutputStream tos = new TarArchiveOutputStream(
                new GzipCompressorOutputStream(new BufferedOutputStream(new FileOutputStream(archive.toFile()))))) {
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            for (Map.Entry<String, String> e : entries.entrySet()) {
                byte[] data = e.getValue().getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry entry = new TarArchiveEntry(e.getKey());
                entry.setSize(data.length);
                tos.putArchiveEntry(entry);
                tos.write(data);
                tos.closeArchiveEntry();
            }
        }
        return archive;
    }

    @Test
    public void extractTarGzExtractsNestedFiles() throws Exception {
        Path tmp = Files.createTempDirectory("targz-extract");
        Path archive = tmp.resolve("skill.tar.gz");
        Path target = tmp.resolve("out");

        String skillMd = "---\nname: foo\ndescription: test\n---\nbody";
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("repo-abc/skills/foo/SKILL.md", skillMd);
        entries.put("repo-abc/skills/foo/refs.txt", "ref content");
        writeTarGz(archive, entries);

        BridgeArchiveExtractor.extractTarGz(archive.toFile(), target.toFile(), null);

        Path extracted = target.resolve("repo-abc").resolve("skills").resolve("foo");
        assertTrue("SKILL.md extracted", Files.exists(extracted.resolve("SKILL.md")));
        assertEquals(skillMd, Files.readString(extracted.resolve("SKILL.md")));
        assertEquals("ref content", Files.readString(extracted.resolve("refs.txt")));
    }

    @Test
    public void extractTarGzCreatesTargetDirIfMissing() throws Exception {
        Path tmp = Files.createTempDirectory("targz-mkdir");
        Path archive = tmp.resolve("a.tar.gz");
        Path target = tmp.resolve("nested").resolve("out");

        writeTarGz(archive, Map.of("hello.txt", "hi"));
        BridgeArchiveExtractor.extractTarGz(archive.toFile(), target.toFile(), null);

        assertEquals("hi", Files.readString(target.resolve("hello.txt")));
    }

    @Test
    public void extractTarGzBlocksZipSlip() throws Exception {
        Path tmp = Files.createTempDirectory("targz-slip");
        Path archive = tmp.resolve("evil.tar.gz");
        Path target = tmp.resolve("out");

        writeTarGz(archive, Map.of("../evil.txt", "escaped"));

        try {
            BridgeArchiveExtractor.extractTarGz(archive.toFile(), target.toFile(), null);
            fail("Expected IOException for ZipSlip entry");
        } catch (java.io.IOException e) {
            assertTrue("error mentions unsafe: " + e.getMessage(),
                    e.getMessage().toLowerCase().contains("unsafe"));
        }
        // 确认 ../ 没有逃逸到上级目录
        assertFalse("evil.txt must not escape target dir",
                Files.exists(tmp.resolve("evil.txt")));
    }
}
