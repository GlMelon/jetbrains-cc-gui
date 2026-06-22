# V9 派发器 OCP 统一 · 第一切片(Codex 订阅配额迁移)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `get_codex_subscription_quota` 从 `SettingsHandler`(SUPPORTED_TYPES 字符串数组 + switch + 委托子 handler)迁移为符合开闭原则的 `FrontendActionHandler<String>` 实现,并删除旧的 `CodexSubscriptionQuotaHandler` 委托类——直接落地 `AGENTS.md` 总则二(开闭原则,新增 action 不改既有派发器)。

**Architecture:** 新建 `GetCodexSubscriptionQuotaActionHandler`(包 `handler/settings/`,与已落地的 5 个 typed handler 同构),复用 app 级 `CodexSubscriptionQuotaService` 单例,异步取快照后经单一 `codex.subscription_quota` 事件回传前端(成功/失败共用同一出口,与旧实现**逐字等价**)。迁移核心约束:**新增 typed handler 注册 `get_codex_subscription_quota` 与从 `SettingsHandler.SUPPORTED_TYPES` 剔除该条目必须同 commit**——否则 `LegacyMessageHandlerAdapter` 仍会为该字符串注册 `LegacyActionHandler`,与 typed handler 在 `FrontendActionDispatcher` 构造期 `putIfAbsent` 重复检测冲突,抛 `IllegalArgumentException` 致窗口无法启动。守门测试用反射读取 `SUPPORTED_TYPES` 静态字段,**在运行时崩溃之前**拦截这种回归。

**Tech Stack:** Java 17 + IntelliJ Platform + Gson + JUnit 4(本项目 testImplementation 仅声明 `junit:junit:4.13.2`,无 Jupiter)。

**依据:** `AGENTS.md` 总则二(开闭原则)、第 6 节合规检查清单第 4 条(派发器对扩展开放)。本切片是 V9(62 个未迁移 action)的**热身切片**:选 `get_codex_subscription_quota` 因其 action 数最少(1)、纯 GET 无写入/校验/级联、已有独立 service、单一 dispatchEvent、零构造注入。

---

## 前置事实(已排查确认,执行者无需重复调查)

| 事实 | 位置 | 说明 |
|---|---|---|
| 旧 handler 仅 2 处引用 | `src` 全局 grep `CodexSubscriptionQuotaHandler` | 仅 `SettingsHandler.java` + 自身;迁移删 SettingsHandler 引用后可安全删整个类 |
| 上行枚举已存在 | `protocol/UpstreamAction.java:98` | `GET_CODEX_SUBSCRIPTION_QUOTA("get_codex_subscription_quota")` |
| 下行枚举已存在 | `protocol/DownstreamEvent.java:189` | `CODEX_SUBSCRIPTION_QUOTA("codex.subscription_quota")` |
| service 是 app 级单例 | `service/CodexSubscriptionQuotaService.java` | `@Service(Service.Level.APP)`;`getQuotaSnapshot()` 返回 `CompletableFuture<JsonObject>`(:337);`buildUnavailablePayload(String, long)` 静态工厂(:98) |
| 迁移模板 | `handler/settings/ResetModelRegistryActionHandler.java` | 36 行,实现 `FrontendActionHandler<String>`,三 override |
| 重复检测硬约束 | `handler/core/FrontendActionDispatcher.java:18` | 构造期 `putIfAbsent`,重复 action 抛 `IllegalArgumentException` |
| adapter 传导链 | `handler/core/LegacyMessageHandlerAdapter.java:14-17` | `from()` 遍历 `getSupportedTypes()`,对可被 `UpstreamAction.fromValue()` 解析的每个字符串包装成 `LegacyActionHandler` 注册 |
| 框架接口 | `handler/core/FrontendActionHandler.java` | `UpstreamAction action()` / `Class<T> payloadType()` / `void handle(T, FrontendActionContext)` |
| context 取值 | `handler/core/FrontendActionContext.java:10` | `context.handlerContext()` 返回 `HandlerContext`(含 `dispatchEvent`/`escapeJs`) |
| 旧实现逐字 | `handler/CodexSubscriptionQuotaHandler.java:24-42` | `handleGetCodexSubscriptionQuota()` + `sendPayload(JsonObject)`,本切片照搬其异步出口 |
| SUPPORTED_TYPES 可反射 | `handler/SettingsHandler.java:35` | `private static final String[] SUPPORTED_TYPES` |
| 旧 handler 委托点 | `handler/SettingsHandler.java` | 字段 :33 / 构造 :111 / switch case :182-184 / SUPPORTED_TYPES 条目 :47 |
| wiring 注册点 | `ui/ChatWindowDelegate.java:317-323` | `typedHandlers.add(...)` 在 :318-322;`LegacyMessageHandlerAdapter.from(...)` 在 :323 |

**注意(本切片纪律):** 迁移只做"搬入 OCP 框架",**不夹带**额外重构。`dispatchEvent` 仍用字符串字面量 `"codex.subscription_quota"`(与旧实现逐字等价);魔法字符串→`DownstreamEvent` 枚举化属独立 SSOT 优化,留后续切片,不混入本迁移。

---

## File Structure

| 文件 | 操作 | 职责 |
|---|---|---|
| `src/main/java/com/github/claudecodegui/handler/settings/GetCodexSubscriptionQuotaActionHandler.java` | Create | typed handler,绑定 `GET_CODEX_SUBSCRIPTION_QUOTA`,复用 service 异步取快照回传 |
| `src/test/java/com/github/claudecodegui/handler/settings/GetCodexSubscriptionQuotaActionHandlerTest.java` | Create | 契约单测:断言 `action()`/`payloadType()` 绑定正确(不依赖 IDE Application) |
| `src/test/java/com/github/claudecodegui/handler/settings/SettingsHandlerTypedWiringTest.java` | Modify | 守门:`migratedActionsRemainResolvable` 加新 action + 新增反射守门断言已迁移 action 已剔除自 `SUPPORTED_TYPES` |
| `src/main/java/com/github/claudecodegui/handler/SettingsHandler.java` | Modify | 删 SUPPORTED_TYPES 条目 :47 + switch case :182-184 + 字段 :33 + 构造 :111 |
| `src/main/java/com/github/claudecodegui/ui/ChatWindowDelegate.java` | Modify | 加 import(:27 前)+ `typedHandlers.add(new GetCodexSubscriptionQuotaActionHandler())`(:322 后、:323 前) |
| `src/main/java/com/github/claudecodegui/handler/CodexSubscriptionQuotaHandler.java` | Delete | 旧委托类,迁移后无引用 |

---

## Task 1: 创建 typed handler + 契约单测(孤立可提交)

**Files:**
- Create: `src/main/java/com/github/claudecodegui/handler/settings/GetCodexSubscriptionQuotaActionHandler.java`
- Test: `src/test/java/com/github/claudecodegui/handler/settings/GetCodexSubscriptionQuotaActionHandlerTest.java`

**说明:** 此 Task 只新增一个孤立类 + 单测,**尚未注册到 wiring、未删 SUPPORTED_TYPES**。此时 typed handler 不注册 `get_codex_subscription_quota`,旧 `LegacyMessageHandlerAdapter` 仍注册它,无冲突。可独立提交,不破坏现状。

- [ ] **Step 1: 创建 typed handler**

文件 `src/main/java/com/github/claudecodegui/handler/settings/GetCodexSubscriptionQuotaActionHandler.java`:

```java
package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.service.CodexSubscriptionQuotaService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

/**
 * OCP typed handler:取代旧 SettingsHandler 对 get_codex_subscription_quota 的字符串派发
 * + CodexSubscriptionQuotaHandler 委托(AGENTS.md §2 开闭原则)。
 *
 * <p>复用 app 级 {@link CodexSubscriptionQuotaService} 单例,异步取快照后经单一
 * {@code codex.subscription_quota} 事件回传前端(成功/失败共用同一出口,与旧
 * CodexSubscriptionQuotaHandler 逐字等价)。
 */
public final class GetCodexSubscriptionQuotaActionHandler implements FrontendActionHandler<String> {

    private static final Logger LOG = Logger.getInstance(GetCodexSubscriptionQuotaActionHandler.class);
    private static final Gson GSON = new Gson();

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_CODEX_SUBSCRIPTION_QUOTA;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        ApplicationManager.getApplication()
                .getService(CodexSubscriptionQuotaService.class)
                .getQuotaSnapshot()
                .thenAccept(snapshot -> sendPayload(ctx, snapshot))
                .exceptionally(e -> {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    LOG.warn("[GetCodexSubscriptionQuotaActionHandler] Failed to load quota: " + cause.getMessage());
                    sendPayload(ctx, CodexSubscriptionQuotaService.buildUnavailablePayload(
                            cause.getMessage(), System.currentTimeMillis()));
                    return null;
                });
    }

    private void sendPayload(HandlerContext ctx, JsonObject payload) {
        ApplicationManager.getApplication().invokeLater(() ->
                ctx.dispatchEvent("codex.subscription_quota", ctx.escapeJs(GSON.toJson(payload))));
    }
}
```

- [ ] **Step 2: 创建契约单测**

文件 `src/test/java/com/github/claudecodegui/handler/settings/GetCodexSubscriptionQuotaActionHandlerTest.java`:

```java
package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 契约单测:typed handler 必须绑定正确的上行 action + payload 类型。
 *
 * <p>handle() 的异步行为(取快照→invokeLater→dispatchEvent)与旧
 * CodexSubscriptionQuotaHandler 逐字等价(见实现注释),其等价性靠源码对照 +
 * SettingsHandlerTypedWiringTest 的 wiring 守门保证,不在此单测内(纯 JUnit 无
 * IntelliJ Application 环境,无法驱动 ApplicationManager.getApplication())。
 *
 * <p>本项目 testImplementation 仅声明 JUnit 4(build.gradle),沿用同目录既有 JUnit 4 风格。
 */
public class GetCodexSubscriptionQuotaActionHandlerTest {

    @Test
    public void bindsCodexSubscriptionQuotaUpstreamActionWithRawStringPayload() {
        GetCodexSubscriptionQuotaActionHandler handler = new GetCodexSubscriptionQuotaActionHandler();

        assertEquals(UpstreamAction.GET_CODEX_SUBSCRIPTION_QUOTA, handler.action());
        assertEquals(String.class, handler.payloadType());
    }
}
```

- [ ] **Step 3: 跑契约单测,确认通过**

Run: `./gradlew test --tests "com.github.claudecodegui.handler.settings.GetCodexSubscriptionQuotaActionHandlerTest"`
Expected: PASS。handler 绑定 `GET_CODEX_SUBSCRIPTION_QUOTA` 枚举 + `String.class` payload。

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/github/claudecodegui/handler/settings/GetCodexSubscriptionQuotaActionHandler.java \
        src/test/java/com/github/claudecodegui/handler/settings/GetCodexSubscriptionQuotaActionHandlerTest.java
git commit -m "refactor: add typed GetCodexSubscriptionQuotaActionHandler (V9 OCP slice 1/3)"
```

---

## Task 2: 守门测试先行(TDD 红)→ 原子迁移(TDD 绿)

**Files:**
- Modify: `src/test/java/com/github/claudecodegui/handler/settings/SettingsHandlerTypedWiringTest.java`
- Modify: `src/main/java/com/github/claudecodegui/handler/SettingsHandler.java`
- Modify: `src/main/java/com/github/claudecodegui/ui/ChatWindowDelegate.java`

**说明:** 先加守门(红:`get_codex_subscription_quota` 仍在 SUPPORTED_TYPES),再做**原子迁移**(删 SUPPORTED_TYPES 条目 + 删 switch case + 删字段 + 删构造 + wiring 注册),使守门转绿。wiring 注册新 typed handler 与删 SUPPORTED_TYPES 必须同 commit(否则重复检测崩)。

- [ ] **Step 1: 增强 migratedActionsRemainResolvable(加新 action 到已迁移清单)**

在 `SettingsHandlerTypedWiringTest.java` 的 `migratedActionsRemainResolvable()`(:28-35),把数组扩为:

```java
        for (String migrated : new String[]{
                "get_model_registry", "set_model_registry", "reset_model_registry",
                "get_model_registry_schema", "set_appearance_config",
                "get_codex_subscription_quota"
        }) {
            assertTrue(UpstreamAction.fromValue(migrated).isPresent());
        }
```

- [ ] **Step 2: 新增反射守门(断言已迁移 action 已从 SUPPORTED_TYPES 剔除)**

在 `SettingsHandlerTypedWiringTest.java` 顶部 import 区追加:

```java
import com.github.claudecodegui.handler.SettingsHandler;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.assertFalse;
```

并在 `migratedActionsRemainResolvable()` 之后新增测试方法:

```java
    /**
     * 已迁移为 typed FrontendActionHandler 的 action 必须从 SettingsHandler.SUPPORTED_TYPES
     * 剔除,否则 LegacyMessageHandlerAdapter 会为该字符串注册 LegacyActionHandler,与 typed
     * handler 在 FrontendActionDispatcher 构造期重复检测冲突(putIfAbsent 抛
     * IllegalArgumentException,窗口无法启动)。本守门在运行时崩溃之前拦截该回归
     * (AGENTS.md §2 开闭原则)。
     *
     * <p>反射读取 SUPPORTED_TYPES 静态字段,无需构造 SettingsHandler(避免对 IDE
     * Application 环境的依赖)。
     */
    @Test
    public void migratedActionsRemovedFromLegacySupportedTypes() throws Exception {
        Field field = SettingsHandler.class.getDeclaredField("SUPPORTED_TYPES");
        field.setAccessible(true);
        String[] supportedTypes = (String[]) field.get(null);
        Set<String> supported = new HashSet<>(Arrays.asList(supportedTypes));

        for (String migrated : new String[]{
                "get_model_registry", "set_model_registry", "reset_model_registry",
                "get_model_registry_schema", "set_appearance_config",
                "get_codex_subscription_quota"
        }) {
            assertFalse("migrated action '" + migrated + "' must be removed from "
                    + "SettingsHandler.SUPPORTED_TYPES to avoid FrontendActionDispatcher duplicate "
                    + "(AGENTS.md §2 OCP)", supported.contains(migrated));
        }
    }
```

- [ ] **Step 3: 跑守门,确认失败(红)**

Run: `./gradlew test --tests "com.github.claudecodegui.handler.settings.SettingsHandlerTypedWiringTest"`
Expected: **FAIL**。`migratedActionsRemovedFromLegacySupportedTypes` 失败,因 `get_codex_subscription_quota` 仍在 `SettingsHandler.SUPPORTED_TYPES`(:47)。(其余两个既有测试仍 PASS。)

- [ ] **Step 4: 从 SettingsHandler.SUPPORTED_TYPES 删除条目**

在 `src/main/java/com/github/claudecodegui/handler/SettingsHandler.java`,删除 `SUPPORTED_TYPES` 中的这一行(原 :47):

```java
        "get_codex_subscription_quota",
```

(`"get_usage_statistics",`(:46)与 `"get_working_directory",`(:48)之间,删除后两者相邻。)

- [ ] **Step 5: 从 SettingsHandler 删除 switch case**

删除 switch 中的这三行(原 :182-184):

```java
            case "get_codex_subscription_quota":
                codexSubscriptionQuotaHandler.handleGetCodexSubscriptionQuota();
                return true;
```

- [ ] **Step 6: 从 SettingsHandler 删除字段 + 构造赋值**

删除字段声明(原 :33):

```java
    private final CodexSubscriptionQuotaHandler codexSubscriptionQuotaHandler;
```

删除构造赋值(原 :111):

```java
        this.codexSubscriptionQuotaHandler = new CodexSubscriptionQuotaHandler(context);
```

(`SettingsHandler` 与 `CodexSubscriptionQuotaHandler` 同包 `com.github.claudecodegui.handler`,故无 import 需删除。)

- [ ] **Step 7: 在 ChatWindowDelegate 注册新 typed handler**

在 `src/main/java/com/github/claudecodegui/ui/ChatWindowDelegate.java` import 区,按字母序在 :27 `import ...settings.GetModelRegistryActionHandler;` **之前**插入:

```java
import com.github.claudecodegui.handler.settings.GetCodexSubscriptionQuotaActionHandler;
```

在 wiring 块(:318-323),于 :322 `typedHandlers.add(new SetAppearanceConfigActionHandler(appearanceConfigService));` **之后**、:323 `typedHandlers.addAll(LegacyMessageHandlerAdapter.from(...));` **之前**插入:

```java
        typedHandlers.add(new GetCodexSubscriptionQuotaActionHandler());
```

- [ ] **Step 8: 跑守门 + wiring 测试,确认通过(绿)**

Run: `./gradlew test --tests "com.github.claudecodegui.handler.settings.SettingsHandlerTypedWiringTest"`
Expected: PASS。三个测试全绿——`migratedActionsRemovedFromLegacySupportedTypes` 现在 `get_codex_subscription_quota` 已不在 SUPPORTED_TYPES,通过。

- [ ] **Step 9: 提交**

```bash
git add src/test/java/com/github/claudecodegui/handler/settings/SettingsHandlerTypedWiringTest.java \
        src/main/java/com/github/claudecodegui/handler/SettingsHandler.java \
        src/main/java/com/github/claudecodegui/ui/ChatWindowDelegate.java
git commit -m "refactor: migrate get_codex_subscription_quota to typed handler (V9 OCP slice 2/3)"
```

---

## Task 3: 删除旧委托类 + 全量验证

**Files:**
- Delete: `src/main/java/com/github/claudecodegui/handler/CodexSubscriptionQuotaHandler.java`

**说明:** Task 2 已从 SettingsHandler 移除对 `CodexSubscriptionQuotaHandler` 的所有引用。此时该类应仅剩自身文件(无引用)。删除前用 grep 复核。

- [ ] **Step 1: 复核无残留引用**

Run: `git grep -n "CodexSubscriptionQuotaHandler" -- 'src'`
Expected: 仅剩 `CodexSubscriptionQuotaHandler.java` 自身(文件内的类名/Logger getInstance 自引用)。**不应**再有 `SettingsHandler.java` 或其他文件引用。若仍有其他引用,STOP 并排查,不得继续删除。

- [ ] **Step 2: 删除旧类文件**

```bash
git rm src/main/java/com/github/claudecodegui/handler/CodexSubscriptionQuotaHandler.java
```

- [ ] **Step 3: 全量后端测试,确认无回归**

Run: `./gradlew test`
Expected: 全绿。重点关注:`GetCodexSubscriptionQuotaActionHandlerTest`、`SettingsHandlerTypedWiringTest`、`FrontendActionDispatcherTest`(若存在)。

> 注:若全量 `gradlew test` 出现与本切片无关的既有失败(如系统性 NPE 环境),需如 Phase 1 验证那样用 `git stash` + `git checkout` 对照证明非本切片引入;本切片改动范围内的测试必须全绿。

- [ ] **Step 4: 提交**

```bash
git commit -m "refactor: remove legacy CodexSubscriptionQuotaHandler after typed migration (V9 OCP slice 3/3)"
```

---

## 验收

- [ ] `./gradlew test --tests "...GetCodexSubscriptionQuotaActionHandlerTest"` PASS。
- [ ] `./gradlew test --tests "...SettingsHandlerTypedWiringTest"` 三个测试 PASS(含新增反射守门)。
- [ ] `git grep -n "CodexSubscriptionQuotaHandler" -- 'src'` 仅在 git rm 后无任何输出(旧类已删,无残留引用)。
- [ ] `SettingsHandler.SUPPORTED_TYPES` 不再含 `get_codex_subscription_quota`(反射守门锁定)。
- [ ] `ChatWindowDelegate` 在 `LegacyMessageHandlerAdapter.from(...)` 之前注册了 `GetCodexSubscriptionQuotaActionHandler`。
- [ ] 3 个 commit 已提交,各自可独立 revert(注:Task 2 的 wiring 与 SUPPORTED_TYPES 删除绑在同一 commit 是刻意的——分离会导致中间状态重复检测崩溃)。
- [ ] 前端无需改动(`get_codex_subscription_quota` 的上行调用与 `codex.subscription_quota` 下行监听协议不变)。

---

## 自审(writing-plans skill 要求)

1. **Spec 覆盖:** 目标(`get_codex_subscription_quota` → typed handler + 删旧类)→ Task 1(建 handler)+ Task 2(迁移守门 + 删 SUPPORTED_TYPES/case/字段/构造 + wiring)+ Task 3(删旧类 + 验证)。覆盖完整,无遗漏。AGENTS.md §2(开闭)由"新增 action 走 typed handler 注册、不改既有 switch 数组"落地。
2. **占位符扫描:** 无 TBD/TODO;所有步骤含完整代码块、精确文件:行号、可运行命令与预期输出。Task 1 handler 与单测、Task 2 守门与四处删除 + wiring、Task 3 grep 复核 + git rm 均为真实可执行内容。
3. **类型一致性:** `UpstreamAction.GET_CODEX_SUBSCRIPTION_QUOTA`(枚举 :98)在 handler `action()`、契约单测、wiring 守门三处一致;`FrontendActionHandler<String>` + `payloadType() == String.class` 在 handler 与单测一致;`get_codex_subscription_quota` 字符串在守门、SUPPORTED_TYPES 删除、case 删除三处一致。反射字段名 `SUPPORTED_TYPES` 与 SettingsHandler:35 声明一致。
4. **重复检测硬约束已处理:** Task 2 将"wiring 注册 typed handler"与"删 SUPPORTED_TYPES 条目"绑在同一 commit(Step 4-9 同 commit),Step 8 跑守门验证无重复。中间不可分离提交(Task 验收已注明)。
5. **纪律边界已声明:** 本切片不夹带魔法字符串→枚举化重构(说明段 + handler 注释),保持与旧实现逐字等价,降低迁移 diff 表面。
