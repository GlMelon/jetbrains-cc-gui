package com.github.claudecodegui.handler;

import com.github.claudecodegui.bridge.ProcessManager;
import org.junit.Assume;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * S3 故障注入测试矩阵(docs/comprehensive-optimization-directions.md §S3)。
 * <p>
 * 历史仅有 1 个静态文本断言;本轮补齐 executeNodeScript 的边界覆盖:永不退出、
 * 超大输出、非零退出 + stderr 分流、stderr 不污染 stdout、正常 framing。
 * <p>
 * 需要本机 PATH 可用 {@code node};不可用时故障注入测试整体 {@link Assume#assumeTrue}
 * 跳过(不报失败),仅保留静态断言。{@code @Test(timeout=...)} 保证 timeout 逻辑回归时
 * 测试线程本身不会永久挂死。
 */
public class NodeJsServiceCallerTest {

    /** 探测 PATH 上的 node;不可用则故障注入测试跳过。 */
    private static String resolveNode() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? "node" : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static final String NODE = resolveNode();

    private static NodeJsServiceCaller newCaller(int timeoutSec) {
        // context=null:测试只驱动 executeNodeScript,不触碰 context。
        return new NodeJsServiceCaller(null, new ProcessManager(), timeoutSec);
    }

    private static ProcessBuilder nodeInline(String script) {
        return new ProcessBuilder(NODE, "-e", script);
    }

    private static void assumeNodeAvailable() {
        Assume.assumeTrue("node not available on PATH — skipping subprocess injection tests", NODE != null);
    }

    // ---- 原有静态断言(保留)----

    @Test
    public void favoritesAllowlistIncludesRemoveFavoriteForDeleteCleanup() throws Exception {
        Path source = Path.of("src/main/java/com/github/claudecodegui/handler/NodeJsServiceCaller.java");
        String text = Files.readString(source, StandardCharsets.UTF_8);

        assertTrue(text.contains("\"removeFavorite\""));
    }

    // ---- S3 故障注入矩阵 ----

    /** 永不退出的子进程必须在 timeout 内被终止并抛异常(根治 readLine 阻塞致 waitFor 永不触达)。 */
    @Test(timeout = 30000)
    public void neverExitingProcessTimesOutInsteadOfHanging() throws Exception {
        assumeNodeAvailable();
        ProcessBuilder pb = nodeInline("setInterval(function(){}, 1000)");
        long start = System.nanoTime();
        try {
            newCaller(2).executeNodeScript(pb);
            fail("expected timeout exception");
        } catch (Exception e) {
            assertTrue("message: " + e.getMessage(), e.getMessage().contains("timed out"));
        }
        // 必须在远小于 @Test timeout 内返回 —— 证明 waitFor(2s) 真正生效。
        long elapsedSec = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
        assertTrue("should return shortly after 2s timeout, took " + elapsedSec + "s", elapsedSec < 20);
    }

    /** 超大单行输出必须触发单行 cap,快速失败(不等 timeout、不 OOM)。 */
    @Test(timeout = 30000)
    public void oversizedSingleLineHitsCap() throws Exception {
        assumeNodeAvailable();
        // 2 MiB 单行 >> 64 KiB 行上限。
        ProcessBuilder pb = nodeInline(
                "process.stdout.write(Buffer.alloc(2*1024*1024).fill(0x61).toString())");
        long start = System.nanoTime();
        try {
            newCaller(30).executeNodeScript(pb);
            fail("expected cap exception");
        } catch (Exception e) {
            assertTrue("message: " + e.getMessage(), e.getMessage().contains("cap"));
        }
        // cap 必须快速触发(读线程 destroyForcibly 打断子进程),不能干等 30s timeout。
        long elapsedSec = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
        assertTrue("cap should fail fast, took " + elapsedSec + "s", elapsedSec < 20);
    }

    /** 超大总输出(多行累积超 1 MiB)必须触发总字节 cap。 */
    @Test(timeout = 30000)
    public void oversizedTotalOutputHitsCap() throws Exception {
        assumeNodeAvailable();
        // 每行 1 KiB,写 4 MiB >> 1 MiB 总上限。
        ProcessBuilder pb = nodeInline(
                "var s='x'.repeat(1024)+'\\n'; for(var i=0;i<4096;i++) process.stdout.write(s)");
        try {
            newCaller(30).executeNodeScript(pb);
            fail("expected cap exception");
        } catch (Exception e) {
            assertTrue("message: " + e.getMessage(), e.getMessage().contains("cap"));
        }
    }

    /** 非零退出码:stderr 经分流被捕获进异常信息(验证 stderr 不污染、可读)。 */
    @Test(timeout = 30000)
    public void nonZeroExitCapturesStderr() throws Exception {
        assumeNodeAvailable();
        ProcessBuilder pb = nodeInline("console.error('err-detail-xyz'); process.exit(2)");
        try {
            newCaller(30).executeNodeScript(pb);
            fail("expected non-zero exit exception");
        } catch (Exception e) {
            String msg = e.getMessage();
            assertTrue("message: " + msg, msg.contains("code 2"));
            assertTrue("stderr should be captured: " + msg, msg.contains("err-detail-xyz"));
        }
    }

    /** stderr 即便有输出也不污染 stdout 的 JSON(分流验证的另一面)。 */
    @Test(timeout = 30000)
    public void stderrDoesNotPolluteStdoutJson() throws Exception {
        assumeNodeAvailable();
        ProcessBuilder pb = nodeInline(
                "console.error('this-is-stderr-noise'); console.log(JSON.stringify({ok:true}))");
        String result = newCaller(30).executeNodeScript(pb);
        assertEquals("{\"ok\":true}", result);
    }

    /** 正常路径:多行输出取最后一行 JSON(稳定 framing)。 */
    @Test(timeout = 30000)
    public void normalOutputReturnsLastLineJson() throws Exception {
        assumeNodeAvailable();
        ProcessBuilder pb = nodeInline(
                "console.log('log-line'); console.log(JSON.stringify({ok:true}))");
        String result = newCaller(30).executeNodeScript(pb);
        assertEquals("{\"ok\":true}", result);
    }
}
