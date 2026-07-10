package com.github.claudecodegui.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * {@link CredentialMasker#maskApiKey} 单元测试(安全关键:确保不回传明文)。
 * <p>覆盖空/null、短 key(全掩码)、长 key(前2后4)、长度边界(8 vs 9)。
 */
public class CredentialMaskerTest {

    @Test
    public void emptyOrNullReturnsEmpty() {
        assertEquals("", CredentialMasker.maskApiKey(""));
        assertEquals("", CredentialMasker.maskApiKey(null));
    }

    @Test
    public void shortKeyFullyMasked() {
        assertEquals("••••", CredentialMasker.maskApiKey("abc"));
        assertEquals("••••", CredentialMasker.maskApiKey("12345678"));
    }

    @Test
    public void longKeyShowsHeadAndTailOnly() {
        // 长度 20:前2 "sk" + •••• + 后4 "7890"
        assertEquals("sk••••7890", CredentialMasker.maskApiKey("sk-abcdef1234567890"));
    }

    @Test
    public void boundaryLength9ShowsHeadAndTail() {
        // 长度 9(>8 走长分支):前2 "12" + •••• + 后4 "6789"
        assertEquals("12••••6789", CredentialMasker.maskApiKey("123456789"));
    }

    @Test
    public void maskedNeverContainsFullKey() {
        String key = "sk-super-secret-key-12345";
        String masked = CredentialMasker.maskApiKey(key);
        assertFalse("masked must not contain the full key", masked.contains(key));
        assertFalse("masked must not contain middle secret", masked.contains("super-secret"));
    }
}
