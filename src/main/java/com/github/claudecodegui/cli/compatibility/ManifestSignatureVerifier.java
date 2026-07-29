package com.github.claudecodegui.cli.compatibility;

/** Verifies a detached signature for raw manifest bytes. */
@FunctionalInterface
public interface ManifestSignatureVerifier {

    boolean verify(byte[] manifestBytes, byte[] signatureText);
}
