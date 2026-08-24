package com.github.claudecodegui.util;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 hashing helpers. Single home for digest computation and hex/Base64
 * encoding so call sites do not re-implement the byte-to-hex loop.
 */
public final class HashingUtil {

    private HashingUtil() {
        // Utility class, do not instantiate
    }

    /** Raw SHA-256 digest; callers pick their own encoding (hex, Base64, ...). */
    public static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JVM specification; unreachable in practice.
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    public static byte[] sha256(String input) {
        return sha256(input.getBytes(StandardCharsets.UTF_8));
    }

    /** SHA-256 of the UTF-8 bytes, lowercase hex encoded. */
    public static String sha256Hex(String input) {
        return HexFormat.of().formatHex(sha256(input));
    }

    /** SHA-256 digest, lowercase hex encoded. */
    public static String sha256Hex(byte[] input) {
        return HexFormat.of().formatHex(sha256(input));
    }

    /** Streamed SHA-256 of a file, lowercase hex encoded. */
    public static String sha256Hex(File file) throws IOException {
        try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
            return sha256Hex(is);
        }
    }

    /** Streamed SHA-256 of a file, lowercase hex encoded. */
    public static String sha256Hex(Path file) throws IOException {
        try (InputStream is = Files.newInputStream(file)) {
            return sha256Hex(is);
        }
    }

    /** Streamed SHA-256, lowercase hex encoded. */
    public static String sha256Hex(InputStream in) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
        byte[] buffer = new byte[8192];
        int len;
        while ((len = in.read(buffer)) != -1) {
            digest.update(buffer, 0, len);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
