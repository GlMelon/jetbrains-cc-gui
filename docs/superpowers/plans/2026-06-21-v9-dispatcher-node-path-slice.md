# V9 OCP 迁移 — NodePath 切片(第三切片)实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development 执行本计划(每任务一个执行者 + 两阶段审查)。Steps 用 `- [ ]` 勾选。

**Goal:** 把 `get_node_path` / `set_node_path` 从 `SettingsHandler` 字符串派发 + `NodePathHandler` 委托,迁移到 2 个 typed `FrontendActionHandler<String>`(开闭原则),功能与旧实现逐字等价,删除旧 `NodePathHandler`。

**Architecture:** 同前两切片(ModelRegistry / Appearance / CodexQuota / ClaudeCliPath)。`FrontendActionHandler<T>` 3 方法接口(`action()`/`payloadType()`/`handle()`),`FrontendActionDispatcher` LinkedHashMap O(1) + `putIfAbsent` 重复检测。GET/SET 因 `action()` 返回单个枚举而必须分两个 handler。`payloadType=String`(raw content,不强制 DTO)。**无服务提取**(检测/验证逻辑埋在 `ClaudeSDKBridge`/`CodexSDKBridge`,typed handler 经 `ctx` 取 bridge 直接调用,逐字搬移;服务重构是独立议题,YAGNI)。`dispatchEvent` 保持字符串字面量(枚举转换是未来债务)。

**Tech Stack:** Java 17 + JUnit 4(`org.junit.Test` / `org.junit.Assert.*`)+ IntelliJ Platform(`PropertiesComponent` / `ApplicationManager.invokeLater` / `AppExecutorUtil.getAppExecutorService()`)+ Gson。

---

## 前置事实表(已逐项核对,勿改动)

| 项 | 值 | 出处 |
|---|---|---|
| `UpstreamAction.GET_NODE_PATH` | 枚举值 `"get_node_path"` | `UpstreamAction.java:70` |
| `UpstreamAction.SET_NODE_PATH` | 枚举值 `"set_node_path"` | `UpstreamAction.java:71` |
| `DownstreamEvent.NODE_PATH` | `"node.path"` | `DownstreamEvent.java:160` |
| `DownstreamEvent.NODE_CHECK_ENV` | `"node.check_env"` | `DownstreamEvent.java:161` |
| `DownstreamEvent.TOAST_SWITCH_SUCCESS` | `"toast.switch_success"` | `DownstreamEvent.java:87` |
| `DownstreamEvent.TOAST_ERROR` | `"toast.error"` | `DownstreamEvent.java:84` |
| 属性键 `NODE_PATH_PROPERTY_KEY` | `"claude.code.node.path"` | `NodePathHandler.java:24` |
| `NodePathHandler` 旧用 GSON | `GsonHolder.GSON`(非 `new Gson()`) | `NodePathHandler.java:27` |
| `NodeDetector.MIN_NODE_MAJOR_VERSION` | 静态常量(int),被 `addProperty("minVersion", ...)` 接受 | `NodePathHandler.java:87,182` |
| `NodePathHandler` 引用方 | **仅** `SettingsHandler`(:30 字段 + :103 构造),其余皆自身 LOG | grep 确认 |
| `NodePath*Test.java` | **不存在**(无旧测试可迁移) | Glob 确认 |
| SettingsHandler SUPPORTED_TYPES | :40 `"get_node_path"`, :41 `"set_node_path"` | `SettingsHandler.java:40-41` |
| SettingsHandler switch case | :157 `// Node path` 注释 + :158-163 两 case 6 行 | `SettingsHandler.java:157-163` |
| SettingsHandler 字段/构造 | :30 字段, :103 构造 | `SettingsHandler.java:30,103` |
| ChatWindowDelegate wiring | :328 `SetClaudeCliPathActionHandler` 之后、 :329 `addAll(LegacyMessageHandlerAdapter...)` 之前 | `ChatWindowDelegate.java:327-329` |
| 前端上行(get/set_node_path) | 活跃:`useSettingsWindowCallbacks.ts:518`(get)、`useSettingsBasicActions.ts:284`(set) | grep 确认 |
| 前端下行消费 | `node.path`→`useSettingsWindowCallbacks.ts:174`;`node.check_env`→`DependencySection/index.tsx:354` | grep 确认 |

### 关键设计决策(与 ClaudeCliPath 切片对齐 + NodePath 特有)

1. **2 个 typed handler**:GET/SET 因 `action()` 返回单个 `UpstreamAction` 不可合并 → `GetNodePathActionHandler` + `SetNodePathActionHandler`。
2. **`payloadType=String`**:raw content 字符串,与 ClaudeCliPath 切片一致。
3. **`GSON = GsonHolder.GSON`**(**非** `new Gson()`):NodePathHandler 旧用 `GsonHolder.GSON`,逐字等价 + SSOT(GsonHolder 是项目 GSON 单例)。这是与 ClaudeCliPath 切片(`new Gson()`)的有意差异——NodePath 沿用旧实现的正确选择。
4. **`NODE_PATH_PROPERTY_KEY`**:每个 handler 各声明一份 `private static final String = "claude.code.node.path"`(与 SetClaudeCliPath 的 `CLAUDE_CLI_PATH_PROPERTY_KEY` 模式一致,接受重复;提取共享常量是未来债务)。
5. **无服务提取**:typed handler 经 `ctx.getClaudeSDKBridge()`/`ctx.getCodexSDKBridge()` 直接调用,逐字搬移旧逻辑。
6. **`dispatchEvent` 保持字符串字面量**:推迟 DownstreamEvent 枚举转换(未来债务)。
7. **3 事件级联顺序必须保持**(仅 set 成功时):`node.path` → `toast.switch_success` → `node.check_env`。
8. **原子约束**:注册 typed handler + 删除 SUPPORTED_TYPES 的 `get_node_path`/`set_node_path` + 删除 switch case 必须同一提交(否则窗口启动时 `putIfAbsent` 与 LegacyActionHandler 冲突抛 IllegalArgumentException)。
9. **契约测试(无旧测试可迁移)**:每个 handler 一个契约测试断言 `action()` 绑定 + `payloadType()`。SetNodePath 无 `validateCliPath` 类纯静态方法可测(验证埋在 `ClaudeSDKBridge.verifyAndCacheNodePath` 实跑 node 进程),故无用例测试。

### handleSetNodePath 双桥接联动(逐字搬移,务必保持)

- **清空分支**(pathArg 为空/null):`props.unsetValue` → `ClaudeSDKBridge.setNodeExecutable(null)` → `CodexSDKBridge.setNodeExecutable(null)` → `detectNodeWithDetails()` 重检测 → 若命中:`setValue` + `verifyAndCacheNodePath` + `CodexSDKBridge.setNodeExecutable(finalPath)` + `verifySuccess=true`,否则 `failureMsg="已清空自定义路径，但无法自动检测到 Node.js，请手动配置路径"`。
- **设置分支**(pathArg 非空):`verifyAndCacheNodePath(pathArg)` → 若 `isFound()`:`setValue` + `CodexSDKBridge.setNodeExecutable(pathArg)` + `verifySuccess=true`,否则不存盘 `failureMsg=result.getErrorMessage()`(或默认)。
- **GET 的 saved 无效分支**:同样清空 + `setNodeExecutable(null)` 双桥 + 重检测。

---

## 文件结构

| 文件 | 动作 | 责任 |
|---|---|---|
| `src/main/java/com/github/claudecodegui/handler/settings/GetNodePathActionHandler.java` | 新建 | typed handler 绑定 `GET_NODE_PATH`,逐字搬移 `handleGetNodePath` |
| `src/main/java/com/github/claudecodegui/handler/settings/SetNodePathActionHandler.java` | 新建 | typed handler 绑定 `SET_NODE_PATH`,逐字搬移 `handleSetNodePath` |
| `src/test/java/com/github/claudecodegui/handler/settings/GetNodePathActionHandlerTest.java` | 新建 | 契约测试 |
| `src/test/java/com/github/claudecodegui/handler/settings/SetNodePathActionHandlerTest.java` | 新建 | 契约测试 |
| `src/main/java/com/github/claudecodegui/handler/SettingsHandler.java` | 修改 | 删字段/构造/SUPPORTED_TYPES 2/case 6(原子,与 wiring 同提交) |
| `src/main/java/com/github/claudecodegui/ui/ChatWindowDelegate.java` | 修改 | import 2 + wiring 2 行(原子,同提交) |
| `src/test/java/com/github/claudecodegui/handler/settings/SettingsHandlerTypedWiringTest.java` | 修改 | 两个列表各追加 2 项(原子,同提交) |
| `src/main/java/com/github/claudecodegui/handler/NodePathHandler.java` | 删除 | 旧委托类(209 行) |

---

## Task 1: 创建 Get/SetNodePathActionHandler + 契约测试

**Files:**
- Create: `src/main/java/com/github/claudecodegui/handler/settings/GetNodePathActionHandler.java`
- Create: `src/main/java/com/github/claudecodegui/handler/settings/SetNodePathActionHandler.java`
- Create: `src/test/java/com/github/claudecodegui/handler/settings/GetNodePathActionHandlerTest.java`
- Create: `src/test/java/com/github/claudecodegui/handler/settings/SetNodePathActionHandlerTest.java`

- [ ] **Step 1.1: 写 GetNodePathActionHandlerTest(失败测试)**

创建 `src/test/java/com/github/claudecodegui/handler/settings/GetNodePathActionHandlerTest.java`:

```java
package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GetNodePathActionHandlerTest {

    /**
     * 契约:GetNodePathActionHandler 必须绑定 GET_NODE_PATH 枚举并以 String 为原始载荷类型。
     * FrontendActionDispatcher 据此 O(1) 路由,绕开 SettingsHandler 字符串 switch(AGENTS.md §2)。
     */
    @Test
    public void bindsGetNodePathUpstreamActionWithRawStringPayload() {
        GetNodePathActionHandler handler = new GetNodePathActionHandler();
        assertEquals(UpstreamAction.GET_NODE_PATH, handler.action());
        assertEquals(String.class, handler.payloadType());
    }
}
```

- [ ] **Step 1.2: 写 SetNodePathActionHandlerTest(失败测试)**

创建 `src/test/java/com/github/claudecodegui/handler/settings/SetNodePathActionHandlerTest.java`:

```java
package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SetNodePathActionHandlerTest {

    /**
     * 契约:SetNodePathActionHandler 必须绑定 SET_NODE_PATH 枚举并以 String 为原始载荷类型。
     */
    @Test
    public void bindsSetNodePathUpstreamActionWithRawStringPayload() {
        SetNodePathActionHandler handler = new SetNodePathActionHandler();
        assertEquals(UpstreamAction.SET_NODE_PATH, handler.action());
        assertEquals(String.class, handler.payloadType());
    }
}
```

- [ ] **Step 1.3: 运行测试确认失败(类不存在)**

Run: `./gradlew test --tests "com.github.claudecodegui.handler.settings.GetNodePathActionHandlerTest" --tests "com.github.claudecodegui.handler.settings.SetNodePathActionHandlerTest"`
Expected: 编译失败(`GetNodePathActionHandler` / `SetNodePathActionHandler` 无法解析)。

- [ ] **Step 1.4: 写 GetNodePathActionHandler**

创建 `src/main/java/com/github/claudecodegui/handler/settings/GetNodePathActionHandler.java`(逐字搬移 `NodePathHandler.handleGetNodePath`,仅替换访问路径):

```java
package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.model.NodeDetectionResult;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.concurrent.CompletableFuture;

/**
 * OCP typed handler:取代旧 SettingsHandler 对 get_node_path 的字符串派发
 * + NodePathHandler.handleGetNodePath 委托(AGENTS.md §2 开闭原则)。
 *
 * <p>后台线程检测/校验 Node.js 路径(避免阻塞 CEF IO 线程)→ 经 {@code node.path}
 * 事件回传 path/version/minVersion,与旧实现逐字等价。失败经 {@code toast.error} 回传。
 */
public final class GetNodePathActionHandler implements FrontendActionHandler<String> {

    private static final Logger LOG = Logger.getInstance(GetNodePathActionHandler.class);
    private static final Gson GSON = GsonHolder.GSON;
    private static final String NODE_PATH_PROPERTY_KEY = "claude.code.node.path";

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_NODE_PATH;
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
                PropertiesComponent props = PropertiesComponent.getInstance();
                String saved = props.getValue(NODE_PATH_PROPERTY_KEY);
                String pathToSend = "";
                String versionToSend = null;

                if (saved != null && !saved.trim().isEmpty()) {
                    String trimmedPath = saved.trim();
                    NodeDetectionResult result = ctx.getClaudeSDKBridge().verifyAndCacheNodePath(trimmedPath);
                    if (result != null && result.isFound()) {
                        pathToSend = trimmedPath;
                        versionToSend = result.getNodeVersion();
                    } else {
                        // Saved path is invalid, clear it and trigger re-detection
                        LOG.warn("[GetNodePathActionHandler] Saved Node.js path is invalid: " + trimmedPath
                            + ", clearing and triggering re-detection");
                        props.unsetValue(NODE_PATH_PROPERTY_KEY);
                        ctx.getClaudeSDKBridge().setNodeExecutable(null);
                        ctx.getCodexSDKBridge().setNodeExecutable(null);

                        NodeDetectionResult detected = ctx.getClaudeSDKBridge().detectNodeWithDetails();
                        if (detected != null && detected.isFound() && detected.getNodePath() != null) {
                            pathToSend = detected.getNodePath();
                            versionToSend = detected.getNodeVersion();
                            props.setValue(NODE_PATH_PROPERTY_KEY, pathToSend);
                            ctx.getClaudeSDKBridge().verifyAndCacheNodePath(pathToSend);
                            ctx.getCodexSDKBridge().setNodeExecutable(pathToSend);
                        }
                    }
                } else {
                    NodeDetectionResult detected = ctx.getClaudeSDKBridge().detectNodeWithDetails();
                    if (detected != null && detected.isFound() && detected.getNodePath() != null) {
                        pathToSend = detected.getNodePath();
                        versionToSend = detected.getNodeVersion();
                        props.setValue(NODE_PATH_PROPERTY_KEY, pathToSend);
                        // Use verifyAndCacheNodePath instead of setNodeExecutable to ensure version info is cached
                        ctx.getClaudeSDKBridge().verifyAndCacheNodePath(pathToSend);
                        ctx.getCodexSDKBridge().setNodeExecutable(pathToSend);
                    }
                }

                final String finalPath = pathToSend;
                final String finalVersion = versionToSend;

                ApplicationManager.getApplication().invokeLater(() -> {
                    JsonObject response = new JsonObject();
                    response.addProperty("path", finalPath);
                    response.addProperty("version", finalVersion);
                    response.addProperty("minVersion", NodeDetector.MIN_NODE_MAJOR_VERSION);
                    ctx.dispatchEvent("node.path", ctx.escapeJs(GSON.toJson(response)));
                });
            } catch (Exception e) {
                LOG.error("[GetNodePathActionHandler] Failed to get Node.js path: " + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() ->
                    ctx.dispatchEvent("toast.error", ctx.escapeJs("获取 Node.js 路径失败: " + e.getMessage()))
                );
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[GetNodePathActionHandler] Unexpected error in handle: " + ex.getMessage(), ex);
            return null;
        });
    }
}
```

> 注:`addProperty("version", finalVersion)` 当 `finalVersion==null` 时 Gson 内部写 `JsonNull`(旧实现 :86 同样行为,逐字保持)。

- [ ] **Step 1.5: 写 SetNodePathActionHandler**

创建 `src/main/java/com/github/claudecodegui/handler/settings/SetNodePathActionHandler.java`(逐字搬移 `NodePathHandler.handleSetNodePath`,仅替换访问路径 + LOG 前缀):

```java
package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.model.NodeDetectionResult;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.concurrent.CompletableFuture;

/**
 * OCP typed handler:取代旧 SettingsHandler 对 set_node_path 的字符串派发
 * + NodePathHandler.handleSetNodePath 委托(AGENTS.md §2 开闭原则)。
 *
 * <p>CEF 线程同步解析 JSON(纯解析无 I/O)→ 后台线程校验/写盘/检测(避免阻塞 CEF IO 线程)
 * → 经 {@code node.path} 回传 + 成功时 {@code toast.switch_success} + {@code node.check_env}
 * 触发环境重检,失败时 {@code toast.error},与旧实现逐字等价。
 */
public final class SetNodePathActionHandler implements FrontendActionHandler<String> {

    private static final Logger LOG = Logger.getInstance(SetNodePathActionHandler.class);
    private static final Gson GSON = GsonHolder.GSON;
    private static final String NODE_PATH_PROPERTY_KEY = "claude.code.node.path";

    @Override
    public UpstreamAction action() {
        return UpstreamAction.SET_NODE_PATH;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        LOG.debug("[SetNodePathActionHandler] ========== handle START ==========");
        LOG.debug("[SetNodePathActionHandler] Received content: " + payload);

        // Parse path on the CEF IO thread — pure JSON parsing, no I/O, safe to do synchronously
        String parsedPath = null;
        try {
            JsonObject json = GSON.fromJson(payload, JsonObject.class);
            if (json != null && json.has("path") && !json.get("path").isJsonNull()) {
                parsedPath = json.get("path").getAsString();
            }
        } catch (Exception e) {
            LOG.error("[SetNodePathActionHandler] Failed to parse set_node_path content: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                ctx.dispatchEvent("toast.error", ctx.escapeJs("保存 Node.js 路径失败: " + e.getMessage()))
            );
            return;
        }
        final String pathArg = (parsedPath != null) ? parsedPath.trim() : null;

        // All I/O and process-spawning runs in a background thread
        CompletableFuture.runAsync(() -> {
            try {
                PropertiesComponent props = PropertiesComponent.getInstance();
                String finalPath = "";
                String versionToSend = null;
                boolean verifySuccess = false;
                String failureMsg = null;

                if (pathArg == null || pathArg.isEmpty()) {
                    props.unsetValue(NODE_PATH_PROPERTY_KEY);
                    ctx.getClaudeSDKBridge().setNodeExecutable(null);
                    ctx.getCodexSDKBridge().setNodeExecutable(null);
                    LOG.info("[SetNodePathActionHandler] Cleared manual Node.js path from settings");

                    NodeDetectionResult detected = ctx.getClaudeSDKBridge().detectNodeWithDetails();
                    if (detected != null && detected.isFound() && detected.getNodePath() != null) {
                        finalPath = detected.getNodePath();
                        versionToSend = detected.getNodeVersion();
                        props.setValue(NODE_PATH_PROPERTY_KEY, finalPath);
                        // Use verifyAndCacheNodePath to ensure version info is cached
                        ctx.getClaudeSDKBridge().verifyAndCacheNodePath(finalPath);
                        ctx.getCodexSDKBridge().setNodeExecutable(finalPath);
                        verifySuccess = true;
                    } else {
                        failureMsg = "已清空自定义路径，但无法自动检测到 Node.js，请手动配置路径";
                    }
                } else {
                    // Verify before saving to avoid caching invalid path
                    NodeDetectionResult result = ctx.getClaudeSDKBridge().verifyAndCacheNodePath(pathArg);
                    if (result != null && result.isFound()) {
                        // Only save if verification succeeds
                        props.setValue(NODE_PATH_PROPERTY_KEY, pathArg);
                        ctx.getCodexSDKBridge().setNodeExecutable(pathArg);
                        finalPath = pathArg;
                        versionToSend = result.getNodeVersion();
                        verifySuccess = true;
                        LOG.info("[SetNodePathActionHandler] Saved manual Node.js path: " + pathArg);
                    } else {
                        // Verification failed, don't save invalid path
                        finalPath = "";
                        failureMsg = result != null ? result.getErrorMessage() : "无法验证指定的 Node.js 路径";
                        LOG.warn("[SetNodePathActionHandler] Node.js path verification failed: " + pathArg + " - " + failureMsg);
                    }
                }

                final boolean successFlag = verifySuccess;
                final String failureMsgFinal = failureMsg;
                final String finalPathToSend = finalPath;
                final String finalVersionToSend = versionToSend;

                ApplicationManager.getApplication().invokeLater(() -> {
                    JsonObject response = new JsonObject();
                    response.addProperty("path", finalPathToSend);
                    response.addProperty("version", finalVersionToSend);
                    response.addProperty("minVersion", NodeDetector.MIN_NODE_MAJOR_VERSION);
                    ctx.dispatchEvent("node.path", ctx.escapeJs(GSON.toJson(response)));

                    if (successFlag) {
                        // Trigger environment re-check, no IDE restart needed
                        ctx.dispatchEvent("toast.switch_success", ctx.escapeJs("Node.js 路径已保存并生效,无需重启IDE"));

                        // Notify DependencySection to re-check Node.js environment
                        ctx.dispatchEvent("node.check_env", "");
                    } else {
                        String msg = failureMsgFinal != null ? failureMsgFinal : "无法验证指定的 Node.js 路径";
                        ctx.dispatchEvent("toast.error", ctx.escapeJs("保存的 Node.js 路径无效: " + msg));
                    }
                });
            } catch (Exception e) {
                LOG.error("[SetNodePathActionHandler] Failed to set Node.js path: " + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() ->
                    ctx.dispatchEvent("toast.error", ctx.escapeJs("保存 Node.js 路径失败: " + e.getMessage()))
                );
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[SetNodePathActionHandler] Unexpected error in handle: " + ex.getMessage(), ex);
            return null;
        });

        LOG.debug("[SetNodePathActionHandler] ========== handle END (async dispatched) ==========");
    }
}
```

- [ ] **Step 1.6: 运行测试确认通过**

Run: `./gradlew test --tests "com.github.claudecodegui.handler.settings.GetNodePathActionHandlerTest" --tests "com.github.claudecodegui.handler.settings.SetNodePathActionHandlerTest"`
Expected: 2 passed, 0 failed。

- [ ] **Step 1.7: 自检 — 逐行对照旧 NodePathHandler**

对照 `src/main/java/com/github/claudecodegui/handler/NodePathHandler.java`:
- [ ] GET 3 分支(saved 有效 / saved 无效重检测 / 无 saved 检测)逻辑、调用顺序、`minVersion` 字段一致
- [ ] GET 失败 toast 文案 `"获取 Node.js 路径失败: "` 一致
- [ ] SET 清空分支双桥 `setNodeExecutable(null)` 顺序 + 重检测 + failureMsg 一致
- [ ] SET 设置分支 `verifyAndCacheNodePath` → `setValue` → `CodexSDKBridge.setNodeExecutable` 顺序一致
- [ ] SET 3 事件级联顺序 `node.path` → `toast.switch_success` → `node.check_env` 一致
- [ ] SET 失败 toast 文案一致
- [ ] 无遗漏的 `this.context` → `ctx`、`gson` → `GSON` 替换

- [ ] **Step 1.8: 提交**

```bash
git add src/main/java/com/github/claudecodegui/handler/settings/GetNodePathActionHandler.java \
        src/main/java/com/github/claudecodegui/handler/settings/SetNodePathActionHandler.java \
        src/test/java/com/github/claudecodegui/handler/settings/GetNodePathActionHandlerTest.java \
        src/test/java/com/github/claudecodegui/handler/settings/SetNodePathActionHandlerTest.java
git commit -m "refactor: add typed Node path action handlers (V9 OCP slice 1/3)"
```

---

## Task 2: 防护扩展 + 原子迁移(注册 typed + 删 SUPPORTED_TYPES/case + wiring,同一提交)

**Files:**
- Modify: `src/test/java/com/github/claudecodegui/handler/settings/SettingsHandlerTypedWiringTest.java:34-39` 与 `:54-69`
- Modify: `src/main/java/com/github/claudecodegui/handler/SettingsHandler.java:30,40-41,103,157-163`
- Modify: `src/main/java/com/github/claudecodegui/ui/ChatWindowDelegate.java`(import 区 + :328 后)

- [ ] **Step 2.1: 扩展 SettingsHandlerTypedWiringTest 防护(写期望)**

在 `migratedActionsRemainResolvable()`(:34-39 的 String[] 字面量末尾)与 `migratedActionsRemovedFromLegacySupportedTypes()`(:54-69 同结构列表末尾)**两处**列表中,在 `"get_claude_cli_path", "set_claude_cli_path"` 之后追加:

```java
                "get_claude_cli_path", "set_claude_cli_path",
                "get_node_path", "set_node_path"
```

(两处都加,保持两个列表同步。修改后两列表各含 10 项。)

- [ ] **Step 2.2: 运行防护测试确认失败(SUPPORTED_TYPES 尚未删)**

Run: `./gradlew test --tests "com.github.claudecodegui.handler.settings.SettingsHandlerTypedWiringTest"`
Expected: `migratedActionsRemovedFromLegacyLegacySupportedTypes` FAIL(反射读到 SUPPORTED_TYPES 仍含 `get_node_path`/`set_node_path`)。这是 TDD 红灯,证明守门生效。

- [ ] **Step 2.3: SettingsHandler 删字段**

`src/main/java/com/github/claudecodegui/handler/SettingsHandler.java`:
删除字段声明(`:30`):

```java
    private final NodePathHandler nodePathHandler;
```

(注意删除后,字段块从 :26-31 的 `inputHistoryHandler` / `usagePushService` / `permissionModeHandler` / `modelProviderHandler` / `nodePathHandler` / `projectConfigHandler` 变为去掉 nodePathHandler。)

- [ ] **Step 2.4: SettingsHandler 删构造赋值**

删除(`:103`):

```java
        this.nodePathHandler = new NodePathHandler(context);
```

- [ ] **Step 2.5: SettingsHandler 删 SUPPORTED_TYPES 两项**

删除 SUPPORTED_TYPES 数组中(`:40-41`):

```java
        "get_node_path",
        "set_node_path",
```

(数组是 `private static final String[]`,可被反射读取——这正是防护测试的拦截点。)

- [ ] **Step 2.6: SettingsHandler 删 switch case**

删除(`:157-163`,含注释):

```java
            // Node path
            case "get_node_path":
                nodePathHandler.handleGetNodePath();
                return true;
            case "set_node_path":
                nodePathHandler.handleSetNodePath(content);
                return true;
```

(删除后 `// Model and provider` 区块之后直接接 `// Project configuration` 区块。switch 结构保持 `default: return false;` 完整。)

- [ ] **Step 2.7: ChatWindowDelegate 加 import 2 行**

在 `import ...SetClaudeCliPathActionHandler;` 之后(保持 GET/SET 配对 + Claude<Node 字母序),追加:

```java
import com.github.claudecodegui.handler.settings.GetNodePathActionHandler;
import com.github.claudecodegui.handler.settings.SetNodePathActionHandler;
```

(若 import 区现有顺序为 GetCodexSubscriptionQuota / GetClaudeCliPath / SetAppearanceConfig / SetClaudeCliPath 等,则在对应 Get*/Set* 位置插入;Java import 顺序不影响编译,保持可读即可。)

- [ ] **Step 2.8: ChatWindowDelegate 加 wiring 2 行**

在 `typedHandlers.add(new SetClaudeCliPathActionHandler());`(`:328`)之后、`typedHandlers.addAll(LegacyMessageHandlerAdapter.from(new SettingsHandler(handlerContext)));`(`:329`)之前插入:

```java
        typedHandlers.add(new GetNodePathActionHandler());
        typedHandlers.add(new SetNodePathActionHandler());
```

- [ ] **Step 2.9: 运行防护测试确认通过**

Run: `./gradlew test --tests "com.github.claudecodegui.handler.settings.SettingsHandlerTypedWiringTest"`
Expected: 3 passed(`migratedActionsRemainResolvable` + `migratedActionsRemovedFromLegacySupportedTypes` + `wiredDispatcherConstructsAndRoutesLegacyWithoutDuplicates`)。SUPPORTED_TYPES 不再含 node_path,守门转绿。

- [ ] **Step 2.10: 全量编译确认无回归**

Run: `./gradlew test`
Expected: 与基线一致(ClaudeCliPath 切片后基线:844 tests, 34 pre-existing failures, 8 skipped——34 失败是 ClaudeSDKBridge/CodexSDKBridge/MessageHandler/Session/Parser/Git 模块的系统性 NPE,与本切片无关)。**新增的 2 个契约测试应通过**。若失败数变化(除新增测试),需排查。

- [ ] **Step 2.11: 自检 — 原子性**

确认以下在同一工作树状态(将一起提交):
- [ ] SettingsHandler 已无 `nodePathHandler` 字段、构造、SUPPORTED_TYPES 项、switch case
- [ ] ChatWindowDelegate 已 import + wiring 2 个 typed handler
- [ ] 防护测试 `migratedActionsRemovedFromLegacySupportedTypes` 通过(反射读 SUPPORTED_TYPES 不含 node_path)
- [ ] **关键**:wiring 注册与 SUPPORTED_TYPES 删除在同一提交(否则窗口启动时 typed handler 的 action 与 LegacyActionHandler 在 `putIfAbsent` 冲突,抛 IllegalArgumentException)

- [ ] **Step 2.12: 提交(原子)**

```bash
git add src/main/java/com/github/claudecodegui/handler/SettingsHandler.java \
        src/main/java/com/github/claudecodegui/ui/ChatWindowDelegate.java \
        src/test/java/com/github/claudecodegui/handler/settings/SettingsHandlerTypedWiringTest.java
git commit -m "refactor: migrate node path actions to typed handlers (V9 OCP slice 2/3)"
```

---

## Task 3: 删除旧 NodePathHandler

**Files:**
- Delete: `src/main/java/com/github/claudecodegui/handler/NodePathHandler.java`

- [ ] **Step 3.1: 确认无其他引用**

Run: `git grep -n "NodePathHandler" -- 'src/**/*.java'`
Expected: 仅 `NodePathHandler.java` 自身(LOG 标签 + 类定义)。**无 SettingsHandler 残留引用**(Task 2 已删字段/构造)。若有其他引用,停止并报告。

- [ ] **Step 3.2: 删除旧类**

```bash
git rm src/main/java/com/github/claudecodegui/handler/NodePathHandler.java
```

(无 `NodePathHandlerTest.java`——Glob 已确认不存在,无需删测试。)

- [ ] **Step 3.3: 全量编译确认**

Run: `./gradlew test`
Expected: 编译通过(无对已删类的悬空引用)。测试数同 Task 2 末(844+2 契约 = 与基线一致,34 pre-existing failures 不变)。

- [ ] **Step 3.4: 自检 — 删除完整性**

- [ ] `git grep "NodePathHandler"` 仅命中本计划文档与 git 历史(无源码残留)
- [ ] 前端协议零改动(`get_node_path`/`set_node_path` 上行、`node.path`/`node.check_env`/`toast.*` 下行字符串均未变)
- [ ] `feature/v0.4.6` 工作树干净(仅本切片 3 提交 + 未追踪计划文档)

- [ ] **Step 3.5: 提交**

```bash
git commit -m "refactor: remove legacy NodePathHandler after typed migration (V9 OCP slice 3/3)"
```

(`git rm` 已暂存,直接 commit。)

---

## Self-Review(写完后自查)

**1. Spec coverage:**
- 旧 `handleGetNodePath` 3 分支 → Task 1 Step 1.4 逐字覆盖 ✅
- 旧 `handleSetNodePath` 2 主分支 + 3 事件级联 → Task 1 Step 1.5 逐字覆盖 ✅
- 双桥接联动(Claude+Codex `setNodeExecutable`)→ 两 handler 均保留 ✅
- OCP 迁移(删 SUPPORTED_TYPES/case/字段/构造 + wiring)→ Task 2 ✅
- 防护测试扩展 → Task 2 Step 2.1/2.2 ✅
- 旧类删除 → Task 3 ✅
- 前端协议零改动 → Task 3 Step 3.4 验证 ✅

**2. Placeholder scan:** 无 TBD/TODO/"类似 Task N"。所有代码块完整。✅

**3. Type consistency:**
- `GetNodePathActionHandler.action()` → `GET_NODE_PATH`(:70 枚举存在)✅
- `SetNodePathActionHandler.action()` → `SET_NODE_PATH`(:71 枚举存在)✅
- `payloadType()` = `String.class`(两 handler 一致)✅
- `NODE_PATH_PROPERTY_KEY = "claude.code.node.path"`(与旧 `:24` 一致)✅
- `GSON = GsonHolder.GSON`(与旧 `:27` 一致)✅
- dispatchEvent 字符串字面量与旧实现 + DownstreamEvent 枚举值一致(`node.path`/`node.check_env`/`toast.switch_success`/`toast.error`)✅
- ChatWindowDelegate wiring import 类名与创建的类全限定名一致 ✅
- SettingsHandlerTypedWiringTest 两列表同步追加 ✅

---

## 执行交接

Plan complete and saved to `docs/superpowers/plans/2026-06-21-v9-dispatcher-node-path-slice.md`。

按持久指令"继续推进 V9(推荐)...可连续做多个切片",本切片采用 **Subagent-Driven**(与前两切片一致):每任务一个执行者 + 两阶段审查(规范后质量)。
