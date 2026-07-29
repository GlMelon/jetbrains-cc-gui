package com.github.claudecodegui.settings;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * ProviderSettingsService 领域委托 + 内联逻辑测试(A3 领域拆分第六步,docs §A3)。
 *
 * <p>ProviderSettingsService 是「持有并构造三 {@link ProviderManager} 的领域入口」+ 收口 6 个
 * localConfigAuthorized/runtimeAccessMode 内联方法。本测试聚焦<b>6 个内联方法的 config.json
 * 读写语义</b>(对称①~④的内联逻辑下沉)+ <b>委托链不漂移</b> + <b>CSS Facade 转发</b>,不重复
 * 三 ProviderManager 内部 CRUD 矩阵(由各 Manager 自身测试守门)。
 *
 * <p><b>夹具隔离</b>:反射注入 {@code PlatformUtils.cachedRealHomeDir} 指向隔离临时 home(参照
 * {@link ModelRegistrySettingsServiceTest} / {@link McpSettingsServiceTest})。该字段是 home SSOT,
 * config.json 读写经 CSS readConfig/writeConfig → ConfigRepository 全部落隔离临时 home,绝不碰真实环境。
 * {@code isolationCanaryAndDelegationNoThrow} 兼隔离 canary —— 若隔离失效读到真实 config 的
 * localConfigAuthorized,该用例会 fail 而非静默污染。
 *
 * <p><b>不测 {@code isCodexCliLoginAvailable}/{@code readCodexCliLoginAccountInfo}</b>:它们经
 * {@link CodexSettingsManager} 读 codex 原生 {@code ~/.codex} 文件(§F9 边界),本测试聚焦插件自有
 * config.json 段;这两个方法的 try-catch + 委托语义由 CSS Facade + 编译期签名保证。
 */
public class ProviderSettingsServiceTest {
    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    // ==================== 隔离 canary + 委托链(三 ProviderManager)====================

    @Test
    public void isolationCanaryAndDelegationNoThrow() throws Exception {
        useTemporaryHome(Files.createTempDirectory("prov-svc-canary-home"));
        ProviderSettingsService svc = newProviderSettingsService(new CodemossSettingsService());

        // 隔离 canary:临时 home 无 localConfigAuthorized → false(若读真实环境授权态会 true,需立即中止)。
        assertFalse(svc.isCodexLocalConfigAuthorized());
        // 委托链:三 Manager 经 reader 读 createDefaultConfig 骨架,不抛、返回非 null。
        assertNotNull(svc.getClaudeProviders());
        assertNotNull(svc.getCodexProviders());
        assertNotNull(svc.getOpenCodeProviders());
    }

    // ==================== codex localConfigAuthorized 内联(经 CSS config.json)====================

    @Test
    public void codexLocalConfigAuthorizedRoundTrip() throws Exception {
        useTemporaryHome(Files.createTempDirectory("prov-svc-codex-rt-home"));
        ProviderSettingsService svc = newProviderSettingsService(new CodemossSettingsService());

        assertFalse(svc.isCodexLocalConfigAuthorized());
        svc.setCodexLocalConfigAuthorized(true);
        assertTrue(svc.isCodexLocalConfigAuthorized());
        svc.setCodexLocalConfigAuthorized(false);
        assertFalse(svc.isCodexLocalConfigAuthorized());
    }

    @Test
    public void setCodexLocalConfigAuthorizedCreatesSegmentSkeleton() throws Exception {
        useTemporaryHome(Files.createTempDirectory("prov-svc-codex-skel-home"));
        CodemossSettingsService css = new CodemossSettingsService();
        // 清空 codex 段,验证 set 内部创建 providers + current + localConfigAuthorized 骨架(逐字迁移语义)。
        JsonObject config = css.readConfig();
        config.remove(ProviderType.CODEX.value());
        css.writeConfig(config);

        ProviderSettingsService svc = newProviderSettingsService(css);
        svc.setCodexLocalConfigAuthorized(true);
        assertTrue(svc.isCodexLocalConfigAuthorized());
        // 骨架创建后 getCodexProviders 不抛(providers 段存在)。
        assertNotNull(svc.getCodexProviders());
    }

    // ==================== codex runtimeAccessMode 内联 ====================

    @Test
    public void codexRuntimeAccessModeInactiveWhenNoCodexSegment() throws Exception {
        useTemporaryHome(Files.createTempDirectory("prov-svc-codex-inactive-home"));
        CodemossSettingsService css = new CodemossSettingsService();
        JsonObject config = css.readConfig();
        config.remove(ProviderType.CODEX.value());
        css.writeConfig(config);

        ProviderSettingsService svc = newProviderSettingsService(css);
        assertEquals(CodemossSettingsService.CODEX_RUNTIME_ACCESS_INACTIVE, svc.getCodexRuntimeAccessMode());
    }

    @Test
    public void codexRuntimeAccessModeManagedForActiveProvider() throws Exception {
        useTemporaryHome(Files.createTempDirectory("prov-svc-codex-managed-home"));
        CodemossSettingsService css = new CodemossSettingsService();
        JsonObject config = css.readConfig();
        JsonObject codex = new JsonObject();
        codex.addProperty("current", "managed-id");
        JsonObject providers = new JsonObject();
        providers.add("managed-id", new JsonObject());
        codex.add("providers", providers);
        config.add(ProviderType.CODEX.value(), codex);
        css.writeConfig(config);

        ProviderSettingsService svc = newProviderSettingsService(css);
        assertEquals(CodemossSettingsService.CODEX_RUNTIME_ACCESS_MANAGED, svc.getCodexRuntimeAccessMode());
    }

    @Test
    public void codexRuntimeAccessModeCliLoginWhenAuthorized() throws Exception {
        useTemporaryHome(Files.createTempDirectory("prov-svc-codex-cli-auth-home"));
        CodemossSettingsService css = new CodemossSettingsService();
        ProviderSettingsService svc = newProviderSettingsService(css);
        svc.setCodexLocalConfigAuthorized(true);

        JsonObject config = css.readConfig();
        config.getAsJsonObject(ProviderType.CODEX.value())
                .addProperty("current", CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID);
        css.writeConfig(config);

        assertEquals(CodemossSettingsService.CODEX_RUNTIME_ACCESS_CLI_LOGIN, svc.getCodexRuntimeAccessMode());
    }

    @Test
    public void codexRuntimeAccessModeInactiveForCliLoginWhenNotAuthorized() throws Exception {
        useTemporaryHome(Files.createTempDirectory("prov-svc-codex-cli-noauth-home"));
        CodemossSettingsService css = new CodemossSettingsService();
        JsonObject config = css.readConfig();
        config.getAsJsonObject(ProviderType.CODEX.value())
                .addProperty("current", CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID);
        css.writeConfig(config);

        ProviderSettingsService svc = newProviderSettingsService(css);
        assertEquals(CodemossSettingsService.CODEX_RUNTIME_ACCESS_INACTIVE, svc.getCodexRuntimeAccessMode());
    }

    // ==================== opencode 对称内联 ====================

    @Test
    public void opencodeLocalConfigAuthorizedRoundTrip() throws Exception {
        useTemporaryHome(Files.createTempDirectory("prov-svc-opencode-rt-home"));
        ProviderSettingsService svc = newProviderSettingsService(new CodemossSettingsService());

        assertFalse(svc.isOpencodeLocalConfigAuthorized());
        svc.setOpencodeLocalConfigAuthorized(true);
        assertTrue(svc.isOpencodeLocalConfigAuthorized());
    }

    @Test
    public void opencodeRuntimeAccessModeManagedForActiveProvider() throws Exception {
        useTemporaryHome(Files.createTempDirectory("prov-svc-opencode-managed-home"));
        CodemossSettingsService css = new CodemossSettingsService();
        JsonObject config = css.readConfig();
        JsonObject opencode = new JsonObject();
        opencode.addProperty("current", "oc-managed-id");
        JsonObject providers = new JsonObject();
        providers.add("oc-managed-id", new JsonObject());
        opencode.add("providers", providers);
        config.add(ProviderType.OPENCODE.value(), opencode);
        css.writeConfig(config);

        ProviderSettingsService svc = newProviderSettingsService(css);
        assertEquals(CodemossSettingsService.CODEX_RUNTIME_ACCESS_MANAGED, svc.getOpenCodeRuntimeAccessMode());
    }

    @Test
    public void opencodeRuntimeAccessModeInactiveWhenNoSegment() throws Exception {
        useTemporaryHome(Files.createTempDirectory("prov-svc-opencode-inactive-home"));
        CodemossSettingsService css = new CodemossSettingsService();
        JsonObject config = css.readConfig();
        config.remove(ProviderType.OPENCODE.value());
        css.writeConfig(config);

        ProviderSettingsService svc = newProviderSettingsService(css);
        assertEquals(CodemossSettingsService.CODEX_RUNTIME_ACCESS_INACTIVE, svc.getOpenCodeRuntimeAccessMode());
    }

    // ==================== CSS Facade 转发 ====================

    @Test
    public void delegationViaCssFacade() throws Exception {
        useTemporaryHome(Files.createTempDirectory("prov-svc-css-home"));
        CodemossSettingsService css = new CodemossSettingsService();

        assertFalse(css.isCodexLocalConfigAuthorized());
        css.setCodexLocalConfigAuthorized(true);
        assertTrue(css.isCodexLocalConfigAuthorized());
        // 默认骨架(current 空)→ inactive,经 CSS Facade 转发与直调 Service 一致。
        assertEquals(CodemossSettingsService.CODEX_RUNTIME_ACCESS_INACTIVE, css.getCodexRuntimeAccessMode());
    }

    // ==================== helpers ====================

    private ProviderSettingsService newProviderSettingsService(CodemossSettingsService css) {
        Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
        ConfigPathManager pathManager = new ConfigPathManager();
        ClaudeSettingsManager claudeSettingsManager = new ClaudeSettingsManager(gson, pathManager);
        CodexSettingsManager codexSettingsManager = new CodexSettingsManager(gson);
        OpenCodeSettingsManager openCodeSettingsManager = new OpenCodeSettingsManager(gson);
        return new ProviderSettingsService(
                SettingsTestConfig.create().configStore(),
                gson,
                pathManager,
                claudeSettingsManager,
                codexSettingsManager,
                openCodeSettingsManager);
    }

    private void useTemporaryHome(Path tempHome) throws Exception {
        if (originalHomeDir == null) {
            originalHomeDir = getCachedHomeDirectory();
        }
        setCachedHomeDirectory(tempHome.toString());
        Files.createDirectories(tempHome.resolve(".codemoss"));
    }

    private String getCachedHomeDirectory() throws Exception {
        Field field = PlatformUtils.class.getDeclaredField("cachedRealHomeDir");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private void setCachedHomeDirectory(String homeDir) throws Exception {
        Field field = PlatformUtils.class.getDeclaredField("cachedRealHomeDir");
        field.setAccessible(true);
        field.set(null, homeDir);
    }
}
