# 下行通信总线归一化架构文档

> ADR (Architecture Decision Record) — 记录下行通信从散落 `window.xxx` 回调收敛为单一总线的架构决策、契约与规范。

## 1. 背景

原架构中,后端通过 `executeJavaScript` 直接调用约 75 个 `window.xxx` 具名回调,散落在 25+ 个 Java handler 里。前端在 `window` 上零散注册,另有 18 个 `__pendingXxx` 缓冲槽 + ~17 个 `__xxx` 跨模块可变标志。新增一个下行事件要动 4 层(后端调用点 → 前端注册 → options 类型 → register 分发)。

上行早已收敛(`sendBridgeEvent` → `window.sendToJava`),下行是对称缺口。

## 2. 目标架构

### 2.1 下行单一契约

```
Java:    context.dispatchEvent("usage.update", payloadJson)
         → window.__bridge.dispatch("usage.update", payloadJson)

前端:    bridgeHub.subscribe("usage.update", handler)   // 业务模块自取所需
```

- **唯一公开入口** `window.__bridge.dispatch(type, payloadJson)`,在 `main.tsx` React 挂载前安装。
- **透明字符串管道**:总线不解析 payload,订阅者收到原始 `payloadJson`(逐字节等价旧 `window.xxx(json)`)。原因:现有回调 payload 约定不统一(多数 JSON,少数裸字符串如 `onModeChanged`,多参数如 `onModelConfirmed`)。
- **默认同步派发**:`ready = true`,dispatch 立即触发(等价旧 `window.xxx(json)` 的同步语义)。

### 2.2 三类承载

| 类别 | 代表 | hub API | 关键约束 |
|---|---|---|---|
| 事件 | usage/mode/provider | `subscribe(type, fn)` | Set 广播;可下沉 Context |
| RPC | onFilePathResolved | `request(type, payload, {timeoutMs})` | requestId correlation + 超时;删前端 pending Map |
| 流式 | onContentDelta×7 | `subscribePassthrough(type, fn)` | ref-first;rAF/startTransition 节流;不广播不拷贝;App 层不下沉 |
| bootstrap | font/theme/language | `subscribe(fn)` | DOM 直写不进 React state |

### 2.3 后端入口

```java
// HandlerContext / ClaudeChatWindow / BaseMessageHandler
context.dispatchEvent("usage.update", payloadJson);
// 内部:window.__bridge.dispatch("usage.update", payloadJson)
```

### 2.4 兼容层(双轨)

```typescript
registerLegacyAlias('onUsageUpdate', 'usage.update');
// window.onUsageUpdate(json) → bridgeHub.dispatch('usage.update', json)
```

迁移期新旧路径并存、行为一致。后端可逐步从 `callJavaScript("window.xxx")` 切到 `dispatchEvent("type")`。

## 3. 新增下行事件标准步骤

1. **登记事件目录** `webview/src/bridge/events/index.ts`:
   ```typescript
   { type: 'my_feature.update', kind: 'event' }
   ```
2. **后端发送**:
   ```java
   context.dispatchEvent("my_feature.update", payloadJson);
   ```
3. **前端订阅**:
   ```typescript
   bridgeHub.subscribe('my_feature.update', (payloadJson) => {
     const data = JSON.parse(payloadJson);
     // 处理
   });
   ```

迁移期额外步骤:注册兼容别名 `registerLegacyAlias('window.myFeature', 'my_feature.update')`。

## 4. 流式红线

`onContentDelta` / `onThinkingDelta` 是性能关键路径(每秒数十次增量):

- **必须保持 ref-first**: `streamingContentRef.current += delta`
- **必须保持 rAF + startTransition 节流**: `scheduleContentRaf()` 调度,`THROTTLE_INTERVAL` 节流
- **passthrough 模式不得引入**: Set 广播 / `Array.from` / 闭包拷贝 / 二次 JSON.parse
- **不下沉到 Context**: streaming refs 来自 `useStreamingMessages`(App 层组合产物)

## 5. `__` 黑板清单

### 已迁入 BridgeStateStore(Phase 4)

| 键 | 用途 | 写 | 读 |
|---|---|---|---|
| `sessionTransitioning` | 会话切换期间抑制 stale 回调 | useSessionManagement | streamingCallbacks / messageCallbacks |
| `activeStreamScopeKey` | 当前活跃流 scope | streamScopeState | streamingCallbacks |
| `streamEndProcessedTurnId` | onStreamEnd 幂等守卫 | streamingCallbacks | streamingCallbacks |
| `lastStreamActivityAt` | stall watchdog 心跳时间戳 | streamingCallbacks | streamingCallbacks |
| `stallWatchdogInterval` | watchdog 定时器句柄 | streamingCallbacks | streamingCallbacks |
| `activeStreamingResponseId` | 前端运行时 response 分组 id | streamingCallbacks | streamingCallbacks |
| `turnStartedAt` | 当前 turn 起始时间 | streamingCallbacks | streamingCallbacks |
| `lastStreamEndSource` | 诊断 onStreamEnd 来源 | streamingCallbacks | streamingCallbacks |

### 保留为 window 全局(HMR / 特殊语义)

| 键 | 原因 |
|---|---|
| `__pendingUpdateRaf/Json/Sequence` | 刻意存 window 防 HMR 重装叠加 |
| `__cancelPendingUpdateMessages` | onStreamEnd 调用以取消 pending rAF |
| `__minAcceptedUpdateSequence` | 序列号下限,拒绝乱序/旧 updateMessages |
| `__streamingDeltaRenderingFrame` | 标记 delta 渲染帧 |
| `__deniedToolIds` | 被拒权限的 tool_use id |
| `__CLAUDE_INVOCATION_MODE__` | 缓存当前会话 Claude 调用模式 |
| `__INITIAL_IDE_THEME__` | Java 预注入初值 |
| `__pendingSessionTransitionToast` | 会话切换期间暂存 toast |

## 6. 迁移历史

| Phase | 状态 | 迁移回调数 | 关键改动 |
|---|---|---|---|
| 0 | ✅ | 0 | hub/store/compat/events 地基 + 后端 dispatchEvent API |
| 1 | ✅ | 15 | usage/settings/mode/model/provider/session;消除双写竞争 |
| 2 | ✅ | — | pending drain 通过 compat 别名自然协作 |
| 3 | ✅ | 1 | onFilePathResolved → request/response RPC |
| 4 | ✅ | 7 | streaming passthrough 直通,保持 ref-first + rAF |
| 5 | ✅ | 14 | bootstrap/DOM + 对话框 + config 配置类 + ProjectConfigHandler 改造 |
| 6 | ✅ | — | 文档定稿 |

**总计:37 个回调已迁移到 bridgeHub** + ProjectConfigHandler 全量改造(~30 处 `pushJson`/`respondWithJson`/`showError`/`showSuccess` 调用点)。

## 7. 关键文件清单

### 前端新建
- `webview/src/bridge/hub.ts` — bridgeHub 核心
- `webview/src/bridge/store.ts` — BridgeStateStore
- `webview/src/bridge/compat.ts` — 双轨兼容层
- `webview/src/bridge/events/index.ts` — 事件目录
- `webview/src/bridge/types.ts` — 类型契约
- `webview/src/bridge/index.ts` — barrel
- `webview/src/bridge/__tests__/hub.test.ts` — 单测

### 前端改造
- `main.tsx` — installBridge + markReady 握手
- `global.d.ts` — `__bridge` 类型
- `hooks/useWindowCallbacks.ts` — 消解中
- `hooks/windowCallbacks/registerCallbacks/*` — 迁移中
- `utils/runtimeProviderCapabilities.ts` — 统一到 bridgeHub
- `utils/bridge.ts` — RPC 迁移
- `bootstrap/{fonts,language,pendingSlots}.ts` — 兼容别名
- `hooks/{useThemeInit,useContextActions}.ts` — 兼容别名
- `components/UsageStatistics/useUsageStatistics.ts` — 兼容别名

### 后端改造
- `ui/toolwindow/ClaudeChatWindow.java` — dispatchEvent API
- `handler/core/HandlerContext.java` — JsCallback.dispatchEvent
- `handler/core/BaseMessageHandler.java` — dispatchEvent 代理
- `handler/ProjectConfigHandler.java` — 全量改造
- `handler/file/FileHandler.java` — RPC requestId 转发
