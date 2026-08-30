package com.github.claudecodegui.cli.kimi.acp;

import com.github.claudecodegui.cli.common.ProviderCliResolver;
import com.github.claudecodegui.cli.compatibility.CliCompatibilityService;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.session.SessionCapabilityDegradationReason;
import com.intellij.openapi.diagnostic.Logger;

/**
 * kimi ACP 通道门禁:决定 {@code kimi acp} 子命令通道是否启用,未启用则回退 legacy
 * stream-json 通道({@link com.github.claudecodegui.cli.kimi.KimiRunOnceCliSession})。
 *
 * <p>三层 AND(仿 {@link com.github.claudecodegui.cli.common.CliPersistentFeatureFlags}):
 * <ol>
 *   <li>JVM -D 总开关 {@code kimiAcp.enabled}(默认 true):运维紧急关停,关停后全部回 legacy;</li>
 *   <li>版本经 {@link CliCompatibilityService#evaluateFeature} 检查 {@code features.acp} 规则
 *       (manifest kimi 规则的 features.acp minimumSupported=0.9.0);</li>
 * </ol>
 *
 * <p><b>安全降级铁律</b>:任何异常/版本未知/规则缺失都返回 {@code false}(走 legacy),
 * <em>绝不抛错</em>。这是与 {@link CliCompatibilityService#evaluate} 的关键差异——evaluate
 * 在 rule 缺失时 throw IllegalStateException(Holder clinit 教训:Error 穿透 catch(Exception)
 * 无日志死亡),而本门禁的 evaluateFeature 已设计为规则缺失返回 false 不抛;此处再套一层
 * catch 兜底,确保门禁永不阻塞 send 链。
 */
public final class KimiAcpChannelGate {

    private static final Logger LOG = Logger.getInstance(KimiAcpChannelGate.class);

    /** -D 总开关(默认 true,运维逃生)。 */
    public static final String FEATURE_ENABLED_KEY = "kimiAcp.enabled";
    /** manifest features 规则 id。 */
    static final String FEATURE_ID_ACP = "acp";

    private KimiAcpChannelGate() {
    }

    /**
     * kimi ACP 通道是否启用。-D 关闭 / 版本不满足 / 规则缺失 / 检测异常 → 返回 false(走 legacy)。
     * 仅当 -D 开且 kimi 版本落在 features.acp [0.9.0, 0.38.0] 范围(或 higherVersionPolicy 允许更高)才返回 true。
     */
    public static boolean isAcpEligible() {
        return eligibility().eligible();
    }

    /** Returns the same gate decision with a stable reason for runtime capability telemetry. */
    public static Eligibility eligibility() {
        if (!isSystemEnabled()) {
            return new Eligibility(false, SessionCapabilityDegradationReason.ACP_UNAVAILABLE);
        }
        try {
            String version = ProviderCliResolver.getCachedVersion(ProviderType.KIMI);
            if (version == null || version.isBlank()) {
                LOG.debug("[KimiAcpChannelGate] kimi version not cached yet; falling back to legacy stream-json");
                return new Eligibility(false, SessionCapabilityDegradationReason.VERSION_PROBE_FAILED);
            }
            boolean eligible = CliCompatibilityService.getInstance()
                    .evaluateFeature(ProviderType.KIMI, version, FEATURE_ID_ACP);
            return eligible
                    ? new Eligibility(true, null)
                    : new Eligibility(false, SessionCapabilityDegradationReason.VERSION_UNSUPPORTED);
        } catch (LinkageError | Exception e) {
            LOG.warn("[KimiAcpChannelGate] ACP eligibility check failed; falling back to legacy stream-json: " + e.getMessage());
            return new Eligibility(false, SessionCapabilityDegradationReason.VERSION_PROBE_FAILED);
        }
    }

    public record Eligibility(boolean eligible, SessionCapabilityDegradationReason degradationReason) {
    }

    public static SessionCapabilityDegradationReason degradationReason() {
        Eligibility result = eligibility();
        return result.degradationReason() == null
                ? SessionCapabilityDegradationReason.LEGACY_FALLBACK : result.degradationReason();
    }

    public static boolean isSystemEnabled() {
        return Boolean.parseBoolean(System.getProperty(FEATURE_ENABLED_KEY, "true"));
    }
}
