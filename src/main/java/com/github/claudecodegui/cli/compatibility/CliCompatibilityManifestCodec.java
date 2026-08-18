package com.github.claudecodegui.cli.compatibility;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Parses and strictly validates the bundled or signed remote compatibility manifest. */
public final class CliCompatibilityManifestCodec {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final Pattern VERSION_BOUNDARY =
            Pattern.compile("^\\d+(?:\\.\\d+){1,3}(?:[-+][0-9A-Za-z.-]+)?$");

    private final Gson gson = new Gson();

    public CliCompatibilityManifest parse(byte[] jsonBytes) {
        if (jsonBytes == null || jsonBytes.length == 0) {
            throw new IllegalArgumentException("CLI compatibility manifest is empty");
        }
        try {
            CliCompatibilityManifest manifest = gson.fromJson(
                    new String(jsonBytes, StandardCharsets.UTF_8), CliCompatibilityManifest.class);
            return validate(manifest);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Invalid CLI compatibility manifest JSON", e);
        }
    }

    CliCompatibilityManifest validate(CliCompatibilityManifest manifest) {
        if (manifest == null) {
            throw new IllegalArgumentException("CLI compatibility manifest is null");
        }
        if (manifest.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported CLI compatibility schema: " + manifest.schemaVersion());
        }
        if (manifest.revision() <= 0) {
            throw new IllegalArgumentException("CLI compatibility manifest revision must be positive");
        }
        if (manifest.generatedAt() == null || manifest.generatedAt().isBlank()) {
            throw new IllegalArgumentException("CLI compatibility manifest generatedAt is missing");
        }
        if (manifest.providers() == null) {
            throw new IllegalArgumentException("CLI compatibility manifest providers are missing");
        }

        Map<String, CliCompatibilityManifest.ProviderRule> validated = new LinkedHashMap<>();
        for (ProviderType provider : ProviderType.values()) {
            CliCompatibilityManifest.ProviderRule rule = manifest.providers().get(provider.value());
            if (rule == null) {
                throw new IllegalArgumentException("Missing CLI compatibility rule for " + provider.value());
            }
            validated.put(provider.value(), validateRule(provider, rule));
        }
        if (manifest.providers().size() != validated.size()) {
            throw new IllegalArgumentException("CLI compatibility manifest contains unknown providers");
        }
        return new CliCompatibilityManifest(
                manifest.schemaVersion(),
                manifest.revision(),
                manifest.generatedAt(),
                Collections.unmodifiableMap(validated));
    }

    private CliCompatibilityManifest.ProviderRule validateRule(
            ProviderType provider,
            CliCompatibilityManifest.ProviderRule rule) {
        String minimum = validateBoundary(provider, "minimumSupported", rule.minimumSupported());
        String maximum = validateBoundary(provider, "maximumTested", rule.maximumTested());
        if (VersionComparator.compareVersions(minimum, maximum) > 0) {
            throw new IllegalArgumentException("minimumSupported exceeds maximumTested for " + provider.value());
        }
        if (rule.unknownVersionPolicy() == null || rule.higherVersionPolicy() == null) {
            throw new IllegalArgumentException("CLI compatibility policies are missing for " + provider.value());
        }

        List<String> blocked = new ArrayList<>();
        if (rule.blockedVersions() != null) {
            for (String version : rule.blockedVersions()) {
                blocked.add(validateBoundary(provider, "blockedVersions", version));
            }
        }
        return new CliCompatibilityManifest.ProviderRule(
                minimum,
                maximum,
                Collections.unmodifiableList(blocked),
                rule.unknownVersionPolicy(),
                rule.higherVersionPolicy());
    }

    private static String validateBoundary(ProviderType provider, String field, String version) {
        if (version == null || !VERSION_BOUNDARY.matcher(version).matches()) {
            throw new IllegalArgumentException("Invalid " + field + " for " + provider.value());
        }
        return version;
    }
}
