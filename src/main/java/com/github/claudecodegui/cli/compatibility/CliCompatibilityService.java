package com.github.claudecodegui.cli.compatibility;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Backend facade for provider CLI version parsing and manifest-based compatibility decisions.
 *
 * <p>Registered as an application-level service via {@code @Service(Service.Level.APP)}.
 * The platform manages instantiation and disposal; callers resolve the singleton through
 * {@link #getInstance()}, which prefers the platform-managed instance and falls back to
 * a lazily created instance for edge cases (early bootstrap / isolated unit tests).
 */
@Service(Service.Level.APP)
public final class CliCompatibilityService {

    private static final Logger LOG = Logger.getInstance(CliCompatibilityService.class);

    private final CliCompatibilityManifestRepository repository;
    private final CliVersionParserRegistry parserRegistry;
    private final AtomicReference<CliCompatibilityManifestSnapshot> snapshot;

    /**
     * Public no-arg constructor: required for platform {@code applicationService} registration.
     */
    public CliCompatibilityService() {
        this(new CliCompatibilityManifestRepository(), CliVersionParserRegistry.defaults());
    }

    CliCompatibilityService(
            CliCompatibilityManifestRepository repository,
            CliVersionParserRegistry parserRegistry) {
        this.repository = repository;
        this.parserRegistry = parserRegistry;
        this.snapshot = new AtomicReference<>(repository.load());
    }

    /**
     * Resolve the shared CliCompatibilityService. Prefers the platform-managed application
     * service (auto-disposed on plugin unload / IDE shutdown); falls back to a lazily created
     * instance when the application is not yet resolvable.
     */
    public static CliCompatibilityService getInstance() {
        try {
            CliCompatibilityService service =
                    ApplicationManager.getApplication().getService(CliCompatibilityService.class);
            if (service != null) {
                return service;
            }
        } catch (RuntimeException ignored) {
            // ApplicationManager unavailable (isolated tests / plugin bootstrap).
        }
        try {
            return Holder.INSTANCE;
        } catch (LinkageError e) {
            // Holder 的静态初始化只跑一次:失败(如 manifest 缺 provider 规则)后,后续
            // 每次访问都抛 NoClassDefFoundError —— 是 Error,穿透调用方 catch(Exception)
            // 造成无日志死亡。这里转成带根因的 RuntimeException,让 CLI 解析层能以
            // 「版本门禁不可用」的可见错误处理,而不是整条 send 链静默失败。
            throw new IllegalStateException(
                    "CLI compatibility service failed to initialize (bundled manifest invalid?): "
                            + e.getMessage(), e);
        }
    }

    public CliCompatibilityDecision evaluate(ProviderType provider, String rawVersion) {
        if (provider == null) {
            throw new IllegalArgumentException("Provider is required for CLI compatibility evaluation");
        }
        CliCompatibilityManifestSnapshot current = snapshot.get();
        CliCompatibilityManifest.ProviderRule rule = current.manifest().providers().get(provider.value());
        if (rule == null) {
            throw new IllegalStateException("CLI compatibility rule is missing for " + provider.value());
        }

        Optional<String> normalized = parserRegistry.parse(provider, rawVersion);
        if (normalized.isEmpty()) {
            return policyDecision(provider, rawVersion, null, rule.unknownVersionPolicy(),
                    CliCompatibilityStatus.UNKNOWN_ALLOWED, CliCompatibilityStatus.UNKNOWN_BLOCKED, current);
        }

        String version = normalized.get();
        if (isBlocked(version, rule.blockedVersions())) {
            return decision(provider, rawVersion, version, CliCompatibilityStatus.BLOCKED, false, true, current);
        }
        try {
            if (VersionComparator.compareVersions(version, rule.minimumSupported()) < 0) {
                return decision(provider, rawVersion, version, CliCompatibilityStatus.UNSUPPORTED, false, true, current);
            }
            if (VersionComparator.compareVersions(version, rule.maximumTested()) > 0) {
                return policyDecision(provider, rawVersion, version, rule.higherVersionPolicy(),
                        CliCompatibilityStatus.AHEAD_ALLOWED, CliCompatibilityStatus.AHEAD_BLOCKED, current);
            }
            return decision(provider, rawVersion, version, CliCompatibilityStatus.COMPATIBLE, true, false, current);
        } catch (NumberFormatException e) {
            return policyDecision(provider, rawVersion, version, rule.unknownVersionPolicy(),
                    CliCompatibilityStatus.UNKNOWN_ALLOWED, CliCompatibilityStatus.UNKNOWN_BLOCKED, current);
        }
    }

    public boolean isVersionAccepted(ProviderType provider, String rawVersion) {
        CliCompatibilityDecision result = evaluate(provider, rawVersion);
        if (!result.allowed() || result.warning()) {
            LOG.warn("CLI compatibility decision: provider=" + provider.value()
                    + ", rawVersion=" + rawVersion
                    + ", normalizedVersion=" + result.normalizedVersion()
                    + ", status=" + result.status()
                    + ", manifestRevision=" + result.manifestRevision()
                    + ", manifestSource=" + result.manifestSource());
        }
        return result.allowed();
    }

    /**
     * Evaluate an optional per-feature version gate (e.g. ACP channel support).
     * <p>
     * Unlike {@link #evaluate}, this is <b>fail-safe conservative</b>: when the feature rule is
     * absent (null) or the version cannot be parsed, it returns {@code false} (caller falls back
     * to the legacy path) <em>without throwing</em>. This is the deliberate inverse of
     * {@link #evaluate}'s rule-missing behavior — a missing feature gate must never crash the
     * caller (Holder clinit / NoClassDefFoundError lesson), it must silently disable the feature.
     * <p>
     * Only returns {@code true} when the provider's overall version is acceptable AND the feature
     * rule exists AND the version falls within [minimumSupported, maximumTested].
     */
    public boolean evaluateFeature(ProviderType provider, String rawVersion, String featureId) {
        if (provider == null || featureId == null || featureId.isBlank()) {
            return false;
        }
        try {
            CliCompatibilityManifestSnapshot current = snapshot.get();
            CliCompatibilityManifest.ProviderRule rule =
                    current.manifest().providers().get(provider.value());
            if (rule == null) {
                return false;
            }
            CliCompatibilityManifest.FeatureRule feature = rule.feature(featureId);
            if (feature == null) {
                return false;
            }
            Optional<String> normalized = parserRegistry.parse(provider, rawVersion);
            if (normalized.isEmpty()) {
                return false;
            }
            String version = normalized.get();
            if (VersionComparator.compareVersions(version, feature.minimumSupported()) < 0) {
                return false;
            }
            // upper bound: ACP is "tested up to" max; higher versions are cautiously allowed
            // (the CLI is backward-compatible within a major line; hard-block only below floor).
            return VersionComparator.compareVersions(version, feature.maximumTested()) <= 0
                    || rule.higherVersionPolicy() == CliVersionPolicy.WARN_ALLOW
                    || rule.higherVersionPolicy() == CliVersionPolicy.ALLOW;
        } catch (Exception e) {
            LOG.warn("evaluateFeature failed (feature=" + featureId
                    + ", provider=" + provider.value() + ", version=" + rawVersion + "): " + e.getMessage());
            return false;
        }
    }

    public CliCompatibilityManifestSnapshot refreshManifest() {
        CliCompatibilityManifestSnapshot refreshed = repository.refresh();
        snapshot.set(refreshed);
        return refreshed;
    }

    public CliCompatibilityManifestSnapshot currentManifest() {
        return snapshot.get();
    }

    private static boolean isBlocked(String version, List<String> blockedVersions) {
        for (String blocked : blockedVersions) {
            if (blocked.equals(version)) {
                return true;
            }
        }
        return false;
    }

    private static CliCompatibilityDecision policyDecision(
            ProviderType provider,
            String rawVersion,
            String normalizedVersion,
            CliVersionPolicy policy,
            CliCompatibilityStatus allowedStatus,
            CliCompatibilityStatus blockedStatus,
            CliCompatibilityManifestSnapshot snapshot) {
        if (policy == CliVersionPolicy.BLOCK) {
            return decision(provider, rawVersion, normalizedVersion, blockedStatus, false, true, snapshot);
        }
        return decision(provider, rawVersion, normalizedVersion, allowedStatus, true,
                policy == CliVersionPolicy.WARN_ALLOW, snapshot);
    }

    private static CliCompatibilityDecision decision(
            ProviderType provider,
            String rawVersion,
            String normalizedVersion,
            CliCompatibilityStatus status,
            boolean allowed,
            boolean warning,
            CliCompatibilityManifestSnapshot snapshot) {
        return new CliCompatibilityDecision(
                provider,
                rawVersion,
                normalizedVersion,
                status,
                allowed,
                warning,
                snapshot.manifest().revision(),
                snapshot.source());
    }

    /**
     * Fallback instance for edge cases where the platform service is not resolvable
     * (early bootstrap / isolated unit tests).
     */
    private static final class Holder {
        private static final CliCompatibilityService INSTANCE = new CliCompatibilityService(
                new CliCompatibilityManifestRepository(), CliVersionParserRegistry.defaults());

        private Holder() {
        }
    }
}
