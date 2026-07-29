package com.github.claudecodegui.cli.compatibility;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Ed25519 detached-signature verifier with an embedded X.509 public key. */
public final class Ed25519ManifestSignatureVerifier implements ManifestSignatureVerifier {

    private final PublicKey publicKey;

    public Ed25519ManifestSignatureVerifier(String publicKeyBase64) {
        try {
            byte[] encodedKey = Base64.getDecoder().decode(publicKeyBase64);
            this.publicKey = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(encodedKey));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Ed25519 public key", e);
        }
    }

    @Override
    public boolean verify(byte[] manifestBytes, byte[] signatureText) {
        if (manifestBytes == null || signatureText == null) {
            return false;
        }
        try {
            byte[] signatureBytes = Base64.getDecoder().decode(
                    new String(signatureText, StandardCharsets.US_ASCII).trim());
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(manifestBytes);
            return verifier.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }
}
