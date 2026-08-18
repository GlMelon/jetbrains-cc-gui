package com.github.claudecodegui.cli.compatibility;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.intellij.openapi.diagnostic.Logger;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Backend facade for provider CLI version parsing and manifest-based compatibility decisions. */
public final class CliCompatibilityService {

    private static final Logger LOG = Logger.getInstance(CliCompatibilityService.class);

    private final CliCompatibilityManifestRepository repository;
    private final CliVersionParserRegistry parserRegistry;
    private final AtomicReference<CliCompatibilityManifestSnapshot> snapshot;

    public CliCompatibilityService(
            CliCompatibilityManifestRepository repository,
            CliVersionParserRegistry parserRegistry) {
        this.repository = repository;
        this.parserRegistry = parserRegistry;
        this.snapshot = new AtomicReference<>(repository.load());
    }

    public static CliCompatibilityService getInstance() {
        return Holder.INSTANCE;
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

    private static final class Holder {
        private static final CliCompatibilityService INSTANCE = new CliCompatibilityService(
                new CliCompatibilityManifestRepository(), CliVersionParserRegistry.defaults());

        private Holder() {
        }
    }
}
