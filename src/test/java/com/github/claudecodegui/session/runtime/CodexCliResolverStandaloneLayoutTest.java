package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.cli.compatibility.CodexCliVersionParser;
import com.github.claudecodegui.util.PlatformUtils;

import org.junit.Test;

import java.io.File;
import java.util.List;
import java.util.regex.Matcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 独立安装器布局发现与严格版本门控的纯函数测试(对齐 upstream fe95dd8e)。
 */
public class CodexCliResolverStandaloneLayoutTest {

    private static final String HOME = "/home/tester";
    private static final String SEP = File.separator;

    private static String join(String base, String... parts) {
        return base + SEP + String.join(SEP, parts);
    }

    @Test
    public void standaloneDirsContainCodexHomeBinAndFlatLayouts() {
        List<String> dirs = CodexCliResolver.standaloneBinDirs(HOME, null, null, null);

        if (PlatformUtils.isWindows()) {
            assertTrue(dirs.contains(join(HOME, "AppData", "Local", "Programs", "OpenAI", "Codex", "bin")));
        }
        assertTrue(dirs.contains(join(HOME, ".local", "bin")));
        assertTrue(dirs.contains(join(HOME, ".codex", "packages", "standalone", "current", "bin")));
        assertTrue(dirs.contains(join(HOME, ".codex", "packages", "standalone", "current")));
        assertTrue(dirs.contains(join(HOME, ".cargo", "bin")));
        assertTrue(dirs.contains(join(HOME, ".codex", "bin")));
    }

    @Test
    public void codexHomeOverrideReplacesDefaultHome() {
        String override = "/custom/codex-home";
        List<String> dirs = CodexCliResolver.standaloneBinDirs(HOME, null, null, override);

        assertTrue(dirs.contains(join(override, "packages", "standalone", "current", "bin")));
        assertFalse(dirs.contains(join(HOME, ".codex", "packages", "standalone", "current", "bin")));
    }

    @Test
    public void explicitInstallDirComesFirst() {
        List<String> dirs = CodexCliResolver.standaloneBinDirs(HOME, null, "/opt/codex", null);

        assertEquals("/opt/codex", dirs.get(0));
    }

    @Test
    public void strictVersionLineAcceptsOnlyProvenCodexCliOutput() {
        Matcher good = CodexCliVersionParser.STRICT_VERSION_LINE.matcher("codex-cli 0.146.0");
        assertTrue(good.find());
        assertEquals("0.146.0", good.group(1));

        assertTrue(CodexCliVersionParser.STRICT_VERSION_LINE.matcher("Codex-CLI v0.153.4").find());

        // 半安装的 npm launcher 可能仍可执行,但旧格式/裸名输出不构成可信安装。
        assertFalse(CodexCliVersionParser.STRICT_VERSION_LINE.matcher("codex 0.117.0").find());
        assertFalse(CodexCliVersionParser.STRICT_VERSION_LINE.matcher("0.117.0").find());
        assertFalse(CodexCliVersionParser.STRICT_VERSION_LINE.matcher("claude-cli 1.0.0").find());
    }
}
