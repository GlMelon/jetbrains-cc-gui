package com.github.claudecodegui.cli.common;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link UserPathResolver} 纯函数单测。
 * <p>
 * 只测无 Platform 依赖的 package-private 重载({@link UserPathResolver#commonWindowsShimDirs(
 * String, String, String, String)} 与 {@link UserPathResolver#mergePath}),规避记忆中
 * CodemossSettingsService/Platform 在测试环境 NPE 的基线问题。
 */
public class UserPathResolverTest {

    // ===== commonWindowsShimDirs(4 参纯函数)=====

    @Test
    public void shimDirsContainsNpmScoopVoltaNodejsBun() {
        List<String> dirs = UserPathResolver.commonWindowsShimDirs(
                "C:\\Local", "C:\\AppData", "C:\\Users\\me", "C:\\Program Files");

        assertTrue(dirs.contains("C:\\AppData\\npm")); // npm 全局
        assertTrue(dirs.contains("C:\\Users\\me\\scoop\\shims")); // scoop shim
        assertTrue(dirs.contains("C:\\Users\\me\\.bun\\bin")); // bun 全局
        assertTrue(dirs.contains("C:\\Local\\Volta\\bin")); // volta shim
        assertTrue(dirs.contains("C:\\Local\\Programs\\nodejs")); // nodejs per-user
        assertTrue(dirs.contains("C:\\Program Files\\nodejs")); // nodejs machine
    }

    @Test
    public void shimDirsSkipsNullAndBlankEnvWithoutProducingNullLiteral() {
        // appData=null → 跳过 npm;userProfile="  " → 跳过 scoop/bun(绝不产生 "null\..." 字面量)
        List<String> dirs = UserPathResolver.commonWindowsShimDirs(
                "C:\\Local", null, "  ", "C:\\PF");

        assertFalse(dirs.stream().anyMatch(d -> d.contains("null")));
        assertFalse(dirs.stream().anyMatch(d -> d.contains("scoop")));
        assertFalse(dirs.stream().anyMatch(d -> d.contains(".bun")));
        assertFalse(dirs.stream().anyMatch(d -> d.contains("npm")));
        assertTrue(dirs.contains("C:\\Local\\Volta\\bin"));
        assertTrue(dirs.contains("C:\\Local\\Programs\\nodejs"));
        assertTrue(dirs.contains("C:\\PF\\nodejs"));
    }

    @Test
    public void shimDirsEmptyWhenAllEnvNull() {
        List<String> dirs = UserPathResolver.commonWindowsShimDirs(null, null, null, null);
        assertTrue(dirs.isEmpty());
    }

    // ===== mergePath 纯函数 =====

    @Test
    public void mergePathAppendsExtraToBasePreservingOrder() {
        String merged = UserPathResolver.mergePath(
                "C:\\a;C:\\b", List.of("C:\\c", "C:\\d"), ";");
        assertEquals("C:\\a;C:\\b;C:\\c;C:\\d", merged);
    }

    @Test
    public void mergePathCaseInsensitiveDedupKeepsFirstOccurrence() {
        // Windows 大小写不敏感去重:C:\A 与 c:\a 视为重复,保留首次(base 在前)。
        String merged = UserPathResolver.mergePath(
                "C:\\A", List.of("c:\\a", "C:\\B"), ";");
        assertEquals("C:\\A;C:\\B", merged);
    }

    @Test
    public void mergePathDedupsDuplicatesWithinBase() {
        String merged = UserPathResolver.mergePath(
                "C:\\a;C:\\a;C:\\b", List.of(), ";");
        assertEquals("C:\\a;C:\\b", merged);
    }

    @Test
    public void mergePathNullBaseReturnsExtraJoined() {
        String merged = UserPathResolver.mergePath(null, List.of("C:\\x", "C:\\y"), ";");
        assertEquals("C:\\x;C:\\y", merged);
    }

    @Test
    public void mergePathNullExtraReturnsBaseDeduped() {
        String merged = UserPathResolver.mergePath("C:\\a;C:\\b", null, ";");
        assertEquals("C:\\a;C:\\b", merged);
    }

    @Test
    public void mergePathEmptyBaseAndExtraReturnsBaseAsIs() {
        // deduped 为空 → 返回 basePath 原值(此处空串)
        String merged = UserPathResolver.mergePath("", List.of(), ";");
        assertEquals("", merged);
    }
}
