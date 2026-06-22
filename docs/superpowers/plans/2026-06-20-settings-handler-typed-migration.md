# Settings Handler Typed Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `SettingsHandler` 承载的 model registry(4 个 action)与 appearance(`SET_APPEARANCE_CONFIG`)业务迁移到 typed `FrontendActionHandler` + 后端 service,启用 `FrontendActionDispatcher` 作为主入口(typed 优先 + legacy fallback),并真正启用 `LegacyMessageHandlerAdapter` 承载瘦身后的 SettingsHandler。

**Architecture:**
- 新增 `settings/` 包下的 service(`ModelRegistryService` / `AppearanceConfigService`)与 result DTO,封装 read/write/reset/schema 业务,不依赖前端、不依赖 action 字符串。
- 新增 `handler/settings/` 包下的 5 个 typed `FrontendActionHandler`,只做"调 service → 派发 DownstreamEvent"编排。
- `SettingsHandler` 删除已迁移的 5 个 switch case、对应 `getSupportedTypes` 项与私有方法,瘦身为"只剩未迁移 action 的 legacy handler"。
- `ChatWindowDelegate` 构造 `FrontendActionDispatcher`(5 个 typed handler + `LegacyMessageHandlerAdapter.from(new SettingsHandler(ctx))`),经 `DelegateHost.setFrontendActionDispatcher` 注入;SettingsHandler 不再单独注册到 `MessageDispatcher`。
- `ClaudeChatWindow:724` 主入口改为"先 typed 后 legacy"两段 dispatch。
- 前端 2 处裸字符串(`useCodexProviderManagement`、`useSettingsThemeSync`)切到 `sendAction(UPSTREAM.*)`。

**Tech Stack:** Java (Gradle, JUnit 4);TypeScript / React (vitest)。后端 action/event 的 SSOT 是 `UpstreamAction` / `DownstreamEvent` 枚举,前端 `webview/src/generated/protocol.ts` 已含全部目标常量(无需 codegen)。

参考设计文档:`docs/designs/plugin-architecture-refactor-next-iteration.md`。

---

## 现状与关键决策(执行前必读)

**已就绪(无需新建):**
- `handler/core/FrontendActionHandler<T>` 接口(`action()` / `payloadType()` / `handle(T, FrontendActionContext)`)。
- `handler/core/FrontendActionDispatcher`(按 `action().value()` 查表,构造期重复检测抛 `IllegalArgumentException`)。
- `handler/core/FrontendActionContext`(薄包装 `HandlerContext`,`handlerContext()` getter)。
- `handler/core/LegacyMessageHandlerAdapter.from(MessageHandler)`(把 legacy handler 每个 supportedType 包成 String-payload typed handler,**目前零生产引用**)。
- `protocol/UpstreamAction` / `protocol/DownstreamEvent` 枚举;`ProtocolEnumCoverageTest`。
- 后端 model registry 持久化/校验逻辑已在 `CodemossSettingsService`(`getModelRegistry` / `setModelRegistry` / `resetModelRegistry` / `getModelRegistryJson`),且有 `CodemossSettingsServiceModelRegistryTest`。
- 前端 `sendAction` / `subscribeEvent` / `UPSTREAM` / `DOWNSTREAM` 常量;`ModelRegistrySection` 主路径已类型化。

**接驳形态(已与 owner 确认 = 形态 B):** `FrontendActionDispatcher` 成为顶层主入口,`ClaudeChatWindow:724` 先 typed 后 legacy;瘦身后的 `SettingsHandler` 经 `LegacyMessageHandlerAdapter` 接入 typed dispatcher。

**关键安全前提(已验证):** `SettingsHandler.getSupportedTypes()` 全部 72 个 type 均在 `UpstreamAction` 枚举内(瘦身后剩余 67 个同样全覆盖),因此 `LegacyMessageHandlerAdapter.from()` 不会跳过任何 type,无 action 失效风险。Task 4 会以测试固化这一不变量。

**功能等价约束:** 每个 typed handler 派发的 downstream event 的 type 与 payload 结构必须与原 `SettingsHandler` 私有方法逐字等价(见各 Task 的"原行为对照")。

---

## File Structure

**新增(Java main):**
- `src/main/java/com/github/claudecodegui/settings/ModelRegistryResult.java` — model registry 操作结果(success / reset / registry Json / errors)。
- `src/main/java/com/github/claudecodegui/settings/ModelRegistrySchemaResult.java` — schema 结果。
- `src/main/java/com/github/claudecodegui/settings/ModelRegistryService.java` — serialize/parse/get/set/reset/schema(从 SettingsHandler 搬运 + 委托 CodemossSettingsService)。
- `src/main/java/com/github/claudecodegui/settings/AppearanceConfigResult.java` — appearance 操作结果(configJson)。
- `src/main/java/com/github/claudecodegui/settings/AppearanceConfigService.java` — set + 回读 configJson。
- `src/main/java/com/github/claudecodegui/handler/settings/ModelRegistryEvents.java` — package-private 工具,组装 `model_registry_updated` response 并 dispatch(DRY)。
- `src/main/java/com/github/claudecodegui/handler/settings/GetModelRegistryActionHandler.java`
- `src/main/java/com/github/claudecodegui/handler/settings/SetModelRegistryActionHandler.java`
- `src/main/java/com/github/claudecodegui/handler/settings/ResetModelRegistryActionHandler.java`
- `src/main/java/com/github/claudecodegui/handler/settings/GetModelRegistrySchemaActionHandler.java`
- `src/main/java/com/github/claudecodegui/handler/settings/SetAppearanceConfigActionHandler.java`

**新增(Java test):**
- `src/test/java/com/github/claudecodegui/settings/ModelRegistryServiceTest.java`
- `src/test/java/com/github/claudecodegui/handler/settings/SettingsHandlerTestFixtures.java` — 共享 test helper(tmp home 反射 + recording FrontendActionContext)。
- `src/test/java/com/github/claudecodegui/handler/settings/ModelRegistryActionHandlerTest.java`
- `src/test/java/com/github/claudecodegui/handler/settings/AppearanceConfigActionHandlerTest.java`
- `src/test/java/com/github/claudecodegui/handler/settings/SettingsHandlerTypedWiringTest.java` — 接驳不变量(typed 命中、adapter 承载未迁移、supportedTypes 全在枚举)。

**修改(Java main):**
- `handler/SettingsHandler.java` — 删除 5 个 case + 5 个 supportedType + 相关私有方法 + 失效 import。
- `ui/ChatWindowDelegate.java` — 构造并注入 `FrontendActionDispatcher`;`DelegateHost` 接口加 `setFrontendActionDispatcher`。
- `ui/toolwindow/ClaudeChatWindow.java` — 加字段/setter;`:724` 改两段 dispatch。

**修改(前端):**
- `webview/src/components/settings/hooks/useCodexProviderManagement.ts` — `set_model_registry` 裸字符串 → `sendAction(UPSTREAM.SET_MODEL_REGISTRY, ...)`。
- `webview/src/components/settings/hooks/useSettingsThemeSync.ts` — `set_appearance_config` 裸字符串 → `sendAction(UPSTREAM.SET_APPEARANCE_CONFIG, ...)`。

---

## Task 1: 抽出 ModelRegistryService + result DTO

**Files:**
- Create: `src/main/java/com/github/claudecodegui/settings/ModelRegistryResult.java`
- Create: `src/main/java/com/github/claudecodegui/settings/ModelRegistrySchemaResult.java`
- Create: `src/main/java/com/github/claudecodegui/settings/ModelRegistryService.java`
- Test: `src/test/java/com/github/claudecodegui/settings/ModelRegistryServiceTest.java`

> 说明:`CodemossSettingsService` 不改动 —— service 只是把 `SettingsHandler` 里散落的 serialize/parse/schema 组装搬出来,持久化/校验仍委托给 `CodemossSettingsService`。

- [ ] **Step 1: 写 `ModelRegistryResult`**

`src/main/java/com/github/claudecodegui/settings/ModelRegistryResult.java`:

```java
package com.github.claudecodegui.settings;

import com.google.gson.JsonObject;
import java.util.List;

/**
 * Result of a model registry operation (get / set / reset).
 * Carries the serialized registry payload ({@code {items:[...]}}) on success,
 * or error messages on failure. Handlers translate this into downstream events.
 */
public final class ModelRegistryResult {
    private final boolean success;
    private final boolean reset;
    private final JsonObject registry;
    private final List<String> errors;

    private ModelRegistryResult(boolean success, boolean reset, JsonObject registry, List<String> errors) {
        this.success = success;
        this.reset = reset;
        this.registry = registry;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static ModelRegistryResult success(JsonObject registry) {
        return new ModelRegistryResult(true, false, registry, List.of());
    }

    public static ModelRegistryResult resetSuccess(JsonObject registry) {
        return new ModelRegistryResult(true, true, registry, List.of());
    }

    public static ModelRegistryResult failure(String error) {
        return new ModelRegistryResult(false, false, null, List.of(error));
    }

    public static ModelRegistryResult failure(List<String> errors) {
        return new ModelRegistryResult(false, false, null, errors);
    }

    public boolean success() { return success; }
    public boolean reset() { return reset; }
    public JsonObject registry() { return registry; }
    public List<String> errors() { return errors; }
}
```

- [ ] **Step 2: 写 `ModelRegistrySchemaResult`**

`src/main/java/com/github/claudecodegui/settings/ModelRegistrySchemaResult.java`:

```java
package com.github.claudecodegui.settings;

import com.google.gson.JsonObject;

/**
 * Schema description for the model registry form. Mirrors the previous
 * hardcoded schema emitted by SettingsHandler.handleGetModelRegistrySchema.
 */
public final class ModelRegistrySchemaResult {
    private final JsonObject schema;

    public ModelRegistrySchemaResult(JsonObject schema) {
        this.schema = schema;
    }

    public static ModelRegistrySchemaResult defaultSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("title", "模型配置中心");
        schema.addProperty("description", "配置 Claude/Codex 可选模型、上下文窗口与 1M 能力。错误配置会被后端拒绝。");
        schema.addProperty("providers", "claude, codex");
        schema.addProperty("contextWindow", "8192 到 2000000 的整数 tokens");
        schema.addProperty("supports1MContext", "为 true 时 contextWindow 必须 >= 1000000");
        return new ModelRegistrySchemaResult(schema);
    }

    public JsonObject schema() { return schema; }
}
```

- [ ] **Step 3: 写失败测试(serialize/parse 往返)**

`src/test/java/com/github/claudecodegui/settings/ModelRegistryServiceTest.java`:

```java
package com.github.claudecodegui.settings;

import com.github.claudecodegui.config.ModelConfig;
import com.github.claudecodegui.config.ModelRegistryConfig;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModelRegistryServiceTest {
    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    @Test
    public void serializeAndParseRoundTrip() {
        ModelRegistryConfig config = new ModelRegistryConfig(List.of(
                new ModelConfig("mimo-v2.5", "claude", "sonnet", "MiMo V2.5", "mimo-v2.5",
                        "", 1_000_000, true, true)
        ));
        JsonObject serialized = ModelRegistryService.serialize(config);

        assertEquals(1, serialized.getAsJsonArray("items").size());

        ModelRegistryConfig parsed = ModelRegistryService.parse(serialized);
        assertEquals("mimo-v2.5", parsed.models().get(0).id());
        assertEquals(1_000_000, parsed.models().get(0).contextWindow());
        assertTrue(parsed.models().get(0).supports1MContext());
    }

    @Test
    public void parseNullActualModelToleratedAsEmpty() {
        JsonObject item = JsonParser.parseString(
                "{\"id\":\"x\",\"provider\":\"claude\",\"role\":\"sonnet\","
                        + "\"label\":\"X\",\"actualModel\":null,\"description\":\"\","
                        + "\"contextWindow\":200000,\"supports1MContext\":false,\"enabled\":true}"
        ).getAsJsonObject();
        JsonObject root = new JsonObject();
        root.add("items", new com.google.gson.JsonArray());
        root.getAsJsonArray("items").add(item);

        ModelRegistryConfig parsed = ModelRegistryService.parse(root);
        assertEquals("", parsed.models().get(0).actualModel());
    }

    @Test
    public void getRegistryReturnsSuccessWithDefaults() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("mrs-get-home"));
        ModelRegistryService service = new ModelRegistryService(new CodemossSettingsService());

        ModelRegistryResult result = service.getRegistry();

        assertTrue(result.success());
        assertTrue(result.registry().getAsJsonArray("items").size() > 0);
    }

    @Test
    public void setRegistryRejectsConflict() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("mrs-conflict-home"));
        ModelRegistryService service = new ModelRegistryService(new CodemossSettingsService());
        JsonObject payload = ModelRegistryService.serialize(new ModelRegistryConfig(List.of(
                new ModelConfig("claude-role-sonnet", "claude", "sonnet", "Hacked",
                        "evil", "", 200_000, true, true)
        )));

        ModelRegistryResult result = service.setRegistry(payload);

        assertFalse(result.success());
        assertFalse(result.errors().isEmpty());
    }

    @Test
    public void resetRegistryReturnsResetSuccess() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("mrs-reset-home"));
        ModelRegistryService service = new ModelRegistryService(new CodemossSettingsService());

        ModelRegistryResult result = service.resetRegistry();

        assertTrue(result.success());
        assertTrue(result.reset());
        assertTrue(result.registry().getAsJsonArray("items").size() > 0);
    }

    @Test
    public void defaultSchemaHasExpectedFields() {
        JsonObject schema = ModelRegistrySchemaResult.defaultSchema().schema();
        assertEquals("模型配置中心", schema.get("title").getAsString());
        assertTrue(schema.has("providers"));
    }

    private void useTemporaryHomeDirectory(Path tempHome) throws Exception {
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
```

- [ ] **Step 4: 运行测试验证失败**

Run: `rtk .\gradlew.bat test --tests com.github.claudecodegui.settings.ModelRegistryServiceTest`
Expected: 编译失败(`ModelRegistryService` 不存在)。

- [ ] **Step 5: 实现 `ModelRegistryService`**

`src/main/java/com/github/claudecodegui/settings/ModelRegistryService.java`:

```java
package com.github.claudecodegui.settings;

import com.github.claudecodegui.config.ModelConfig;
import com.github.claudecodegui.config.ModelRegistryConfig;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Backend service for the configurable model registry.
 *
 * <p>Encapsulates payload (de)serialization, schema assembly and orchestration of
 * read/write/reset. Persistence and validation are delegated to
 * {@link CodemossSettingsService}; this service adds no front-end or action-string
 * coupling.
 */
public final class ModelRegistryService {
    private static final Logger LOG = Logger.getInstance(ModelRegistryService.class);

    private final CodemossSettingsService settingsService;

    public ModelRegistryService(CodemossSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public ModelRegistryResult getRegistry() {
        try {
            return ModelRegistryResult.success(serialize(settingsService.getModelRegistry()));
        } catch (Exception e) {
            LOG.error("[ModelRegistryService] Failed to get model registry: " + e.getMessage(), e);
            return ModelRegistryResult.failure("获取模型配置失败: " + e.getMessage());
        }
    }

    public ModelRegistryResult setRegistry(JsonObject payload) {
        try {
            ModelRegistryConfig registry = parse(payload);
            var result = settingsService.setModelRegistry(registry);
            if (result.isValid()) {
                return ModelRegistryResult.success(serialize(settingsService.getModelRegistry()));
            }
            return ModelRegistryResult.failure(result.errors());
        } catch (Exception e) {
            LOG.error("[ModelRegistryService] Failed to set model registry: " + e.getMessage(), e);
            return ModelRegistryResult.failure("保存失败: " + e.getMessage());
        }
    }

    public ModelRegistryResult resetRegistry() {
        try {
            settingsService.resetModelRegistry();
            return ModelRegistryResult.resetSuccess(serialize(settingsService.getModelRegistry()));
        } catch (Exception e) {
            LOG.error("[ModelRegistryService] Failed to reset model registry: " + e.getMessage(), e);
            return ModelRegistryResult.failure("重置模型配置失败: " + e.getMessage());
        }
    }

    public ModelRegistrySchemaResult getSchema() {
        return ModelRegistrySchemaResult.defaultSchema();
    }

    /** Serialize a registry into the {@code {items:[...]}} payload shape expected by the webview. */
    public static JsonObject serialize(ModelRegistryConfig registry) {
        JsonObject root = new JsonObject();
        var items = new com.google.gson.JsonArray();
        for (ModelConfig model : registry.models()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", model.id());
            obj.addProperty("provider", model.provider());
            obj.addProperty("role", model.role());
            obj.addProperty("label", model.label());
            if (model.actualModel() == null || model.actualModel().isEmpty()) {
                obj.add("actualModel", com.google.gson.JsonNull.INSTANCE);
            } else {
                obj.addProperty("actualModel", model.actualModel());
            }
            obj.addProperty("description", model.description());
            obj.addProperty("contextWindow", model.contextWindow());
            obj.addProperty("supports1MContext", model.supports1MContext());
            obj.addProperty("enabled", model.enabled());
            obj.addProperty("readOnly", model.readOnly());
            items.add(obj);
        }
        root.add("items", items);
        return root;
    }

    /** Parse the {@code {items:[...]}} payload back into a {@link ModelRegistryConfig}. */
    public static ModelRegistryConfig parse(JsonObject json) {
        List<ModelConfig> models = new ArrayList<>();
        if (json != null && json.has("items") && json.get("items").isJsonArray()) {
            for (var item : json.getAsJsonArray("items")) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject obj = item.getAsJsonObject();
                String id = readString(obj, "id");
                String provider = readString(obj, "provider");
                String role = readString(obj, "role");
                String label = readString(obj, "label");
                String actualModel = readString(obj, "actualModel");
                String description = readString(obj, "description");
                int contextWindow = obj.has("contextWindow") && !obj.get("contextWindow").isJsonNull()
                        ? obj.get("contextWindow").getAsInt()
                        : 200_000;
                boolean supports1MContext = obj.has("supports1MContext")
                        && !obj.get("supports1MContext").isJsonNull()
                        && obj.get("supports1MContext").getAsBoolean();
                boolean enabled = !obj.has("enabled") || obj.get("enabled").isJsonNull()
                        || obj.get("enabled").getAsBoolean();
                models.add(new ModelConfig(id, provider, role, label, actualModel, description,
                        contextWindow, supports1MContext, enabled));
            }
        }
        return new ModelRegistryConfig(models);
    }

    private static String readString(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        return obj.get(key).getAsString();
    }
}
```

- [ ] **Step 6: 运行测试验证通过**

Run: `rtk .\gradlew.bat test --tests com.github.claudecodegui.settings.ModelRegistryServiceTest`
Expected: PASS(6 个用例全绿)。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/github/claudecodegui/settings/ModelRegistryResult.java \
        src/main/java/com/github/claudecodegui/settings/ModelRegistrySchemaResult.java \
        src/main/java/com/github/claudecodegui/settings/ModelRegistryService.java \
        src/test/java/com/github/claudecodegui/settings/ModelRegistryServiceTest.java
git commit -m "refactor: extract ModelRegistryService from SettingsHandler"
```

---

## Task 2: model registry typed action handlers

**Files:**
- Create: `src/test/java/com/github/claudecodegui/handler/settings/SettingsHandlerTestFixtures.java`
- Create: `src/main/java/com/github/claudecodegui/handler/settings/ModelRegistryEvents.java`
- Create: `src/main/java/com/github/claudecodegui/handler/settings/GetModelRegistryActionHandler.java`
- Create: `src/main/java/com/github/claudecodegui/handler/settings/SetModelRegistryActionHandler.java`
- Create: `src/main/java/com/github/claudecodegui/handler/settings/ResetModelRegistryActionHandler.java`
- Create: `src/main/java/com/github/claudecodegui/handler/settings/GetModelRegistrySchemaActionHandler.java`
- Test: `src/test/java/com/github/claudecodegui/handler/settings/ModelRegistryActionHandlerTest.java`

> **原行为对照(必须逐字等价):**
> - `get`:成功 → `model_registry {items}`;失败 → `model_registry_updated {success:false, errors:[msg]}`。
> - `set`:成功 → `model_registry_updated {success:true, registry}` **再** `model_registry {items}`;校验失败 → `model_registry_updated {success:false, errors}`;异常 → `model_registry_updated {success:false, errors:["保存失败..."]}`。
> - `reset`:成功 → `model_registry_updated {success:true, reset:true, registry}` **再** `model_registry {items}`;异常 → `model_registry_updated {success:false, errors}`。
> - `schema`:→ `model_registry_schema {schema}`。
>
> 所有 event type 改用 `DownstreamEvent.*.value()`(SSOT),不再裸字符串。

- [ ] **Step 1: 写共享 test fixtures**

`src/test/java/com/github/claudecodegui/handler/settings/SettingsHandlerTestFixtures.java`:

```java
package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.util.PlatformUtils;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

/**
 * Shared test fixtures for settings handler tests:
 * - temporary home directory (so {@code new CodemossSettingsService()} works in plain JUnit);
 * - a {@link FrontendActionContext} whose {@code dispatchEvent} records every call.
 */
final class SettingsHandlerTestFixtures {

    private SettingsHandlerTestFixtures() {
    }

    /** Capture the current cached home dir so it can be restored in @After. */
    static String captureHome() throws Exception {
        return (String) homeField().get(null);
    }

    /** Point the cached home dir at {@code tempHome} and ensure ~/.codemoss exists. */
    static void useTempHome(Path tempHome) throws Exception {
        homeField().set(null, tempHome.toString());
        java.nio.file.Files.createDirectories(tempHome.resolve(".codemoss"));
    }

    static void restoreHome(String original) throws Exception {
        homeField().set(null, original);
    }

    /**
     * Build a {@link FrontendActionContext} that records each {@code dispatchEvent(type, payload)}
     * into {@code sink} as a {@code String[]{type, payload}}. {@code escapeJs} is a passthrough so
     * tests can assert payloads as plain JSON.
     */
    static FrontendActionContext recordingContext(CodemossSettingsService service,
                                                  List<String[]> sink) {
        HandlerContext.JsCallback cb = new HandlerContext.JsCallback() {
            @Override
            public void callJavaScript(String functionName, String... args) {
            }

            @Override
            public String escapeJs(String str) {
                return str;
            }

            @Override
            public void dispatchEvent(String type, String payloadJson) {
                sink.add(new String[]{type, payloadJson == null ? "" : payloadJson});
            }
        };
        HandlerContext ctx = new HandlerContext(null, null, null, service, cb);
        return new FrontendActionContext(ctx);
    }

    private static Field homeField() throws Exception {
        Field field = PlatformUtils.class.getDeclaredField("cachedRealHomeDir");
        field.setAccessible(true);
        return field;
    }
}
```

- [ ] **Step 2: 写 handler 测试(全部失败,handler 未实现)**

`src/test/java/com/github/claudecodegui/handler/settings/ModelRegistryActionHandlerTest.java`:

```java
package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.settings.ModelRegistryService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModelRegistryActionHandlerTest {
    private String originalHome;
    private List<String[]> dispatched;
    private FrontendActionContext context;
    private ModelRegistryService service;

    @Before
    public void setUp() throws Exception {
        originalHome = SettingsHandlerTestFixtures.captureHome();
        SettingsHandlerTestFixtures.useTempHome(
                Files.createTempDirectory("model-registry-handler-home"));
        dispatched = new ArrayList<>();
        CodemossSettingsService settingsService = new CodemossSettingsService();
        context = SettingsHandlerTestFixtures.recordingContext(settingsService, dispatched);
        service = new ModelRegistryService(settingsService);
    }

    @After
    public void tearDown() throws Exception {
        SettingsHandlerTestFixtures.restoreHome(originalHome);
    }

    @Test
    public void getModelRegistryDispatchesRegistryEvent() {
        new GetModelRegistryActionHandler(service).handle("", context);

        assertEquals(1, dispatched.size());
        assertEquals(DownstreamEvent.MODEL_REGISTRY.value(), dispatched.get(0)[0]);
        JsonObject payload = JsonParser.parseString(dispatched.get(0)[1]).getAsJsonObject();
        assertTrue(payload.getAsJsonArray("items").size() > 0);
    }

    @Test
    public void setModelRegistryOnSuccessDispatchesUpdatedThenRegistry() {
        JsonObject payload = ModelRegistryService.serialize(new com.github.claudecodegui.config.ModelRegistryConfig(
                java.util.List.of(new com.github.claudecodegui.config.ModelConfig(
                        "mimo-v2.5", "claude", "opus", "Mimo V2.5 Pro", "mimo-v2.5-pro",
                        "", 1_000_000, true, true))
        ));

        new SetModelRegistryActionHandler(service).handle(payload.toString(), context);

        assertEquals(2, dispatched.size());
        assertEquals(DownstreamEvent.MODEL_REGISTRY_UPDATED.value(), dispatched.get(0)[0]);
        JsonObject updated = JsonParser.parseString(dispatched.get(0)[1]).getAsJsonObject();
        assertTrue(updated.get("success").getAsBoolean());
        assertTrue(updated.has("registry"));
        assertEquals(DownstreamEvent.MODEL_REGISTRY.value(), dispatched.get(1)[0]);
    }

    @Test
    public void setModelRegistryOnConflictDispatchesUpdatedWithError() {
        JsonObject payload = ModelRegistryService.serialize(new com.github.claudecodegui.config.ModelRegistryConfig(
                java.util.List.of(new com.github.claudecodegui.config.ModelConfig(
                        "claude-role-sonnet", "claude", "sonnet", "Hacked", "evil",
                        "", 200_000, true, true))
        ));

        new SetModelRegistryActionHandler(service).handle(payload.toString(), context);

        assertEquals(1, dispatched.size());
        assertEquals(DownstreamEvent.MODEL_REGISTRY_UPDATED.value(), dispatched.get(0)[0]);
        JsonObject updated = JsonParser.parseString(dispatched.get(0)[1]).getAsJsonObject();
        assertFalse(updated.get("success").getAsBoolean());
        assertTrue(updated.getAsJsonArray("errors").size() > 0);
    }

    @Test
    public void resetModelRegistryDispatchesUpdatedThenRegistry() {
        new ResetModelRegistryActionHandler(service).handle("", context);

        assertEquals(2, dispatched.size());
        assertEquals(DownstreamEvent.MODEL_REGISTRY_UPDATED.value(), dispatched.get(0)[0]);
        JsonObject updated = JsonParser.parseString(dispatched.get(0)[1]).getAsJsonObject();
        assertTrue(updated.get("success").getAsBoolean());
        assertTrue(updated.get("reset").getAsBoolean());
        assertEquals(DownstreamEvent.MODEL_REGISTRY.value(), dispatched.get(1)[0]);
    }

    @Test
    public void getModelRegistrySchemaDispatchesSchemaEvent() {
        new GetModelRegistrySchemaActionHandler(service).handle("", context);

        assertEquals(1, dispatched.size());
        assertEquals(DownstreamEvent.MODEL_REGISTRY_SCHEMA.value(), dispatched.get(0)[0]);
        JsonObject schema = JsonParser.parseString(dispatched.get(0)[1]).getAsJsonObject();
        assertEquals("模型配置中心", schema.get("title").getAsString());
    }

    @Test
    public void handlersDeclareCorrectActions() {
        assertEquals(UpstreamAction.GET_MODEL_REGISTRY, new GetModelRegistryActionHandler(service).action());
        assertEquals(UpstreamAction.SET_MODEL_REGISTRY, new SetModelRegistryActionHandler(service).action());
        assertEquals(UpstreamAction.RESET_MODEL_REGISTRY, new ResetModelRegistryActionHandler(service).action());
        assertEquals(UpstreamAction.GET_MODEL_REGISTRY_SCHEMA, new GetModelRegistrySchemaActionHandler(service).action());
    }
}
```

- [ ] **Step 3: 运行测试验证失败**

Run: `rtk .\gradlew.bat test --tests com.github.claudecodegui.handler.settings.ModelRegistryActionHandlerTest`
Expected: 编译失败(handler 类不存在)。

- [ ] **Step 4: 实现 `ModelRegistryEvents` 共享工具**

`src/main/java/com/github/claudecodegui/handler/settings/ModelRegistryEvents.java`:

```java
package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.settings.ModelRegistryResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Shared downstream-event assembly for model registry handlers (DRY).
 * Builds the {@code model_registry_updated} response payload and dispatches it.
 */
final class ModelRegistryEvents {
    private ModelRegistryEvents() {
    }

    /** Dispatch {@code model_registry_updated} with success/registry or errors shape. */
    static void dispatchUpdated(HandlerContext ctx, ModelRegistryResult result) {
        JsonObject response = new JsonObject();
        response.addProperty("success", result.success());
        if (result.reset()) {
            response.addProperty("reset", true);
        }
        if (result.success() && result.registry() != null) {
            response.add("registry", result.registry());
        }
        if (!result.success()) {
            JsonArray errors = new JsonArray();
            result.errors().forEach(errors::add);
            response.add("errors", errors);
        }
        ctx.dispatchEvent(DownstreamEvent.MODEL_REGISTRY_UPDATED.value(),
                ctx.escapeJs(response.toString()));
    }

    /** Dispatch the full {@code model_registry} snapshot. */
    static void dispatchRegistry(HandlerContext ctx, ModelRegistryResult result) {
        if (result.registry() == null) {
            return;
        }
        ctx.dispatchEvent(DownstreamEvent.MODEL_REGISTRY.value(),
                ctx.escapeJs(result.registry().toString()));
    }
}
```

- [ ] **Step 5: 实现 4 个 handler**

`src/main/java/com/github/claudecodegui/handler/settings/GetModelRegistryActionHandler.java`:

```java
package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.settings.ModelRegistryResult;
import com.github.claudecodegui.settings.ModelRegistryService;

public final class GetModelRegistryActionHandler implements FrontendActionHandler<String> {
    private final ModelRegistryService service;

    public GetModelRegistryActionHandler(ModelRegistryService service) {
        this.service = service;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_MODEL_REGISTRY;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        ModelRegistryResult result = service.getRegistry();
        if (result.success()) {
            ModelRegistryEvents.dispatchRegistry(ctx, result);
        } else {
            ModelRegistryEvents.dispatchUpdated(ctx, result);
        }
    }
}
```

`src/main/java/com/github/claudecodegui/handler/settings/SetModelRegistryActionHandler.java`:

```java
package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.settings.ModelRegistryResult;
import com.github.claudecodegui.settings.ModelRegistryService;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;

public final class SetModelRegistryActionHandler implements FrontendActionHandler<String> {
    private final ModelRegistryService service;

    public SetModelRegistryActionHandler(ModelRegistryService service) {
        this.service = service;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.SET_MODEL_REGISTRY;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        JsonObject json = GsonHolder.GSON.fromJson(payload, JsonObject.class);
        ModelRegistryResult result = service.setRegistry(json);
        ModelRegistryEvents.dispatchUpdated(ctx, result);
        if (result.success()) {
            ModelRegistryEvents.dispatchRegistry(ctx, result);
        }
    }
}
```

`src/main/java/com/github/claudecodegui/handler/settings/ResetModelRegistryActionHandler.java`:

```java
package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.settings.ModelRegistryResult;
import com.github.claudecodegui.settings.ModelRegistryService;

public final class ResetModelRegistryActionHandler implements FrontendActionHandler<String> {
    private final ModelRegistryService service;

    public ResetModelRegistryActionHandler(ModelRegistryService service) {
        this.service = service;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.RESET_MODEL_REGISTRY;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        ModelRegistryResult result = service.resetRegistry();
        ModelRegistryEvents.dispatchUpdated(ctx, result);
        if (result.success()) {
            ModelRegistryEvents.dispatchRegistry(ctx, result);
        }
    }
}
```

`src/main/java/com/github/claudecodegui/handler/settings/GetModelRegistrySchemaActionHandler.java`:

```java
package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.settings.ModelRegistryService;

public final class GetModelRegistrySchemaActionHandler implements FrontendActionHandler<String> {
    private final ModelRegistryService service;

    public GetModelRegistrySchemaActionHandler(ModelRegistryService service) {
        this.service = service;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_MODEL_REGISTRY_SCHEMA;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        String schemaJson = service.getSchema().schema().toString();
        ctx.dispatchEvent(DownstreamEvent.MODEL_REGISTRY_SCHEMA.value(), ctx.escapeJs(schemaJson));
    }
}
```

- [ ] **Step 6: 运行测试验证通过**

Run: `rtk .\gradlew.bat test --tests com.github.claudecodegui.handler.settings.ModelRegistryActionHandlerTest`
Expected: PASS(6 个用例全绿)。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/github/claudecodegui/handler/settings/ \
        src/test/java/com/github/claudecodegui/handler/settings/SettingsHandlerTestFixtures.java \
        src/test/java/com/github/claudecodegui/handler/settings/ModelRegistryActionHandlerTest.java
git commit -m "refactor: add typed model registry action handlers"
```

---

## Task 3: AppearanceConfigService + SetAppearanceConfigActionHandler

**Files:**
- Create: `src/main/java/com/github/claudecodegui/settings/AppearanceConfigResult.java`
- Create: `src/main/java/com/github/claudecodegui/settings/AppearanceConfigService.java`
- Create: `src/main/java/com/github/claudecodegui/handler/settings/SetAppearanceConfigActionHandler.java`
- Test: `src/test/java/com/github/claudecodegui/handler/settings/AppearanceConfigActionHandlerTest.java`

> **原行为对照:** `handleSetAppearanceConfig` —— 无论成功/失败都回读 `getAppearanceConfigJson` 并派发 `appearance.apply`。service 保留同一行为(set → 回读 → result 携带 configJson),日志在 service 内。

- [ ] **Step 1: 写 `AppearanceConfigResult` + `AppearanceConfigService`**

`src/main/java/com/github/claudecodegui/settings/AppearanceConfigResult.java`:

```java
package com.github.claudecodegui.settings;

/**
 * Result of applying an appearance config. Always carries the authoritative
 * config JSON (post-write read-back) so the handler can push it to the webview
 * on both success and failure paths.
 */
public final class AppearanceConfigResult {
    private final String configJson;

    public AppearanceConfigResult(String configJson) {
        this.configJson = configJson;
    }

    public String configJson() { return configJson; }
}
```

`src/main/java/com/github/claudecodegui/settings/AppearanceConfigService.java`:

```java
package com.github.claudecodegui.settings;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Backend service for appearance config persistence.
 * Writes the webview payload and reads back the authoritative config so the
 * handler can hydrate / roll back optimistic UI updates.
 */
public final class AppearanceConfigService {
    private static final Logger LOG = Logger.getInstance(AppearanceConfigService.class);

    private final CodemossSettingsService settingsService;

    public AppearanceConfigService(CodemossSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public AppearanceConfigResult apply(JsonObject rawConfig) {
        try {
            settingsService.setAppearanceConfig(rawConfig);
            LOG.debug("[AppearanceConfigService] Saved appearance config");
        } catch (Exception e) {
            LOG.error("[AppearanceConfigService] Failed to save appearance config: " + e.getMessage(), e);
        }
        return new AppearanceConfigResult(CodemossSettingsService.getAppearanceConfigJson(settingsService));
    }
}
```

- [ ] **Step 2: 写 handler 测试**

`src/test/java/com/github/claudecodegui/handler/settings/AppearanceConfigActionHandlerTest.java`:

```java
package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.settings.AppearanceConfigService;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AppearanceConfigActionHandlerTest {
    private String originalHome;
    private List<String[]> dispatched;
    private FrontendActionContext context;
    private AppearanceConfigService service;

    @Before
    public void setUp() throws Exception {
        originalHome = SettingsHandlerTestFixtures.captureHome();
        SettingsHandlerTestFixtures.useTempHome(
                Files.createTempDirectory("appearance-handler-home"));
        dispatched = new ArrayList<>();
        CodemossSettingsService settingsService = new CodemossSettingsService();
        context = SettingsHandlerTestFixtures.recordingContext(settingsService, dispatched);
        service = new AppearanceConfigService(settingsService);
    }

    @After
    public void tearDown() throws Exception {
        SettingsHandlerTestFixtures.restoreHome(originalHome);
    }

    @Test
    public void setAppearanceConfigDispatchesApplyEvent() {
        JsonObject payload = new JsonObject();
        payload.addProperty("themePreference", "dark");
        payload.addProperty("fontSizeLevel", 2);

        new SetAppearanceConfigActionHandler(service).handle(payload.toString(), context);

        assertEquals(1, dispatched.size());
        assertEquals(DownstreamEvent.APPEARANCE_APPLY.value(), dispatched.get(0)[0]);
        JsonObject applied = JsonParser.parseString(dispatched.get(0)[1]).getAsJsonObject();
        assertEquals("dark", applied.get("themePreference").getAsString());
    }

    @Test
    public void declaresCorrectAction() {
        assertEquals(UpstreamAction.SET_APPEARANCE_CONFIG,
                new SetAppearanceConfigActionHandler(service).action());
    }
}
```

- [ ] **Step 3: 运行测试验证失败**

Run: `rtk .\gradlew.bat test --tests com.github.claudecodegui.handler.settings.AppearanceConfigActionHandlerTest`
Expected: 编译失败(`SetAppearanceConfigActionHandler` 不存在)。

- [ ] **Step 4: 实现 `SetAppearanceConfigActionHandler`**

`src/main/java/com/github/claudecodegui/handler/settings/SetAppearanceConfigActionHandler.java`:

```java
package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.settings.AppearanceConfigResult;
import com.github.claudecodegui.settings.AppearanceConfigService;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;

public final class SetAppearanceConfigActionHandler implements FrontendActionHandler<String> {
    private final AppearanceConfigService service;

    public SetAppearanceConfigActionHandler(AppearanceConfigService service) {
        this.service = service;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.SET_APPEARANCE_CONFIG;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        JsonObject json = GsonHolder.GSON.fromJson(payload, JsonObject.class);
        AppearanceConfigResult result = service.apply(json);
        ctx.dispatchEvent(DownstreamEvent.APPEARANCE_APPLY.value(),
                ctx.escapeJs(result.configJson()));
    }
}
```

- [ ] **Step 5: 运行测试验证通过**

Run: `rtk .\gradlew.bat test --tests com.github.claudecodegui.handler.settings.AppearanceConfigActionHandlerTest`
Expected: PASS(2 个用例全绿)。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/github/claudecodegui/settings/AppearanceConfigResult.java \
        src/main/java/com/github/claudecodegui/settings/AppearanceConfigService.java \
        src/main/java/com/github/claudecodegui/handler/settings/SetAppearanceConfigActionHandler.java \
        src/test/java/com/github/claudecodegui/handler/settings/AppearanceConfigActionHandlerTest.java
git commit -m "refactor: add typed appearance config action handler"
```

---

## Task 4: 接驳 —— SettingsHandler 瘦身 + FrontendActionDispatcher 接入主入口

**Files:**
- Modify: `src/main/java/com/github/claudecodegui/handler/SettingsHandler.java`
- Modify: `src/main/java/com/github/claudecodegui/ui/ChatWindowDelegate.java`(含 `DelegateHost` 接口)
- Modify: `src/main/java/com/github/claudecodegui/ui/toolwindow/ClaudeChatWindow.java`
- Test: `src/test/java/com/github/claudecodegui/handler/settings/SettingsHandlerTypedWiringTest.java`

> 本 Task 是集成接线,无独立"失败测试先行"——用 Step 1 的接线不变量测试 + 编译 + 已有测试共同把关。

- [ ] **Step 1: 写接驳不变量测试**

`src/test/java/com/github/claudecodegui/handler/settings/SettingsHandlerTypedWiringTest.java`:

```java
package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionDispatcher;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.handler.core.MessageHandler;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.settings.AppearanceConfigService;
import com.github.claudecodegui.settings.ModelRegistryService;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SettingsHandlerTypedWiringTest {

    /**
     * Migrated actions must remain resolvable UpstreamAction values so the typed handlers can
     * claim them (and so they are absent from the slimmed SettingsHandler's SUPPORTED_TYPES).
     */
    @Test
    public void migratedActionsRemainResolvable() {
        for (String migrated : new String[]{
                "get_model_registry", "set_model_registry", "reset_model_registry",
                "get_model_registry_schema", "set_appearance_config"
        }) {
            assertTrue(UpstreamAction.fromValue(migrated).isPresent());
        }
    }

    /**
     * The wired dispatcher must: (a) construct without a duplicate-action exception — proving the
     * 5 typed handlers do not collide with each other; (b) route a legacy MessageHandler's actions
     * through LegacyMessageHandlerAdapter with raw content forwarded; (c) miss unknown actions.
     *
     * We deliberately use a dummy legacy handler instead of a real SettingsHandler: constructing
     * SettingsHandler requires a live IDE environment (ApplicationManager + sub-handlers), and
     * dispatching its actions touches the settings service. Typed-handler dispatch behaviour is
     * covered by ModelRegistryActionHandlerTest / AppearanceConfigActionHandlerTest.
     */
    @Test
    public void wiredDispatcherConstructsAndRoutesLegacyWithoutDuplicates() {
        HandlerContext ctx = new HandlerContext(null, null, null, null, new HandlerContext.JsCallback() {
            @Override public void callJavaScript(String functionName, String... args) { }
            @Override public String escapeJs(String str) { return str; }
        });
        ModelRegistryService modelRegistryService = new ModelRegistryService(null);
        AppearanceConfigService appearanceConfigService = new AppearanceConfigService(null);

        AtomicReference<String> legacySeen = new AtomicReference<>();
        MessageHandler dummyLegacy = new MessageHandler() {
            @Override public boolean handle(String type, String content) {
                legacySeen.set(type + "|" + content);
                return true;
            }
            @Override public String[] getSupportedTypes() {
                return new String[]{"set_model", "get_runtime_policy"};
            }
        };

        List<FrontendActionHandler<?>> typed = new ArrayList<>();
        typed.add(new GetModelRegistryActionHandler(modelRegistryService));
        typed.add(new SetModelRegistryActionHandler(modelRegistryService));
        typed.add(new ResetModelRegistryActionHandler(modelRegistryService));
        typed.add(new GetModelRegistrySchemaActionHandler(modelRegistryService));
        typed.add(new SetAppearanceConfigActionHandler(appearanceConfigService));
        typed.addAll(LegacyMessageHandlerAdapter.from(dummyLegacy));

        // 构造不抛 IllegalArgumentException = 5 个 typed action 互不重复,且 dummy 的 set_model /
        // get_runtime_policy 与 typed 不重叠(它们仍在 UpstreamAction 枚举中,故 adapter 会包装)
        FrontendActionDispatcher dispatcher = new FrontendActionDispatcher(typed, ctx);

        // legacy action 经 adapter 命中 dummy handler,且透传原始 content
        assertTrue(dispatcher.dispatch("set_model", "claude-role-sonnet"));
        assertEquals("set_model|claude-role-sonnet", legacySeen.get());
        assertTrue(dispatcher.dispatch("get_runtime_policy", ""));
        // 未知 action miss
        assertFalse(dispatcher.dispatch("not_a_real_action", ""));
    }
}
```

> 注:本测试用 **dummy legacy handler** 验证 adapter 路由与"构造无重复 action"不变量,**不构造真实 `SettingsHandler`**(其构造依赖 `ApplicationManager` 等 IDE 运行时,且 dispatch 其 action 会访问 settings service)。真实 `SettingsHandler` 经 adapter 接入的生产接线在 Step 4(生产代码,有真实 context);typed handler 的真实派发由 `ModelRegistryActionHandlerTest` / `AppearanceConfigActionHandlerTest` 覆盖。

- [ ] **Step 2: 瘦身 `SettingsHandler`**

在 `src/main/java/com/github/claudecodegui/handler/SettingsHandler.java`:

(a) 从 `SUPPORTED_TYPES` 数组删除这 5 项(行号以当前文件为准):
- `"get_model_registry",`(约 42)
- `"set_model_registry",`(约 43)
- `"reset_model_registry",`(约 44)
- `"get_model_registry_schema",`(约 45)
- `"set_appearance_config",`(约 103,在 Appearance 注释块下)

(b) 从 `handle()` 的 switch 删除这 5 个 case 块(约 161-172 的 4 个 model registry case + 约 353-355 的 `set_appearance_config` case 及其上方 `// Appearance config ...` 注释)。

(c) 删除这些私有方法(整段删除):
- `handleSetAppearanceConfig(String)`(约 436-446)
- `pushAppearanceConfig()`(约 448-451)
- `handleGetModelRegistry()`(约 462-470)
- `handleSetModelRegistry(String)`(约 472-500)
- `handleResetModelRegistry()`(约 502-516)
- `handleGetModelRegistrySchema()`(约 518-526)
- `dispatchModelRegistryError(String)`(约 528-535)
- `serializeModelRegistry(ModelRegistryConfig)`(约 537-560)
- `parseModelRegistryFromJson(JsonObject)`(约 562-589)
- `readString(JsonObject, String)`(约 591-596)

(d) 删除已无引用的 import:
- `import com.github.claudecodegui.config.ModelConfig;`(line 6)
- `import com.github.claudecodegui.config.ModelRegistryConfig;`(line 7)
- `import com.github.claudecodegui.settings.CodemossSettingsService;`(line 14)—— 仅 `pushAppearanceConfig` 用过 `CodemossSettingsService.getAppearanceConfigJson`;删除后该 import 无引用。

> 保留:`runtime_policy` 相关方法、`handleSetUserLanguage`/`handleGetUserLanguage`/`handleClearUserLanguage`/`pushLanguageConfig`、`getModelContextLimit` 静态委托、`registerThemeChangeListener` 全部不动。`ModelProviderHandler` import 不动。
>
> 删除后用 IDE 搜索确认无残留引用(`serializeModelRegistry` / `parseModelRegistryFromJson` / `dispatchModelRegistryError` / `pushAppearanceConfig` 全仓应只剩 `ModelRegistryService` / 新 handler 里的新实现)。

- [ ] **Step 3: `DelegateHost` 接口加 setter**

`src/main/java/com/github/claudecodegui/ui/ChatWindowDelegate.java`,在 `DelegateHost` 接口(约 line 101)内,`void setMessageDispatcher(MessageDispatcher d);`(约 line 117)之后新增:

```java
        void setFrontendActionDispatcher(FrontendActionDispatcher d);
```

并在文件顶部 import 区新增:

```java
import com.github.claudecodegui.handler.core.FrontendActionDispatcher;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.LegacyMessageHandlerAdapter;
import com.github.claudecodegui.handler.settings.GetModelRegistryActionHandler;
import com.github.claudecodegui.handler.settings.SetModelRegistryActionHandler;
import com.github.claudecodegui.handler.settings.ResetModelRegistryActionHandler;
import com.github.claudecodegui.handler.settings.GetModelRegistrySchemaActionHandler;
import com.github.claudecodegui.handler.settings.SetAppearanceConfigActionHandler;
import com.github.claudecodegui.settings.ModelRegistryService;
import com.github.claudecodegui.settings.AppearanceConfigService;
import java.util.ArrayList;
import java.util.List;
```

- [ ] **Step 4: `ChatWindowDelegate` 构造并注入 typed dispatcher**

在 handler 注册段(约 line 295 `MessageDispatcher messageDispatcher = new MessageDispatcher();` 之后、`messageDispatcher.registerHandler(new ProviderHandler(...))` 之前)插入 typed dispatcher 构造,并删除原 `messageDispatcher.registerHandler(new SettingsHandler(handlerContext));` 行(约 line 303):

```java
        MessageDispatcher messageDispatcher = new MessageDispatcher();
        host.setMessageDispatcher(messageDispatcher);

        // Typed frontend action dispatcher: migrated settings actions (model registry + appearance)
        // are served by dedicated typed handlers; the remaining SettingsHandler actions are bridged
        // via LegacyMessageHandlerAdapter. This dispatcher is consulted before the legacy
        // MessageDispatcher in ClaudeChatWindow#handleMessage.
        CodemossSettingsService settings = handlerContext.getSettingsService();
        ModelRegistryService modelRegistryService = new ModelRegistryService(settings);
        AppearanceConfigService appearanceConfigService = new AppearanceConfigService(settings);
        List<FrontendActionHandler<?>> typedHandlers = new ArrayList<>();
        typedHandlers.add(new GetModelRegistryActionHandler(modelRegistryService));
        typedHandlers.add(new SetModelRegistryActionHandler(modelRegistryService));
        typedHandlers.add(new ResetModelRegistryActionHandler(modelRegistryService));
        typedHandlers.add(new GetModelRegistrySchemaActionHandler(modelRegistryService));
        typedHandlers.add(new SetAppearanceConfigActionHandler(appearanceConfigService));
        typedHandlers.addAll(LegacyMessageHandlerAdapter.from(new SettingsHandler(handlerContext)));
        host.setFrontendActionDispatcher(
                new FrontendActionDispatcher(typedHandlers, handlerContext));

        messageDispatcher.registerHandler(new ProviderHandler(handlerContext));
```

> 删除 `messageDispatcher.registerHandler(new SettingsHandler(handlerContext));` 这一行(SettingsHandler 已通过 adapter 进 typed dispatcher,不可重复)。
> 其余 `messageDispatcher.registerHandler(...)` 全部保留不变。

- [ ] **Step 5: `ClaudeChatWindow` 加字段/setter + 改主入口**

`src/main/java/com/github/claudecodegui/ui/toolwindow/ClaudeChatWindow.java`:

(a) import 区(line 6 `import ...core.MessageDispatcher;` 附近)新增:

```java
import com.github.claudecodegui.handler.core.FrontendActionDispatcher;
```

(b) 字段区(line 174 `private MessageDispatcher messageDispatcher;` 之后)新增:

```java
    private FrontendActionDispatcher frontendActionDispatcher;
```

(c) 主入口(line 724)替换为两段 dispatch:

```java
        if (frontendActionDispatcher != null && frontendActionDispatcher.dispatch(type, content)) {
            return;
        }
        if (messageDispatcher.dispatch(type, content)) {
            return;
        }
```

(d) `DelegateHost` 匿名实现区(line 1430-1433 `setMessageDispatcher` 实现之后)新增:

```java
            @Override
            public void setFrontendActionDispatcher(FrontendActionDispatcher d) {
                frontendActionDispatcher = d;
            }
```

- [ ] **Step 6: 编译 + 跑接驳与协议测试**

Run:
```bash
rtk .\gradlew.bat test --tests com.github.claudecodegui.handler.settings.SettingsHandlerTypedWiringTest --tests com.github.claudecodegui.protocol.ProtocolEnumCoverageTest --tests com.github.claudecodegui.handler.core.FrontendActionDispatcherTest
```
Expected: PASS。

再确认 SettingsHandler 编译无误(全量编译相关模块):
Run: `rtk .\gradlew.bat compileJava`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/github/claudecodegui/handler/SettingsHandler.java \
        src/main/java/com/github/claudecodegui/ui/ChatWindowDelegate.java \
        src/main/java/com/github/claudecodegui/ui/toolwindow/ClaudeChatWindow.java \
        src/test/java/com/github/claudecodegui/handler/settings/SettingsHandlerTypedWiringTest.java
git commit -m "refactor: wire FrontendActionDispatcher as primary entry, slim SettingsHandler"
```

---

## Task 5: 前端 2 处裸字符串收尾

**Files:**
- Modify: `webview/src/components/settings/hooks/useCodexProviderManagement.ts`
- Modify: `webview/src/components/settings/hooks/useSettingsThemeSync.ts`

> `sendAction(action, payload)` 对对象 payload 自动 `JSON.stringify`(见 `webview/src/bridge/typed.ts:5-16`),因此去掉外层手动 `JSON.stringify`。

- [ ] **Step 1: 迁移 `useCodexProviderManagement.ts`**

`webview/src/components/settings/hooks/useCodexProviderManagement.ts`:

(a) import 区替换/新增(line 4 `import { sendBridgeEvent } from '../../../utils/bridge';`):

```typescript
import { sendAction } from '../../../bridge/typed';
import { UPSTREAM } from '../../../generated/protocol';
```

> 若文件内 `sendBridgeEvent` / 顶部 `sendToJava` wrapper(line 7-9)在迁移后无其他引用,一并删除;否则保留。执行前在该文件内 grep `sendBridgeEvent` 与 `sendToJava` 确认。

(b) `syncCodexProviderCatalogToRegistry`(line 15)替换为:

```typescript
  sendAction(UPSTREAM.SET_MODEL_REGISTRY, {
    items: [...nonCodexItems, ...catalogModels],
  });
```

- [ ] **Step 2: 迁移 `useSettingsThemeSync.ts`**

`webview/src/components/settings/hooks/useSettingsThemeSync.ts`:

(a) import 区新增(与现有 import 并列):

```typescript
import { sendAction } from '../../../bridge/typed';
import { UPSTREAM } from '../../../generated/protocol';
```

(b) 防抖 effect 内(line 213 `sendBridgeEvent('set_appearance_config', JSON.stringify({...}))`)替换为:

```typescript
      sendAction(UPSTREAM.SET_APPEARANCE_CONFIG, {
        themePreference,
        fontSizeLevel,
        diffTheme,
        chatBgColor: { light: chatBgColors.light || undefined, dark: chatBgColors.dark || undefined },
        userMsgColor: { light: userMsgColors.light || undefined, dark: userMsgColors.dark || undefined },
      });
```

(c) 若 `sendBridgeEvent` 在本文件迁移后无其他引用,删除其 import;否则保留。

- [ ] **Step 3: 运行前端测试验证不回归**

Run:
```bash
cd webview
cmd /c node_modules\.bin\vitest.cmd run src/bridge/__tests__/typed.test.ts src/components/settings/ModelRegistrySection/index.test.tsx
```
Expected: PASS。

并确认这两个文件无残留裸字符串:
Run: `cd webview && git grep -n "set_model_registry\|set_appearance_config" -- src/components/settings/hooks/useCodexProviderManagement.ts src/components/settings/hooks/useSettingsThemeSync.ts`
Expected: 无输出(已全部迁移)。

- [ ] **Step 4: Commit**

```bash
git add webview/src/components/settings/hooks/useCodexProviderManagement.ts \
        webview/src/components/settings/hooks/useSettingsThemeSync.ts
git commit -m "refactor: switch settings hooks to typed sendAction"
```

---

## Task 6: 验收(全量 targeted tests + 行为兼容确认)

- [ ] **Step 1: 跑文档指定的全部 targeted Java 测试**

Run:
```bash
rtk .\gradlew.bat test --tests com.github.claudecodegui.protocol.ProtocolEnumCoverageTest --tests com.github.claudecodegui.handler.core.FrontendActionDispatcherTest --tests com.github.claudecodegui.handler.settings.ModelRegistryActionHandlerTest --tests com.github.claudecodegui.handler.settings.AppearanceConfigActionHandlerTest --tests com.github.claudecodegui.handler.settings.SettingsHandlerTypedWiringTest --tests com.github.claudecodegui.settings.ModelRegistryServiceTest --tests com.github.claudecodegui.settings.CodemossSettingsServiceModelRegistryTest
```
Expected: 全部 PASS。

- [ ] **Step 2: 跑前端 targeted 测试**

Run:
```bash
cd webview
cmd /c node_modules\.bin\vitest.cmd run src/bridge/__tests__/typed.test.ts src/components/settings/ModelRegistrySection/index.test.tsx
```
Expected: PASS。

- [ ] **Step 3: 手动行为兼容核对(对照验收标准)**

逐条核对 `docs/designs/plugin-architecture-refactor-next-iteration.md` 的验收标准:
- `SettingsHandler` 不再直接承载 model registry action 的业务实现(已删 5 个 case + 私有方法)。
- model registry 四个 action 均有独立 `FrontendActionHandler`(GET/SET/RESET/SCHEMA)。
- appearance config 至少迁移 `SET_APPEARANCE_CONFIG`(`SetAppearanceConfigActionHandler`)。
- model registry / appearance 业务规则在后端 service(`ModelRegistryService` / `AppearanceConfigService`)。
- 前端 settings 调用使用生成协议常量(2 处裸字符串已切 `sendAction(UPSTREAM.*)`)。
- 未迁移 settings action 仍通过 `LegacyMessageHandlerAdapter` 正常工作(`SettingsHandlerTypedWiringTest` + adapter 路径)。

- [ ] **Step 4: 更新设计状态文档(可选)**

若有 `docs/designs/plugin-architecture-refactor-status.md` 记录进度,追加一条"model registry + appearance typed 迁移已落地"。无则跳过。

---

## 验收标准(来自设计文档,逐条对应)

| 设计文档验收标准 | 本 plan 对应 |
|---|---|
| SettingsHandler 不再直接承载 model registry action 业务 | Task 4 Step 2 |
| model registry 四个 action 均有独立 FrontendActionHandler | Task 2 |
| appearance config 至少迁移 SET_APPEARANCE_CONFIG | Task 3 |
| 业务规则位于后端 service | Task 1 + Task 3 |
| 前端 settings 调用使用生成协议常量 | Task 5 |
| targeted Java / frontend tests 通过 | Task 6 Step 1-2 |
| 未迁移 settings action 仍通过 legacy adapter 工作 | Task 4(adapter 接入)+ Task 6 Step 3 |

## 风险与缓解

- **风险:dispatcher 与 legacy adapter 重复处理同一 action。** 缓解:`FrontendActionDispatcher` 构造期重复检测;SettingsHandler 已从 `SUPPORTED_TYPES` 删除 5 个已迁移 type,adapter 不会再次声明它们;`SettingsHandlerTypedWiringTest` 固化"构造不抛 + 未迁移走 adapter"。
- **风险:adapter 跳过未在 `UpstreamAction` 枚举的 type 导致 action 失效。** 缓解:已人工核对 SettingsHandler 全部 72 个 supportedType 均在枚举内;`SettingsHandlerTypedWiringTest.migratedActionsRemainResolvable` 固化已迁移 type 可解析(全量不变量由 `ProtocolEnumCoverageTest` 族覆盖)。
- **风险:前端仍依赖旧 payload 字段。** 缓解:typed handler 的 event payload 与原 SettingsHandler 逐字等价(Task 2/3 的"原行为对照");`sendAction` 仅改 action 来源,payload 结构不变。
- **风险:全量 baseline 测试存在历史失败。** 缓解:本 plan 只以 targeted tests 作为验收门;baseline 另行处理(见 memory `test-mjs-preexisting-failures`)。

## 后续阶段预留(不在本 plan 范围)

1. 把其余 legacy handler(`ProviderHandler` / `SessionHandler` / ...)也经 `LegacyMessageHandlerAdapter` 接入,最终撤除 `MessageDispatcher` 与主入口 fallback 段。
2. 前端 `GET_MODEL_REGISTRY_SCHEMA` 消费(新增 schema UI)。
3. runtime policy / 语言等 settings action 的同类 typed 迁移。
4. 拆分 `ModelProviderHandler`,Provider/Model ViewModel 组装迁入后端 service。
