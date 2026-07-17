package com.github.claudecodegui.settings.credentials;

import com.github.claudecodegui.settings.credentials.CredentialBackend.Availability;
import com.github.claudecodegui.settings.credentials.PasswordStore.CredentialStoreUnavailableException;
import com.github.claudecodegui.settings.credentials.PasswordStore.CredentialTooLargeException;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * S2 PasswordStore 故障注入测试矩阵(docs/comprehensive-optimization-directions.md §S2)。
 * <p>
 * 注入 InMemoryCredentialBackend(fake),覆盖:正常往返 / null-空清除 / 容量边界(超限 fail-fast +
 * 恰好等于上限 + 多字节 UTF-8 计量)/ 显式降级(headless + disabled)/ 读路径不抛降级 / credential key
 * 规范 / 降级与超限不脏写后端。
 */
public class PasswordStoreTest {

    private static final String KEY = "codemoss.smithery.apiKey";

    private InMemoryCredentialBackend backend;
    private PasswordStore store;

    @Before
    public void setUp() {
        backend = new InMemoryCredentialBackend();
        store = new PasswordStore(backend);
    }

    // ---- 正常往返 ----

    @Test
    public void storeThenLoadRoundtrips() {
        store.storePassword(KEY, "sk-secret-123");
        assertEquals("sk-secret-123", store.loadPassword(KEY));
    }

    @Test
    public void loadMissingReturnsNull() {
        assertNull(store.loadPassword(KEY));
    }

    @Test
    public void storeOverwritesExistingValue() {
        store.storePassword(KEY, "v1");
        store.storePassword(KEY, "v2");
        assertEquals("v2", store.loadPassword(KEY));
    }

    // ---- null / 空 = 清除 ----

    @Test
    public void storeNullRemoves() {
        store.storePassword(KEY, "v");
        store.storePassword(KEY, null);
        assertNull(store.loadPassword(KEY));
    }

    @Test
    public void storeEmptyRemoves() {
        store.storePassword(KEY, "v");
        store.storePassword(KEY, "");
        assertNull(store.loadPassword(KEY));
    }

    @Test
    public void removePasswordIsIdempotent() {
        store.removePassword(KEY); // 不存在,不抛
        store.storePassword(KEY, "v");
        store.removePassword(KEY);
        assertNull(store.loadPassword(KEY));
        store.removePassword(KEY); // 再次删,no-op,不抛
    }

    // ---- 容量边界(§S2:PasswordSafe 单值大小上限,OAuth refresh token / 大 JSON 需 fail-fast) ----

    @Test
    public void oversizedCredentialThrowsTooLarge() {
        String oversized = repeat('x', PasswordStore.MAX_CREDENTIAL_BYTES + 1);
        try {
            store.storePassword(KEY, oversized);
            fail("expected CredentialTooLargeException");
        } catch (CredentialTooLargeException e) {
            assertEquals(KEY, e.getCredentialKey());
            assertEquals(PasswordStore.MAX_CREDENTIAL_BYTES, e.getLimitBytes());
            assertTrue("actual bytes should exceed limit", e.getActualBytes() > PasswordStore.MAX_CREDENTIAL_BYTES);
        }
    }

    @Test
    public void credentialAtExactLimitIsAccepted() {
        // 恰好 == MAX_CREDENTIAL_BYTES 的 ASCII(UTF-8 字节数 == 字符数)。
        String atLimit = repeat('x', PasswordStore.MAX_CREDENTIAL_BYTES);
        store.storePassword(KEY, atLimit);
        String loaded = store.loadPassword(KEY);
        assertEquals(PasswordStore.MAX_CREDENTIAL_BYTES, loaded.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    public void multibyteCredentialCountedByUtf8Bytes() {
        // 每个中文 3 UTF-8 字节:MAX/3 个字符不超,(MAX/3)+1 必超(计字节非字符)。
        int safeCount = PasswordStore.MAX_CREDENTIAL_BYTES / 3;
        String oversized = repeat('密', safeCount + 1);
        try {
            store.storePassword(KEY, oversized);
            fail("expected CredentialTooLargeException (multibyte counted by UTF-8 bytes)");
        } catch (CredentialTooLargeException e) {
            assertTrue("multibyte byte count should exceed limit",
                    e.getActualBytes() > PasswordStore.MAX_CREDENTIAL_BYTES);
        }
    }

    // ---- 显式降级(§S2:headless / disabled 不静默走不安全存储) ----

    @Test
    public void storeWhenHeadlessUnavailableThrows() {
        backend.setAvailability(Availability.HEADLESS_NO_BACKEND);
        try {
            store.storePassword(KEY, "v");
            fail("expected CredentialStoreUnavailableException");
        } catch (CredentialStoreUnavailableException e) {
            assertEquals(KEY, e.getCredentialKey());
            assertEquals(Availability.HEADLESS_NO_BACKEND, e.getAvailability());
        }
    }

    @Test
    public void storeWhenDisabledThrows() {
        backend.setAvailability(Availability.DISABLED);
        try {
            store.storePassword(KEY, "v");
            fail("expected CredentialStoreUnavailableException");
        } catch (CredentialStoreUnavailableException expected) {
            assertEquals(Availability.DISABLED, expected.getAvailability());
        }
    }

    @Test
    public void loadWhenUnavailableReturnsNullDoesNotThrow() {
        // 读路径不抛降级异常——缺失 key 等价"未配置",不应阻塞调用方。
        store.storePassword(KEY, "v"); // 先在 AVAILABLE 下存
        backend.setAvailability(Availability.HEADLESS_NO_BACKEND);
        assertNull(store.loadPassword(KEY));
    }

    @Test
    public void getAvailabilityPassthrough() {
        assertEquals(Availability.AVAILABLE, store.getAvailability());
        backend.setAvailability(Availability.DISABLED);
        assertEquals(Availability.DISABLED, store.getAvailability());
    }

    // ---- credential key 规范(防与第三方插件条目冲突 + 脱敏扫描) ----

    @Test
    public void keyWithoutPrefixRejected() {
        try {
            store.storePassword("smithery.apiKey", "v"); // 缺 codemoss. 前缀
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue("message should mention prefix: " + e.getMessage(),
                    e.getMessage().contains(PasswordStore.CREDENTIAL_KEY_PREFIX));
        }
    }

    @Test
    public void nullKeyRejected() {
        try {
            store.loadPassword(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ---- 降级 / 超限不脏写后端(store 在校验失败时不应调 backend.store) ----

    @Test
    public void storeWhenUnavailableDoesNotReachBackend() {
        backend.setAvailability(Availability.HEADLESS_NO_BACKEND);
        try {
            store.storePassword(KEY, "v");
            fail("expected unavailable");
        } catch (CredentialStoreUnavailableException expected) {
            backend.setAvailability(Availability.AVAILABLE);
            assertNull("backend must not be dirtied by failed store", store.loadPassword(KEY));
        }
    }

    @Test
    public void oversizedCredentialDoesNotReachBackend() {
        String oversized = repeat('x', PasswordStore.MAX_CREDENTIAL_BYTES + 1);
        try {
            store.storePassword(KEY, oversized);
            fail("expected too large");
        } catch (CredentialTooLargeException expected) {
            assertNull("backend must not be dirtied by oversized credential", backend.load(KEY));
        }
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
