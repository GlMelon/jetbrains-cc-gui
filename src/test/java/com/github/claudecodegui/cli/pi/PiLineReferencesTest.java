package com.github.claudecodegui.cli.pi;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 行号引用重写行为测试(镜像 ai-bridge utils/file-line-references.test.js):
 * {@code @path#L1[-L2]} → {@code @path (lines 1[-2])},pi 不解析 Claude 风格行号引用。
 */
public class PiLineReferencesTest {

    @Test
    public void reformatsIssueReferenceWithWindowsPath() {
        assertEquals(
                "@E:\\proj\\H2Migrator.java (lines 77) 这段代码是什么意思",
                PiRunOnceCliSession.reformatFileLineReferences("@E:\\proj\\H2Migrator.java#L77 这段代码是什么意思"));
    }

    @Test
    public void reformatsLineRange() {
        assertEquals(
                "@/home/user/Foo.java (lines 12-24) explica",
                PiRunOnceCliSession.reformatFileLineReferences("@/home/user/Foo.java#L12-24 explica"));
    }

    @Test
    public void acceptsManuallyWrittenLPrefixedRangeForm() {
        assertEquals(
                "@/home/user/Foo.java (lines 3-9) explica",
                PiRunOnceCliSession.reformatFileLineReferences("@/home/user/Foo.java#L3-L9 explica"));
    }

    @Test
    public void reformatsEveryReferenceInMessage() {
        assertEquals(
                "@a/Foo.java (lines 1) and @b/Bar.java (lines 2-4)",
                PiRunOnceCliSession.reformatFileLineReferences("@a/Foo.java#L1 and @b/Bar.java#L2-4"));
    }

    @Test
    public void leavesNonLineReferencesAndUnrelatedTextUnchanged() {
        assertEquals("@/path/Foo.java", PiRunOnceCliSession.reformatFileLineReferences("@/path/Foo.java"));
        assertEquals("Foo.java#L1", PiRunOnceCliSession.reformatFileLineReferences("Foo.java#L1"));
        assertEquals("text without references", PiRunOnceCliSession.reformatFileLineReferences("text without references"));
    }

    @Test
    public void reformatsReferenceAfterNewline() {
        assertEquals(
                "这是什么\n@E:/proj/Foo.java (lines 7) 什么意思",
                PiRunOnceCliSession.reformatFileLineReferences("这是什么\n@E:/proj/Foo.java#L7 什么意思"));
    }

    @Test
    public void doesNotRewriteMidWordAtSuchAsEmailAddress() {
        assertEquals(
                "email me at user@host.com#L5 please",
                PiRunOnceCliSession.reformatFileLineReferences("email me at user@host.com#L5 please"));
    }

    @Test
    public void doesNotRewriteReferencesQuotedInCodeSpan() {
        assertEquals(
                "use `@x/Y.java#L3` in code ticks",
                PiRunOnceCliSession.reformatFileLineReferences("use `@x/Y.java#L3` in code ticks"));
    }

    @Test
    public void passesThroughNullAndEmpty() {
        assertNull(PiRunOnceCliSession.reformatFileLineReferences(null));
        assertEquals("", PiRunOnceCliSession.reformatFileLineReferences(""));
    }
}
