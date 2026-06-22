# V9 派发器 OCP 统一 · 第二切片(Claude CLI 路径迁移)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `get_claude_cli_path` / `set_claude_cli_path` 从 `SettingsHandler`(SUPPORTED_TYPES 字符串数组 + switch + 委托 `ClaudeCliPathHandler`)迁移为符合开闭原则的两个 `FrontendActionHandler<String>` 实现,并删除旧的 `ClaudeCliPathHandler` 委托类及其测试——继续落地 `AGENTS.md` 总则二(开闭原则,新增 action 不改既有派发器)。

**Architecture:** 每个 action 一个 typed handler(与第一切片"1 action 1 handler"风格一致,因 `FrontendActionHandler.action()` 返回单个 `UpstreamAction`,get/set 不可合并)。`GetClaudeCliPathActionHandler` 读取 `PropertiesComponent` 回传 `config.claude_cli_path`;`SetClaudeCliPathActionHandler` 校验路径→持久化→关闭 daemon→回传 `config.claude_cli_path` + `toast`。两者均把原 handler 内联的业务逻辑**逐字搬入** typed handler(异步出口、`shutdownDaemon` try/catch 兜底、失败回显用户输入 全部保留)。get/set 逻辑无独立 service 可复用(区别于第一切片的 `CodexSubscriptionQuotaService`),直接整段搬入,不抽 service。迁移核心约束(继承第一切片):**新增 typed handler 注册 `get_claude_cli_path`/`set_claude_cli_path` 与从 `SettingsHandler.SUPPORTED_TYPES` 剔除这两条必须同 commit**——否则 `LegacyMessageHandlerAdapter` 仍会为这两个字符串注册 `LegacyActionHandler`,与 typed handler 在 `FrontendActionDispatcher` 构造期 `putIfAbsent` 重复检测冲突,抛 `IllegalArgumentException` 致窗口无法启动。守门测试用反射读取 `SUPPORTED_TYPES` 静态字段,**在运行时崩溃之前**拦截这种回归。

**Tech Stack:** Java 17 + IntelliJ Platform + Gson + JUnit 4(本项目 testImplementation 仅声明 `junit:junit:4.13.2`,无 Jupiter)。

**依据:** `AGENTS.md` 总则二(开闭原则)、第 6 节合规检查清单第 4 条(派发器对扩展开放)。本切片是 V9 的**第二切片**(第一切片 `2026-06-21-v9-dispatcher-codex-quota-slice.md` 已落地)。选 `ClaudeCliPathHandler` 因其副作用单一(仅 `shutdownDaemon` 带 try/catch)、事件编排简单(get 1 事件 / set 最多 2 事件、无 `node.check_env` 级联)、校验已抽成可单测的 static method(`validateCliPath`,已有 4 个 JUnit 用例可平移)、逻辑可直接整段搬入无需抽 service。同批次候选 `NodePathHandler` 副作用更重(双 bridge 联动 + 3 事件级联),留作后续切片。

---

## 前置事实(已排查确认,执行者无需重复调查)

| 事实 | 位置 | 说明 |
|---|---|---|
| 上行枚举已存在 | `protocol/UpstreamAction.java:72-73` | `GET_CLAUDE_CLI_PATH("get_claude_cli_path")` / `SET_CLAUDE_CLI_PATH("set_claude_cli_path")` |
| 下行枚举已存在 | `protocol/DownstreamEvent.java:93,84,87` | `CONFIG_CLAUDE_CLI_PATH("config.claude_cli_path")`(get/set 共用)/ `TOAST_ERROR("toast.error")` / `TOAST_SWITCH_SUCCESS("toast.switch_success")` |
| 旧 get 实现 | `handler/ClaudeCliPathHandler.java:39-60` | `handleGetClaudeCliPath()`:`CompletableFuture.runAsync` 读 props → `invokeLater` 派 `config.claude_cli_path`;失败派 `toast.error`(文案英文) |
| 旧 set 实现 | `handler/ClaudeCliPathHandler.java:67-150` | `handleSetClaudeCliPath(String)`:CEF 线程解析 JSON → 后台线程校验/写 props/`shutdownDaemon`(try/catch)→ `invokeLater` 派 `config.claude_cli_path` + `toast.switch_success`/`toast.error`;失败时回显用户输入 |
| 校验 static method | `handler/ClaudeCliPathHandler.java:158-169` | package-private `static String validateCliPath(File, String)`:不存在/目录/非可执行返回原因,合法返回 null。专为单测抽取(类注释 :152-157) |
| 旧校验单测 | `src/test/java/com/github/claudecodegui/handler/ClaudeCliPathHandlerTest.java` | 4 个 JUnit 用例 :27-69,本切片平移到新 handler 包 |
| 旧 handler 无 service | — | get/set 全内联;仅依赖 `HandlerContext` + `PropertiesComponent`(IDE 静态)+ `ClaudeSDKBridge.shutdownDaemon()` |
| SUPPORTED_TYPES 条目 | `handler/SettingsHandler.java:43-44` | `"get_claude_cli_path",` / `"set_claude_cli_path",`(位于 `set_node_path`(:42)与 `get_usage_statistics`(:45)之间) |
| 旧 handler 委托点 | `handler/SettingsHandler.java` | 字段 :31 / 构造 :107 / switch case :169-174 |
| SUPPORTED_TYPES 可反射 | `handler/SettingsHandler.java:34` | `private static final String[] SUPPORTED_TYPES` |
| 迁移模板 | `handler/settings/GetCodexSubscriptionQuotaActionHandler.java` | 第一切片产物,三 override + 静态 `GSON` |
| 重复检测硬约束 | `handler/core/FrontendActionDispatcher.java:18` | 构造期 `putIfAbsent`,重复 action 抛 `IllegalArgumentException` |
| adapter 传导链 | `handler/core/LegacyMessageHandlerAdapter.java:14-17` | `from()` 遍历 `getSupportedTypes()`,对可被 `UpstreamAction.fromValue()` 解析的每个字符串包装成 `LegacyActionHandler` 注册 |
| 框架接口 | `handler/core/FrontendActionHandler.java` | `UpstreamAction action()` / `Class<T> payloadType()` / `void handle(T, FrontendActionContext)` |
| context 取值 | `handler/core/FrontendActionContext.java` | `context.handlerContext()` 返回 `HandlerContext`(含 `dispatchEvent`/`escapeJs`/`getClaudeSDKBridge`) |
| wiring 注册点 | `ui/ChatWindowDelegate.java:318-325` | `typedHandlers.add(...)` 在 :319-324;`LegacyMessageHandlerAdapter.from(...)` 在 :325 |

**删除安全性:** 全 `src` grep `ClaudeCliPathHandler` 仅命中 `SettingsHandler.java`(字段+构造+case)+ `ClaudeCliPathHandler.java`(自身)+ `ClaudeCliPathHandlerTest.java`(测试)+ `ClaudeCliPathHandler.java:21` Javadoc `{@link NodePathHandler}`(指 NodePath,**不**指 ClaudeCliPath 的反向引用,本切片不迁 NodePath 不受影响)。生产代码无其它引用。

**本切片纪律(继承第一切片):** 迁移只做"搬入 OCP 框架",**不夹带**额外重构。`dispatchEvent` 仍用字符串字面量 `"config.claude_cli_path"` / `"toast.error"` / `"toast.switch_success"`(与旧实现逐字等价);错误 toast 文案保持英文(逐字等价);魔法字符串→`DownstreamEvent` 枚举化属独立 SSOT 优化,留后续切片,不混入本迁移。`CLAUDE_CLI_PATH_PROPERTY_KEY` 旧为 `public static final`(排查确认仅旧 handler 自身引用),迁移后两 handler 各自声明 `private static final`(接受这一处常量重复,与旧单类结构等价;集中化留后续)。

---

## File Structure

| 文件 | 操作 | 职责 |
|---|---|---|
| `src/main/java/com/github/claudecodegui/handler/settings/GetClaudeCliPathActionHandler.java` | Create | typed handler,绑定 `GET_CLAUDE_CLI_PATH`,读 props 回传 `config.claude_cli_path` |
| `src/main/java/com/github/claudecodegui/handler/settings/SetClaudeCliPathActionHandler.java` | Create | typed handler,绑定 `SET_CLAUDE_CLI_PATH`,校验→持久化→`shutdownDaemon`→回传 + `validateCliPath` static |
| `src/test/java/com/github/claudecodegui/handler/settings/GetClaudeCliPathActionHandlerTest.java` | Create | 契约单测:断言 `action()`/`payloadType()` 绑定 |
| `src/test/java/com/github/claudecodegui/handler/settings/SetClaudeCliPathActionHandlerTest.java` | Create | 契约单测 + `validateCliPath` 四分支(从旧测试平移) |
| `src/test/java/com/github/claudecodegui/handler/settings/SettingsHandlerTypedWiringTest.java` | Modify | 守门:两清单各追加 `get_claude_cli_path`/`set_claude_cli_path` |
| `src/main/java/com/github/claudecodegui/handler/SettingsHandler.java` | Modify | 删 SUPPORTED_TYPES :43-44 + switch case :169-174 + 字段 :31 + 构造 :107 |
| `src/main/java/com/github/claudecodegui/ui/ChatWindowDelegate.java` | Modify | 加 2 import + wiring 注册 2 行(:324 后、:325 前) |
| `src/main/java/com/github/claudecodegui/handler/ClaudeCliPathHandler.java` | Delete | 旧委托类,迁移后无引用 |
| `src/test/java/com/github/claudecodegui/handler/ClaudeCliPathHandlerTest.java` | Delete | 旧测试,引用旧 handler 的 `validateCliPath`,删类后编译失败须同删 |

---

## Task 1: 创建 2 个 typed handler + 测试(孤立可提交)

**Files:**
- Create: `src/main/java/com/github/claudecodegui/handler/settings/GetClaudeCliPathActionHandler.java`
- Create: `src/main/java/com/github/claudecodegui/handler/settings/SetClaudeCliPathActionHandler.java`
- Create: `src/test/java/com/github/claudecodegui/handler/settings/GetClaudeCliPathActionHandlerTest.java`
- Create: `src/test/java/com/github/claudecodegui/handler/settings/SetClaudeCliPathActionHandlerTest.java`

**说明:** 此 Task 只新增 2 个孤立类 + 2 个测试,**尚未注册到 wiring、未删 SUPPORTED_TYPES**。此时 typed handler 不注册 `get_claude_cli_path`/`set_claude_cli_path`,旧 `LegacyMessageHandlerAdapter` 仍注册它们,无冲突。旧 `ClaudeCliPathHandler` 与旧 `ClaudeCliPathHandlerTest` 此时仍存在并编译通过(与新 handler 的同名 `validateCliPath` 互不干扰,分属不同类)。可独立提交,不破坏现状。

- [ ] **Step 1: 创建 GetClaudeCliPathActionHandler**

文件 `src/main/java/com/github/claudecodegui/handler/settings/GetClaudeCliPathActionHandler.java`:

```java
package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.concurrent.CompletableFuture;

/**
 * OCP typed handler:取代旧 SettingsHandler 对 get_claude_cli_path 的字符串派发
 * + ClaudeCliPathHandler.handleGetClaudeCliPath 委托(AGENTS.md §2 开闭原则)。
 *
 * <p>从 {@link PropertiesComponent} 读取已保存的 Claude CLI 路径(空串表示未设置),
 * 异步经单一 {@code config.claude_cli_path} 事件回传前端,与旧实现逐字等价。
 */
public final class GetClaudeCliPathActionHandler implements FrontendActionHandler<String> {

    private static final Logger LOG = Logger.getInstance(GetClaudeCliPathActionHandler.class);
    private static final Gson GSON = new Gson();
    private static final String CLAUDE_CLI_PATH_PROPERTY_KEY = "claude.code.cli.path";

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_CLAUDE_CLI_PATH;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        CompletableFuture.runAsync(() -> {
            try {
                String saved = PropertiesComponent.getInstance().getValue(CLAUDE_CLI_PATH_PROPERTY_KEY);
                String pathToSend = (saved != null) ? saved.trim() : "";

                ApplicationManager.getApplication().invokeLater(() -> {
                    JsonObject response = new JsonObject();
                    response.addProperty("path", pathToSend);
                    ctx.dispatchEvent("config.claude_cli_path", ctx.escapeJs(GSON.toJson(response)));
                });
            } catch (Exception e) {
                LOG.error("[GetClaudeCliPathActionHandler] Failed to get Claude CLI path: " + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() ->
                        ctx.dispatchEvent("toast.error", ctx.escapeJs("Failed to load Claude CLI path: " + e.getMessage()))
                );
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[GetClaudeCliPathActionHandler] Unexpected error in handle: " + ex.getMessage(), ex);
            return null;
        });
    }
}
```

- [ ] **Step 2: 创建 SetClaudeCliPathActionHandler**

文件 `src/main/java/com/github/claudecodegui/handler/settings/SetClaudeCliPathActionHandler.java`:

```java
package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * OCP typed handler:取代旧 SettingsHandler 对 set_claude_cli_path 的字符串派发
 * + ClaudeCliPathHandler.handleSetClaudeCliPath 委托(AGENTS.md §2 开闭原则)。
 *
 * <p>校验路径(存在/非目录/可执行)→ 持久化到 {@link PropertiesComponent} → 关闭 daemon
 * 使下次请求以新的 CLAUDE_CODE_PATH 重启 → 经 {@code config.claude_cli_path} + {@code toast}
 * 事件回传前端,与旧实现逐字等价。失败时回显用户输入而非清空。
 */
public final class SetClaudeCliPathActionHandler implements FrontendActionHandler<String> {

    private static final Logger LOG = Logger.getInstance(SetClaudeCliPathActionHandler.class);
    private static final Gson GSON = new Gson();
    private static final String CLAUDE_CLI_PATH_PROPERTY_KEY = "claude.code.cli.path";

    @Override
    public UpstreamAction action() {
        return UpstreamAction.SET_CLAUDE_CLI_PATH;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        // JSON 解析在调用线程(CEF IO)同步做,纯解析无 I/O;校验/写盘/进程派生放后台线程
        String parsedPath = null;
        try {
            JsonObject json = GSON.fromJson(payload, JsonObject.class);
            if (json != null && json.has("path") && !json.get("path").isJsonNull()) {
                parsedPath = json.get("path").getAsString();
            }
        } catch (Exception e) {
            LOG.error("[SetClaudeCliPathActionHandler] Failed to parse set_claude_cli_path content: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                    ctx.dispatchEvent("toast.error", ctx.escapeJs("Failed to save Claude CLI path: " + e.getMessage()))
            );
            return;
        }
        final String pathArg = (parsedPath != null) ? parsedPath.trim() : null;

        CompletableFuture.runAsync(() -> {
            try {
                PropertiesComponent props = PropertiesComponent.getInstance();
                String finalPath = "";
                boolean success = false;
                String failureMsg = null;

                if (pathArg == null || pathArg.isEmpty()) {
                    props.unsetValue(CLAUDE_CLI_PATH_PROPERTY_KEY);
                    LOG.info("[SetClaudeCliPathActionHandler] Cleared custom Claude CLI path");
                    success = true;
                } else {
                    failureMsg = validateCliPath(new File(pathArg), pathArg);
                    if (failureMsg == null) {
                        props.setValue(CLAUDE_CLI_PATH_PROPERTY_KEY, pathArg);
                        finalPath = pathArg;
                        success = true;
                        LOG.info("[SetClaudeCliPathActionHandler] Saved custom Claude CLI path: " + pathArg);
                    }
                }

                // 重启 daemon 使 CLAUDE_CODE_PATH 在下次请求时重新注入(env 仅在 spawn 时读)。
                // shutdownDaemon 安全:下次请求经 ClaudeDaemonCoordinator 触发全新启动。
                if (success) {
                    try {
                        ctx.getClaudeSDKBridge().shutdownDaemon();
                    } catch (Exception e) {
                        LOG.warn("[SetClaudeCliPathActionHandler] Failed to shutdown daemon after path change: " + e.getMessage());
                    }
                }

                final boolean successFlag = success;
                final String failureMsgFinal = failureMsg;
                final String finalPathToSend = finalPath;
                // 失败时回显用户输入,避免输入框被清空;成功时回显持久化的值
                final String pathToEcho = successFlag
                        ? finalPathToSend
                        : (pathArg != null ? pathArg : "");

                ApplicationManager.getApplication().invokeLater(() -> {
                    JsonObject response = new JsonObject();
                    response.addProperty("path", pathToEcho);
                    ctx.dispatchEvent("config.claude_cli_path", ctx.escapeJs(GSON.toJson(response)));

                    if (successFlag) {
                        String msg = finalPathToSend.isEmpty()
                                ? "Claude CLI path cleared, using bundled SDK"
                                : "Claude CLI path saved: " + finalPathToSend;
                        ctx.dispatchEvent("toast.switch_success", ctx.escapeJs(msg));
                    } else {
                        String msg = failureMsgFinal != null ? failureMsgFinal : "Invalid Claude CLI path";
                        ctx.dispatchEvent("toast.error", ctx.escapeJs(msg));
                    }
                });
            } catch (Exception e) {
                LOG.error("[SetClaudeCliPathActionHandler] Failed to set Claude CLI path: " + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() ->
                        ctx.dispatchEvent("toast.error", ctx.escapeJs("Failed to save Claude CLI path: " + e.getMessage()))
                );
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[SetClaudeCliPathActionHandler] Unexpected error in handle: " + ex.getMessage(), ex);
            return null;
        });
    }

    /**
     * Validates a candidate Claude CLI path. Returns {@code null} when the path is a
     * usable executable file, otherwise a human-readable reason. Extracted as a pure
     * static method so the validation branches can be unit-tested without booting the
     * IntelliJ platform (the handler itself depends on {@link PropertiesComponent}).
     */
    static String validateCliPath(File f, String rawPath) {
        if (!f.exists()) {
            return "File does not exist: " + rawPath;
        }
        if (f.isDirectory()) {
            return "Path is a directory, expected an executable file: " + rawPath;
        }
        if (!f.canExecute()) {
            return "File is not executable (check permissions): " + rawPath;
        }
        return null;
    }
}
```

- [ ] **Step 3: 创建 GetClaudeCliPathActionHandlerTest(契约)**

文件 `src/test/java/com/github/claudecodegui/handler/settings/GetClaudeCliPathActionHandlerTest.java`:

```java
package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 契约单测:typed handler 必须绑定正确的上行 action + payload 类型。
 *
 * <p>handle() 的异步行为(读 PropertiesComponent → invokeLater → dispatchEvent)与旧
 * ClaudeCliPathHandler.handleGetClaudeCliPath 逐字等价(见实现注释),其等价性靠源码
 * 对照 + SettingsHandlerTypedWiringTest 的 wiring 守门保证,不在此单测内(纯 JUnit 无
 * IntelliJ Application 环境,无法驱动 ApplicationManager.invokeLater / PropertiesComponent)。
 *
 * <p>本项目 testImplementation 仅声明 JUnit 4(build.gradle),沿用同目录既有 JUnit 4 风格。
 */
public class GetClaudeCliPathActionHandlerTest {

    @Test
    public void bindsGetClaudeCliPathUpstreamActionWithRawStringPayload() {
        GetClaudeCliPathActionHandler handler = new GetClaudeCliPathActionHandler();

        assertEquals(UpstreamAction.GET_CLAUDE_CLI_PATH, handler.action());
        assertEquals(String.class, handler.payloadType());
    }
}
```

- [ ] **Step 4: 创建 SetClaudeCliPathActionHandlerTest(契约 + validateCliPath 四分支)**

文件 `src/test/java/com/github/claudecodegui/handler/settings/SetClaudeCliPathActionHandlerTest.java`:

```java
package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 契约单测 + 纯路径校验分支单测。
 *
 * <p>契约部分断言 typed handler 绑定 SET_CLAUDE_CLI_PATH + String payload。
 *
 * <p>校验部分覆盖 {@link SetClaudeCliPathActionHandler#validateCliPath(File, String)} 的四条
 * 分支(不存在 / 目录 / 非可执行 / 合法可执行),从旧 ClaudeCliPathHandlerTest 平移而来。
 * 这些用例守护"非法路径在持久化与重启 daemon 之前被拒绝"的安全/UX 不变量。
 * handle() 的异步持久化 + daemon 重启行为靠源码对照 + wiring 守门保证,不在此单测内。
 *
 * <p>本项目 testImplementation 仅声明 JUnit 4(build.gradle),沿用同目录既有 JUnit 4 风格。
 */
public class SetClaudeCliPathActionHandlerTest {

    @Test
    public void bindsSetClaudeCliPathUpstreamActionWithRawStringPayload() {
        SetClaudeCliPathActionHandler handler = new SetClaudeCliPathActionHandler();

        assertEquals(UpstreamAction.SET_CLAUDE_CLI_PATH, handler.action());
        assertEquals(String.class, handler.payloadType());
    }

    @Test
    public void validateRejectsNonExistentFile() {
        File missing = new File(System.getProperty("java.io.tmpdir"), "cc-gui-claude-cli-missing-zzz");
        String reason = SetClaudeCliPathActionHandler.validateCliPath(missing, missing.getPath());
        assertNotNull("A non-existent path must be rejected", reason);
        assertTrue("Reason should explain the file is missing: " + reason,
                reason.startsWith("File does not exist"));
    }

    @Test
    public void validateRejectsDirectory() throws IOException {
        File dir = Files.createTempDirectory("cc-gui-claude-cli-dir").toFile();
        dir.deleteOnExit();
        String reason = SetClaudeCliPathActionHandler.validateCliPath(dir, dir.getPath());
        assertNotNull("A directory must be rejected", reason);
        assertTrue("Reason should explain the path is a directory: " + reason,
                reason.startsWith("Path is a directory"));
    }

    @Test
    public void validateRejectsNonExecutableFile() throws IOException {
        File file = Files.createTempFile("cc-gui-claude-cli-noexec", ".bin").toFile();
        file.deleteOnExit();
        file.setExecutable(false, false);
        // Some filesystems / privileged users cannot represent a non-executable regular
        // file (canExecute stays true); skip rather than fail spuriously in that case.
        Assume.assumeFalse("Filesystem cannot strip the execute bit", file.canExecute());

        String reason = SetClaudeCliPathActionHandler.validateCliPath(file, file.getPath());
        assertNotNull("A non-executable file must be rejected", reason);
        assertTrue("Reason should explain the file is not executable: " + reason,
                reason.startsWith("File is not executable"));
    }

    @Test
    public void validateAcceptsExecutableFile() throws IOException {
        File file = Files.createTempFile("cc-gui-claude-cli-ok", ".sh").toFile();
        file.deleteOnExit();
        assertTrue("Test precondition: set the execute bit", file.setExecutable(true, false));

        String reason = SetClaudeCliPathActionHandler.validateCliPath(file, file.getPath());
        assertNull("A usable executable file must pass validation, got: " + reason, reason);
    }
}
```

- [ ] **Step 5: 跑两个新单测,确认通过**

Run: `./gradlew test --tests "com.github.claudecodegui.handler.settings.GetClaudeCliPathActionHandlerTest" --tests "com.github.claudecodegui.handler.settings.SetClaudeCliPathActionHandlerTest"`
Expected: PASS。Get 契约绑定 `GET_CLAUDE_CLI_PATH` + `String.class`;Set 契约绑定 `SET_CLAUDE_CLI_PATH` + `String.class`,且 validateCliPath 四分支(其中非可执行分支在本机若无法剥离执行位会 skip,非失败)。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/github/claudecodegui/handler/settings/GetClaudeCliPathActionHandler.java \
        src/main/java/com/github/claudecodegui/handler/settings/SetClaudeCliPathActionHandler.java \
        src/test/java/com/github/claudecodegui/handler/settings/GetClaudeCliPathActionHandlerTest.java \
        src/test/java/com/github/claudecodegui/handler/settings/SetClaudeCliPathActionHandlerTest.java
git commit -m "refactor: add typed Claude CLI path action handlers (V9 OCP slice 1/3)"
```

---

## Task 2: 守门测试先行(TDD 红)→ 原子迁移(TDD 绿)

**Files:**
- Modify: `src/test/java/com/github/claudecodegui/handler/settings/SettingsHandlerTypedWiringTest.java`
- Modify: `src/main/java/com/github/claudecodegui/handler/SettingsHandler.java`
- Modify: `src/main/java/com/github/claudecodegui/ui/ChatWindowDelegate.java`

**说明:** 先扩守门(红:`get_claude_cli_path`/`set_claude_cli_path` 仍在 SUPPORTED_TYPES),再做**原子迁移**(删 SUPPORTED_TYPES 两条 + 删 switch case + 删字段 + 删构造 + wiring 注册 2 行),使守门转绿。wiring 注册 2 个 typed handler 与删 SUPPORTED_TYPES 必须同 commit(否则重复检测崩)。

- [ ] **Step 1: 扩展 migratedActionsRemainResolvable(加 2 个 action 到已迁移清单)**

在 `SettingsHandlerTypedWiringTest.java` 的 `migratedActionsRemainResolvable()`(:33-41),把数组扩为:

```java
        for (String migrated : new String[]{
                "get_model_registry", "set_model_registry", "reset_model_registry",
                "get_model_registry_schema", "set_appearance_config",
                "get_codex_subscription_quota",
                "get_claude_cli_path", "set_claude_cli_path"
        }) {
            assertTrue(UpstreamAction.fromValue(migrated).isPresent());
        }
```

- [ ] **Step 2: 扩展反射守门 migratedActionsRemovedFromLegacySupportedTypes(加同样 2 项)**

在 `SettingsHandlerTypedWiringTest.java` 的 `migratedActionsRemovedFromLegacySupportedTypes()`(:54-69),把反射守门数组扩为:

```java
        for (String migrated : new String[]{
                "get_model_registry", "set_model_registry", "reset_model_registry",
                "get_model_registry_schema", "set_appearance_config",
                "get_codex_subscription_quota",
                "get_claude_cli_path", "set_claude_cli_path"
        }) {
            assertFalse("migrated action '" + migrated + "' must be removed from "
                    + "SettingsHandler.SUPPORTED_TYPES to avoid FrontendActionDispatcher duplicate "
                    + "(AGENTS.md §2 OCP)", supported.contains(migrated));
        }
```

(import 区已有 `SettingsHandler`/`Field`/`Arrays`/`HashSet`/`Set`/`assertFalse`,第一切片已加,无需再加。)

- [ ] **Step 3: 跑守门,确认失败(红)**

Run: `./gradlew test --tests "com.github.claudecodegui.handler.settings.SettingsHandlerTypedWiringTest"`
Expected: **FAIL**。`migratedActionsRemovedFromLegacySupportedTypes` 失败,因 `get_claude_cli_path`/`set_claude_cli_path` 仍在 `SettingsHandler.SUPPORTED_TYPES`(:43-44)。(其余既有测试仍 PASS。)

- [ ] **Step 4: 从 SettingsHandler.SUPPORTED_TYPES 删除 2 条目**

在 `src/main/java/com/github/claudecodegui/handler/SettingsHandler.java`,删除这两行(原 :43-44):

```java
        "get_claude_cli_path",
        "set_claude_cli_path",
```

(`"set_node_path",`(:42)与 `"get_usage_statistics",`(:45)之间,删除后两者相邻。)

- [ ] **Step 5: 从 SettingsHandler 删除 switch case**

删除 switch 中的这 6 行(原 :168-174,含注释行 `// Claude CLI path`):

```java
            // Claude CLI path
            case "get_claude_cli_path":
                claudeCliPathHandler.handleGetClaudeCliPath();
                return true;
            case "set_claude_cli_path":
                claudeCliPathHandler.handleSetClaudeCliPath(content);
                return true;
```

- [ ] **Step 6: 从 SettingsHandler 删除字段 + 构造赋值**

删除字段声明(原 :31):

```java
    private final ClaudeCliPathHandler claudeCliPathHandler;
```

删除构造赋值(原 :107):

```java
        this.claudeCliPathHandler = new ClaudeCliPathHandler(context);
```

(`SettingsHandler` 与 `ClaudeCliPathHandler` 同包 `com.github.claudecodegui.handler`,故无 import 需删除。)

- [ ] **Step 7: 在 ChatWindowDelegate 注册 2 个新 typed handler**

在 `src/main/java/com/github/claudecodegui/ui/ChatWindowDelegate.java` import 区,按字母序插入两条(`Claude` < `Codex`,`Claude` < `Model`):

```java
import com.github.claudecodegui.handler.settings.GetClaudeCliPathActionHandler;
```
(放在 `import com.github.claudecodegui.handler.settings.GetCodexSubscriptionQuotaActionHandler;` **之前**)

```java
import com.github.claudecodegui.handler.settings.SetClaudeCliPathActionHandler;
```
(放在 `import com.github.claudecodegui.handler.settings.SetAppearanceConfigActionHandler;` **之后**、`SetModelRegistryActionHandler` import **之前**)

在 wiring 块(:318-325),于 :324 `typedHandlers.add(new GetCodexSubscriptionQuotaActionHandler());` **之后**、:325 `typedHandlers.addAll(LegacyMessageHandlerAdapter.from(...));` **之前**插入:

```java
        typedHandlers.add(new GetClaudeCliPathActionHandler());
        typedHandlers.add(new SetClaudeCliPathActionHandler());
```

- [ ] **Step 8: 跑守门 + wiring 测试,确认通过(绿)**

Run: `./gradlew test --tests "com.github.claudecodegui.handler.settings.SettingsHandlerTypedWiringTest"`
Expected: PASS。三个测试全绿——`migratedActionsRemovedFromLegacySupportedTypes` 现在 `get_claude_cli_path`/`set_claude_cli_path` 已不在 SUPPORTED_TYPES,通过。

- [ ] **Step 9: 提交**

```bash
git add src/test/java/com/github/claudecodegui/handler/settings/SettingsHandlerTypedWiringTest.java \
        src/main/java/com/github/claudecodegui/handler/SettingsHandler.java \
        src/main/java/com/github/claudecodegui/ui/ChatWindowDelegate.java
git commit -m "refactor: migrate claude cli path actions to typed handlers (V9 OCP slice 2/3)"
```

---

## Task 3: 删除旧委托类 + 旧测试 + 全量验证

**Files:**
- Delete: `src/main/java/com/github/claudecodegui/handler/ClaudeCliPathHandler.java`
- Delete: `src/test/java/com/github/claudecodegui/handler/ClaudeCliPathHandlerTest.java`

**说明:** Task 2 已从 SettingsHandler 移除对 `ClaudeCliPathHandler` 的所有引用。此时该类仅剩自身 + 旧测试引用它。旧测试引用 `ClaudeCliPathHandler.validateCliPath`,删类后编译失败,故必须与删类**同 commit** 删除。删除前用 grep 复核。

- [ ] **Step 1: 复核无残留生产引用**

Run: `git grep -n "ClaudeCliPathHandler" -- 'src/main'`
Expected: 仅剩 `ClaudeCliPathHandler.java` 自身(文件内的类名/Logger getInstance/Javadoc 自引用)。**不应**再有 `SettingsHandler.java` 或其他生产文件引用。若仍有其他生产引用,STOP 并排查,不得继续删除。

(注:`src/test` 中 `ClaudeCliPathHandlerTest.java` 仍引用它,属预期——本 Task 同步删除该测试。)

- [ ] **Step 2: 删除旧类 + 旧测试文件**

```bash
git rm src/main/java/com/github/claudecodegui/handler/ClaudeCliPathHandler.java
git rm src/test/java/com/github/claudecodegui/handler/ClaudeCliPathHandlerTest.java
```

- [ ] **Step 3: 全量后端测试,确认无回归**

Run: `./gradlew test`
Expected: 全绿。重点关注:`GetClaudeCliPathActionHandlerTest`、`SetClaudeCliPathActionHandlerTest`、`SettingsHandlerTypedWiringTest`。

> 注:若全量 `gradlew test` 出现与本切片无关的既有失败(系统性 NPE 环境,基线 33 个,见第一切片记录),需用 `git stash` + `git checkout` 对照证明非本切片引入;本切片改动范围内的测试必须全绿。

- [ ] **Step 4: 提交**

```bash
git commit -m "refactor: remove legacy ClaudeCliPathHandler after typed migration (V9 OCP slice 3/3)"
```

---

## 验收

- [ ] `./gradlew test --tests "...GetClaudeCliPathActionHandlerTest"` PASS。
- [ ] `./gradlew test --tests "...SetClaudeCliPathActionHandlerTest"` PASS(契约 + validateCliPath 四分支)。
- [ ] `./gradlew test --tests "...SettingsHandlerTypedWiringTest"` 三个测试 PASS(两清单均含 `get_claude_cli_path`/`set_claude_cli_path`)。
- [ ] `git grep -n "ClaudeCliPathHandler" -- 'src'` 在 git rm 后无任何输出(旧类 + 旧测试已删,无残留引用)。
- [ ] `SettingsHandler.SUPPORTED_TYPES` 不再含 `get_claude_cli_path`/`set_claude_cli_path`(反射守门锁定)。
- [ ] `ChatWindowDelegate` 在 `LegacyMessageHandlerAdapter.from(...)` 之前注册了 `GetClaudeCliPathActionHandler` + `SetClaudeCliPathActionHandler`。
- [ ] 3 个 commit 已提交,各自可独立 revert(注:Task 2 的 wiring 与 SUPPORTED_TYPES 删除绑在同一 commit 是刻意的——分离会导致中间状态重复检测崩溃)。
- [ ] 前端无需改动(`get_claude_cli_path`/`set_claude_cli_path` 上行调用与 `config.claude_cli_path` 下行监听协议不变)。

---

## 自审(writing-plans skill 要求)

1. **Spec 覆盖:** 目标(`get_claude_cli_path`/`set_claude_cli_path` → 2 typed handler + 删旧类与旧测试)→ Task 1(建 2 handler + 2 测试)+ Task 2(守门扩 2 项 + 删 SUPPORTED_TYPES 2 条/case/字段/构造 + wiring 注册 2 行)+ Task 3(grep 复核 + git rm 旧类 + 旧测试 + 全量验证)。覆盖完整。AGENTS.md §2(开闭)由"新增 action 走 typed handler 注册、不改既有 switch 数组"落地。

2. **占位符扫描:** 无 TBD/TODO;所有步骤含完整代码块、精确文件:行号、可运行命令与预期输出。Task 1 两 handler + 两测试、Task 2 守门扩展与四处删除 + wiring、Task 3 grep 复核 + 双 git rm 均为真实可执行内容。

3. **类型一致性:** `UpstreamAction.GET_CLAUDE_CLI_PATH`/`SET_CLAUDE_CLI_PATH`(枚举 :72-73)在 handler `action()`、两契约单测、wiring 守门三处一致;`FrontendActionHandler<String>` + `payloadType() == String.class` 在两 handler 与两单测一致;`get_claude_cli_path`/`set_claude_cli_path` 字符串在守门两清单、SUPPORTED_TYPES 删除、case 删除三处一致;反射字段名 `SUPPORTED_TYPES` 与 SettingsHandler:34 声明一致;`validateCliPath(File, String)` 签名在新 Set handler 与新测试一致(返回 null=合法 / 非 null=原因)。

4. **重复检测硬约束已处理:** Task 2 将"wiring 注册 2 typed handler"与"删 SUPPORTED_TYPES 2 条目"绑在同一 commit(Step 4-9 同 commit),Step 8 跑守门验证无重复。中间不可分离提交(验收已注明)。

5. **行为等价性已逐字保留:** get/set 逻辑(异步出口 `CompletableFuture.runAsync` + `AppExecutorUtil.getAppExecutorService()` + `invokeLater`、`shutdownDaemon` try/catch 兜底、失败回显用户输入 `pathToEcho`、`toast` 文案含英文、`escapeJs` 包装)全部从旧 handler :39-150 逐字搬入,仅类名/Logger 标签替换。`Gson` 从旧实例字段 `new Gson()` 改为 `private static final GSON`(Gson 无状态,行为等价,且与 GetCodexSubscriptionQuotaActionHandler 风格一致)。

6. **纪律边界已声明:** 不夹带魔法字符串→枚举化重构(说明段 + handler 注释)、不抽 service(逻辑内聚且 bridge 是 session 级对象 app service 难持有)、`CLAUDE_CLI_PATH_PROPERTY_KEY` 两 handler 各声明一份(接受重复,集中化留后续)。`wiredDispatcherConstructsAndRoutesLegacyWithoutDuplicates` 注释仍提"5 个 typed handler"不改(继承第一切片决策:该测试只列 5 个 + dummy legacy 验证框架而非穷举所有 handler,反射守门已覆盖重复检测)。
