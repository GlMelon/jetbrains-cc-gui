package com.github.claudecodegui.cli.compatibility;

import com.github.claudecodegui.session.runtime.ProviderType;
import org.junit.Test;

import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class CliCompatibilityManifestContractTest {

    @Test
    public void bundledManifestCoversEveryProviderAndUsesCurrentSchema() throws Exception {
        byte[] json;
        try (InputStream stream = getClass().getResourceAsStream(
                "/compatibility/cli-compatibility-manifest.json")) {
            assertNotNull(stream);
            json = stream.readAllBytes();
        }
        CliCompatibilityManifest manifest = new CliCompatibilityManifestCodec().parse(json);

        assertEquals(CliCompatibilityManifestCodec.SUPPORTED_SCHEMA_VERSION, manifest.schemaVersion());
        assertEquals(ProviderType.values().length, manifest.providers().size());
        for (ProviderType provider : ProviderType.values()) {
            assertNotNull(manifest.providers().get(provider.value()));
        }
    }

    @Test
    public void bundledManifestHasValidDetachedSignature() throws Exception {
        byte[] manifest;
        byte[] signature;
        try (InputStream manifestStream = getClass().getResourceAsStream(
                "/compatibility/cli-compatibility-manifest.json");
             InputStream signatureStream = getClass().getResourceAsStream(
                     "/compatibility/cli-compatibility-manifest.sig")) {
            assertNotNull(manifestStream);
            assertNotNull(signatureStream);
            manifest = manifestStream.readAllBytes();
            signature = signatureStream.readAllBytes();
        }

        Ed25519ManifestSignatureVerifier verifier = new Ed25519ManifestSignatureVerifier(
                CliCompatibilityManifestRepository.PUBLIC_KEY_BASE64);
        org.junit.Assert.assertTrue(verifier.verify(manifest, signature));
    }
}
