package com.github.claudecodegui.cli.compatibility;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Ed25519ManifestSignatureVerifierTest {

    @Test
    public void verifiesDetachedSignatureAndRejectsTampering() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] manifest = "manifest".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(manifest);
        byte[] signatureText = Base64.getEncoder().encode(signer.sign());
        Ed25519ManifestSignatureVerifier verifier = new Ed25519ManifestSignatureVerifier(
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));

        assertTrue(verifier.verify(manifest, signatureText));
        assertFalse(verifier.verify("tampered".getBytes(StandardCharsets.UTF_8), signatureText));
        assertFalse(verifier.verify(manifest, "invalid".getBytes(StandardCharsets.US_ASCII)));
    }
}
