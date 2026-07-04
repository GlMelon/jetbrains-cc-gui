package com.github.claudecodegui.mcp;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * 验证 {@link McpGatewayService#applySnapshot} 的"提交顺序":postSnapshot 失败时不得提交
 * currentSnapshot/currentRevision,否则后续 applySnapshot 因 configHash 相同而 skip、永不重推
 * (gateway 实际空载、CLI 拿不到 MCP 工具)。复现见 idea.log 2026-07-02
 * BridgePreloader.prewarmMcpGateway → postSnapshot HttpTimeoutException。
 */
public class McpGatewayServiceTest {

    @Test
    public void applySnapshotRetriesPostAfterFailure() throws Exception {
        FailingThenSuccessClient client = new FailingThenSuccessClient();
        StubCollector collector = new StubCollector();
        McpGatewayService service = new McpGatewayService(collector, client);

        // 第一次:postSnapshot 失败,应向上抛出且不提交 currentSnapshot
        try {
            service.applySnapshot("/test");
            fail("applySnapshot 应传播 postSnapshot 失败");
        } catch (Exception expected) {
            // expected
        }
        assertEquals("第一次应调用 postSnapshot", 1, client.postCalls);

        // 第二次:configHash 相同(StubCollector 始终返回空 servers + 相同 projectPath)。
        // 若第一次失败后误提交 currentSnapshot,此处会因 hash 相同而 skip、postCalls 不变(==1)。
        // 正确行为:currentSnapshot 未提交 → 不 skip → 再次 postSnapshot(==2)。
        service.applySnapshot("/test");
        assertEquals("失败后必须重试 postSnapshot,不能因假成功的 configHash 跳过", 2, client.postCalls);
    }

    /** 固定返回空 server 列表 + 指定 projectPath,使两次 collect 的 configHash 相同。 */
    public static final class StubCollector extends McpGatewayConfigCollector {
        public StubCollector() {
            super(null);
        }

        @Override
        public McpGatewayConfigSnapshot collect(long revision, String projectPath) {
            return McpGatewayConfigSnapshot.create(revision, projectPath, List.of());
        }
    }

    /** 第一次 postSnapshot 抛异常(模拟 HttpTimeoutException),第二次成功。 */
    public static final class FailingThenSuccessClient extends McpGatewayBridgeClient {
        int postCalls = 0;

        public FailingThenSuccessClient() {
            super(Path.of(System.getProperty("java.io.tmpdir")).resolve("mcp-gw-test-state.json"),
                    "test-token");
        }

        @Override
        public JsonObject postSnapshot(McpGatewayConfigSnapshot snapshot) {
            postCalls++;
            if (postCalls == 1) {
                throw new RuntimeException("simulated postSnapshot timeout");
            }
            return new JsonObject();
        }
    }
}
