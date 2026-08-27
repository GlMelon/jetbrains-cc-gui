package com.github.claudecodegui.cli.compatibility;

import com.github.claudecodegui.session.runtime.ProviderType;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** End-to-end provider matrix for parser selection and compatibility policy evaluation. */
public class CliCompatibilityProviderMatrixTest {

    @Test
    public void everyProviderParsesItsCliOutputAndAppliesTheSamePolicy() throws IOException {
        CliCompatibilityService service = service();
        Map<ProviderType, String> compatibleOutputs = new EnumMap<>(ProviderType.class);
        compatibleOutputs.put(ProviderType.CLAUDE, "Claude Code 1.4.0");
        compatibleOutputs.put(ProviderType.CODEX, "codex-cli 1.4.0");
        compatibleOutputs.put(ProviderType.OPENCODE, "OpenCode 1.4.0");
        // grok/kimi/pi 纯 CLI provider:各自的 VersionParser 模式 (?i)<value>[^0-9]*v?<ver>
        // 从 "<Label> 1.4.0" 提取 "1.4.0",与三 full provider 走同一条 evaluate 路径。
        compatibleOutputs.put(ProviderType.GROK, "Grok 1.4.0");
        compatibleOutputs.put(ProviderType.KIMI, "Kimi 1.4.0");
        compatibleOutputs.put(ProviderType.PI, "Pi 1.4.0");
        // omp/dsh 同为 <value>[^0-9]*v?<ver> 模式(Omp/DshCliVersionParser),补齐矩阵
        // 覆盖(a3b6106e 加枚举时漏配,EnumMap 缺项会以 null 进 evaluate 解析失败)。
        compatibleOutputs.put(ProviderType.OMP, "OMP 1.4.0");
        compatibleOutputs.put(ProviderType.DSH, "DSH 1.4.0");

        Map<ProviderType, String> blockedOutputs = new EnumMap<>(ProviderType.class);
        blockedOutputs.put(ProviderType.CLAUDE, "Claude Code 1.5.0");
        blockedOutputs.put(ProviderType.CODEX, "codex-cli 1.5.0");
        blockedOutputs.put(ProviderType.OPENCODE, "OpenCode 1.5.0");
        blockedOutputs.put(ProviderType.GROK, "Grok 1.5.0");
        blockedOutputs.put(ProviderType.KIMI, "Kimi 1.5.0");
        blockedOutputs.put(ProviderType.PI, "Pi 1.5.0");
        blockedOutputs.put(ProviderType.OMP, "OMP 1.5.0");
        blockedOutputs.put(ProviderType.DSH, "DSH 1.5.0");

        for (ProviderType provider : ProviderType.values()) {
            CliCompatibilityDecision compatible = service.evaluate(provider, compatibleOutputs.get(provider));
            assertEquals(provider, compatible.provider());
            assertEquals("1.4.0", compatible.normalizedVersion());
            assertEquals(CliCompatibilityStatus.COMPATIBLE, compatible.status());
            assertTrue(provider.value() + " compatible version must be accepted", compatible.allowed());
            assertFalse(provider.value() + " compatible version must not warn", compatible.warning());

            CliCompatibilityDecision blocked = service.evaluate(provider, blockedOutputs.get(provider));
            assertEquals(provider, blocked.provider());
            assertEquals("1.5.0", blocked.normalizedVersion());
            assertEquals(CliCompatibilityStatus.BLOCKED, blocked.status());
            assertFalse(provider.value() + " blocked version must be rejected", blocked.allowed());
            assertTrue(provider.value() + " blocked version must warn", blocked.warning());
        }
    }

    private static CliCompatibilityService service() throws IOException {
        return serviceWithManifest(manifest());
    }

    private static CliCompatibilityService serviceWithManifest(byte[] bundled) throws IOException {
        Path cache = Files.createTempDirectory("cli-compat-provider-matrix");
        CliCompatibilityManifestRepository repository = new CliCompatibilityManifestRepository(
                cache,
                ignored -> bundled,
                (url, maxBytes) -> {
                    throw new IOException("offline");
                },
                (manifest, signature) -> false,
                new CliCompatibilityManifestCodec(),
                "https://example.test/manifest.json",
                "https://example.test/manifest.json.sig");
        return new CliCompatibilityService(repository, CliVersionParserRegistry.defaults());
    }

    /**
     * evaluateFeature(kimi ACP 门禁)的 4 边界:范围内 true / 低于 floor false /
     * 其它 provider 无该 feature 规则 false(安全降级) / 未知 feature id false。
     * 同时验证 manifest 无 features 字段(向后兼容)能正常解析(evaluateFeature 恒 false)。
     */
    @Test
    public void kimiAcpFeatureGateIsEvaluatedSafely() throws IOException {
        // kimi 带 features.acp(0.9.0-0.38.0);其它 provider 同一基础 rule 无 features
        String baseRule = "{\"minimumSupported\":\"0.0.0\",\"maximumTested\":\"1.0.0\","
                + "\"blockedVersions\":[],\"unknownVersionPolicy\":\"WARN_ALLOW\","
                + "\"higherVersionPolicy\":\"WARN_ALLOW\"}";
        String kimiRule = "{\"minimumSupported\":\"0.0.0\",\"maximumTested\":\"0.38.0\","
                + "\"blockedVersions\":[],\"unknownVersionPolicy\":\"WARN_ALLOW\","
                + "\"higherVersionPolicy\":\"WARN_ALLOW\","
                + "\"features\":{\"acp\":{\"minimumSupported\":\"0.9.0\",\"maximumTested\":\"0.38.0\"}}}";
        StringBuilder providers = new StringBuilder();
        for (ProviderType provider : ProviderType.values()) {
            if (providers.length() > 0) {
                providers.append(',');
            }
            providers.append('"').append(provider.value()).append("\":")
                    .append(provider == ProviderType.KIMI ? kimiRule : baseRule);
        }
        String json = "{\"schemaVersion\":1,\"revision\":2026082802,"
                + "\"generatedAt\":\"2026-08-28\",\"providers\":{" + providers + "}}";
        CliCompatibilityService svc = serviceWithManifest(json.getBytes(StandardCharsets.UTF_8));

        // 范围内(0.38.0)→ true
        assertTrue("kimi 0.38.0 ACP gate must pass",
                svc.evaluateFeature(ProviderType.KIMI, "Kimi 0.38.0", "acp"));
        // 低于 floor(0.8.0 < 0.9.0)→ false
        assertFalse("kimi 0.8.0 below ACP floor must fail",
                svc.evaluateFeature(ProviderType.KIMI, "Kimi 0.8.0", "acp"));
        // 其它 provider 无 acp 规则 → false(安全降级,不抛)
        assertFalse("grok has no acp feature rule; must return false",
                svc.evaluateFeature(ProviderType.GROK, "Grok 0.38.0", "acp"));
        // 未知 feature id → false
        assertFalse("unknown feature id must return false",
                svc.evaluateFeature(ProviderType.KIMI, "Kimi 0.38.0", "nonexistent_feature"));
    }

    /**
     * 无 features 字段的 legacy manifest 仍能正常解析(向后兼容),
     * 且 evaluateFeature 对所有 provider 恒 false(无门禁数据即降级)。
     */
    @Test
    public void legacyManifestWithoutFeaturesParsesAndDegradesFeatureEvaluation() throws IOException {
        CliCompatibilityService svc = service();  // manifest() 无 features 字段
        // 仍能正常 evaluate(向后兼容)
        assertTrue(svc.evaluate(ProviderType.KIMI, "Kimi 1.4.0").allowed());
        // evaluateFeature 无规则 → false,不抛
        assertFalse(svc.evaluateFeature(ProviderType.KIMI, "Kimi 1.4.0", "acp"));
    }

    private static byte[] manifest() {
        String rule = "{\"minimumSupported\":\"1.0.0\","
                + "\"maximumTested\":\"2.0.0\","
                + "\"blockedVersions\":[\"1.5.0\"],"
                + "\"unknownVersionPolicy\":\"BLOCK\","
                + "\"higherVersionPolicy\":\"BLOCK\"}";
        // providers 必须覆盖全部 ProviderType(codec.validate() fail-fast),按枚举 SSOT 动态生成,
        // 使每个 provider 都套用同一 rule(compatible 1.4.0 / blocked 1.5.0)。
        StringBuilder providers = new StringBuilder();
        for (ProviderType provider : ProviderType.values()) {
            if (providers.length() > 0) {
                providers.append(',');
            }
            providers.append('"').append(provider.value()).append("\":").append(rule);
        }
        String json = "{\"schemaVersion\":1,\"revision\":2026072901,"
                + "\"generatedAt\":\"2026-07-29\",\"providers\":{" + providers + "}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
