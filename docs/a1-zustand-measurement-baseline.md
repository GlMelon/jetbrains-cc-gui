# A1 React 状态管理量化测量基线(S3-5)

> 核查基线:`feature/v0.4.8` 工作区,2026-07-27。本文是 `comprehensive-optimization-directions.md` §A1 / §0.3 S3-5 的测量证据底稿,对称 `docs/build-performance-baseline.md` 的基线体例。
>
> **纪律前置(§A1 / §0.3 S3-5)**:暂缓全量 Zustand 迁移,**先量化 Context 重渲染和状态依赖**;`bridgeState` 同步黑板必须保留;不得把后端业务权威迁入前端 store;测量结论可以是「不需要迁移」——那也是有效结论。

---

## 1. 测量范围与方法

| 维度 | 方法 | 本次是否完成 |
| --- | --- | --- |
| Context 架构盘点(文件/嵌套/consumer) | 静态源码分析 | ✅ |
| value 引用稳定性(memo/依赖/内联) | 静态源码分析 | ✅ |
| read/write 分离与订阅范围 | 静态源码分析 | ✅ |
| 流式重渲染链路与节流 | 静态源码分析 | ✅ |
| App/ChatScreen 每帧重渲染 wall-clock 成本 | **需 JCEF React Profiler runtime 采集** | ❌ 未做(独立立项) |
| streaming FPS / long task / first paint | **需 JCEF performance trace** | ❌ 未做(独立立项,§11.4 验收门槛) |

> 静态分析能证明「架构上不存在 Context 导致的系统性 O(n) 重渲染」,但给不出「顶层组件每帧重渲染的实际毫秒成本」。后者需 runtime Profiling,列为后续独立立项(见 §9)。静态结论已足够支撑「当前不应迁移 Zustand」的判断。

---

## 2. Context 架构盘点

### 2.1 Provider 嵌套(实测,纠正文档「6 层」)

`main.tsx:102-113` 实际挂载 **4 层 Provider**(非文档旧述的固定 6 层):

```
ErrorBoundary
└─ UIStateProvider          (main.tsx:104)
   └─ SessionProvider       (main.tsx:105)
      └─ MessagesProvider   (main.tsx:106)
         └─ DialogProvider  (main.tsx:107)
            └─ <App/>
```

`ModelProviderContext` **条件挂载**在 `App.tsx:468`(`currentView === 'chat'` 时),构成第 5 层;`SubagentContext.tsx` 内含 3 个独立 Context(SubagentHistory / SessionId / ToolResultRaw),由 `App.tsx:488-489` 经 `ChatScreen` props 下发,不走 main.tsx 顶层 Provider。

**结论**:Context 文件 6 个,Context 实例 7 个,顶层 Provider 嵌套 4 层(+1 条件层)。文档「不是固定 6 层 Provider」的修正确认无误。

### 2.2 consumer 分布(决定重渲染爆炸半径)

| Context | consumer(实测 `useXxx()` 调用点) | consumer 性质 |
| --- | --- | --- |
| MessagesContext | `App.tsx:52`、`ChatScreen.tsx:127` | **顶层组件**(2 个) |
| ModelProviderContext | `ChatScreen.tsx:122` | 顶层组件(1 个) |
| SessionContext | `App.tsx:60`、`ChatScreen.tsx:128` | 顶层组件(2 个) |
| UIStateContext | `App.tsx:70`、`AppDialogs.tsx:68`、`ChatScreen.tsx:136`、`CommunitySection:18` | 顶层/设置页(4 个) |
| DialogContext | `App.tsx:40`、`AppDialogs.tsx:65` | 顶层组件(2 个) |
| SubagentHistory/ToolResultRaw getter | `TaskExecutionBlock.tsx`(列表项叶子) | 见 §6 |

**关键结论**:5 个主 Context 的 consumer **全部是顶层容器组件(App / ChatScreen / AppDialogs)**,**没有任何叶子/列表项组件直接订阅**。这是当前架构健康的核心原因——Context 变化只触发顶层重渲染,子树由 props 下传 + memo 隔离(见 §6)。

> 注:`useContext` 全局仅 33 处 / 15 文件,其中相当部分是 Context 自身的 hook 定义与测试。Context 渗透面小。

---

## 3. value 引用稳定性

逐个 Provider 核查 `value` 是否 `useMemo` 包裹、依赖数组是否完整、是否存在内联对象/函数导致每次渲染新引用:

| Context | value memo | 依赖数组 | 评价 |
| --- | --- | --- | --- |
| MessagesContext(`MessagesContext.tsx:49-71`) | ✅ `useMemo` | 9 个 state 全列,setter 正确省略(稳定) | ✅ 正确 |
| SessionContext(`SessionContext.tsx:36-48`) | ✅ `useMemo` | 3 state 全列,2 ref 稳定 | ✅ 正确 |
| UIStateContext(`UIStateContext.tsx:83-100`) | ✅ `useMemo` | state + `useCallback` helper 全列 | ✅ 正确 |
| DialogContext(`DialogContext.tsx:19`) | 委托 `useDialogManagement`(内部 memo,`useDialogManagement.ts:235` 注释自述) | — | ✅ 正确 |
| ModelProviderContext(`ModelProviderContext.tsx:61-87`) | ✅ `useMemo` | **手写 24 字段全列** | ⚠️ 功能正确,但手写易漏(新增字段风险),非性能问题 |
| SubagentContext(`SubagentContext.tsx:51-66`) | getter 用 `useCallback([])`(空依赖,引用永不变)+ `useMemo([currentSessionId])` | — | ✅ **最佳实践**(见 §6) |

**结论**:**全部 Context value 已正确 `useMemo`,不存在内联对象/函数导致的引用泄漏**。这是 Context 重渲染优化的第一道防线,当前已达标。

---

## 4. read/write 分离与订阅范围

### 4.1 read/write 混合现状

MessagesContext / SessionContext / UIStateContext / ModelProviderContext 均 **read+write 混合**于同一 value(同时暴露 state 与 setter),这是 React Context 的固有形态,本身不是缺陷。

### 4.2 订阅范围(全量订阅的爆炸半径)

`useContext` 天然全量订阅——value 引用变则所有 consumer 重渲染。**但爆炸半径取决于 consumer 是谁**:

- 当前所有 read consumer 是 **App / ChatScreen / AppDialogs 顶层组件**(§2.2)。
- 流式期间 `messages` 每帧变化 → MessagesContext value 每帧变 → App + ChatScreen 每帧重渲染。
- 但 App/ChatScreen 是顶层容器,其重渲染成本是「函数执行 + hooks 调用」,DOM 更新被子树 memo 隔离(§6)。

**read/write 混合的真正风险**(预防性,当前未触发):若**未来新增叶子组件**调 `useMessages()` 仅为读 `status`,则 `messages` 每帧变化会迫使该叶子也每帧重渲染。**当前不存在这样的叶子 consumer**,故无实际损失,仅作扩展时警惕项(§9 局部治理机会)。

---

## 5. `bridgeState` 同步黑板(必须保留)

`bridge/store.ts` 的 `BridgeStateStore` 是**非 React 的同步黑板**,承接原 17 个裸 `window.__xxx` 流式协作控制标志(`sessionTransitioning` / `activeStreamScopeKey` / `streamEndProcessedTurnId` / `lastStreamActivityAt` / …)。

关键约束(`bridge/store.ts:11-18` 注释自述):

- get/set **同步**,不经过 React 调度;
- **刻意不提供订阅能力**(不触发 re-render)——否则引入异步时序,破坏现有竞态防护;
- 这些标志在 React setState updater **之外**被同步赋值,规避 React 18 updater 异步时序(`streamingCallbacks.ts:835-843, 962-964` 注释详述)。

**结论**:`bridgeState` 是流式正确性的根基。**Zustand 也是异步调度,无法替代 `bridgeState` 的同步黑板语义**——文档「必须保留」判断正确,迁移 Zustand 时 `bridgeState` 必须原样留在 `bridge/store.ts`,不进 store。

---

## 6. 流式消息重渲染链路(四层隔离 → O(1))

流式 token 到 React DOM 的完整路径,已查清为**四层隔离**:

### 第 1 层:ref 缓冲(绕开高频 state)

`useStreamingMessages.ts` 返回的流式状态**全是 ref**(`streamingContentRef` / `streamingThinkingRef` / `isStreamingRef` / `useBackendStreamingRenderRef` / `streamingMessageIndexRef` …),高频 token delta 先写入 ref,**不触发 React 渲染**。`App.tsx:121-128` 解构即证。

### 第 2 层:rAF + THROTTLE_INTERVAL 节流

`streamingCallbacks.ts:508-554` 的 `createStreamingRafScheduler`:`requestAnimationFrame` 调度(frame-aligned),每帧检查 `elapsed < THROTTLE_INTERVAL` 则跳过。`THROTTLE_INTERVAL = 33`(`useStreamingMessages.ts:14`,~30fps,对齐后端 StreamDeltaThrottler 33ms,无额外累积延迟)。

→ setMessages 最高频率 = **max(rAF 60fps, 33ms) ≈ 30fps**,而非每 token。

### 第 3 层:startTransition 降级优先级

`streamingCallbacks.ts:532`:`startTransition(() => setMessages(...))` —— React 18 并发特性,流式更新标记为**低优先级 transition**,可中断、不阻塞用户输入。

### 第 4 层:子树 memo 隔离(O(1) 重渲染)

`setMessages` updater 用 `[...prev]` 浅拷贝数组(`streamingCallbacks.ts:534`),**未变项引用不变**。子组件全部 `memo`:

| 组件 | memo | 证据 |
| --- | --- | --- |
| `MessageList` | ✅ `memo(forwardRef(...))`,默认浅比较 | `MessageList.tsx:138` |
| `MessageItem` | ✅ `memo(...)` | `MessageItem/MessageItem.tsx:266` |
| `MessageAvatar` | ✅ | `MessageItem/MessageAvatar.tsx:44` |
| `MessageUsageStats` | ✅ | `MessageItem/MessageUsageStats.tsx:22` |
| `CopyButton` | ✅ | `MessageItem/MessageItem.tsx:127` |
| `MessageAnchorRail` | ✅ | `MessageAnchorRail.tsx:97` |

→ 流式时仅「正在流式的那条」`MessageItem` 的 content prop 变化而重渲染,其余项 memo 浅比较跳过。**O(1) 重渲染,非 O(n)**。

### 第 4 层生效前提:callback 引用稳定(memo 不被打破的关键保障)

`memo` 浅比较只在「未变项的 props 引用稳定」时才能跳过;若 `MessageList` 给每个 `MessageItem` 传内联箭头函数或依赖 `messages` 的 callback,则流式每帧 callback 新引用 → 所有 `MessageItem` memo 失效 → 退化为 O(n)。实测确认**该陷阱已被刻意规避**:

- `MessageList` 传 `MessageItem` 的 props **全是外部引用,无内联箭头函数**(`MessageList.tsx:454-475, 511-532, 538-555`);
- 三个高频 callback **依赖不含 `messages`,数据访问 ref 化**:
  - `getMessageText = useCallback(..., [localizeMessage, t])`(`useMessageProcessing.ts:78-81`)
  - `getContentBlocks = useCallback(..., [normalizeBlocks, localizeMessage])`,附件经 `sentAttachmentsRef.current`(`useMessageProcessing.ts:101-119`)
  - `findToolResult = useCallback(...)`,内部 `const currentMessages = messagesRef.current`(`useChatComputations.ts:100-102`)—— **刻意用 ref 读 messages 而非闭包**,正是为让引用不被每帧 messages 变化打破;
- `onMessageNodeRef` = `useCallback(..., [])`(`App.tsx:88-91`)空依赖稳定。

这与 §3 的 `SubagentContext` getter 是**同一「ref + 依赖不含高频 state 的 useCallback」最佳实践**,全栈一致。因此流式每帧 App render 时,传给 `MessageItem` 的 callback 引用不变 + 未变 `message` 项引用不变(`[...prev]` 浅拷贝)→ memo 浅比较能正确跳过未变项,O(1) 结论成立。

### 链路总结

```
token → streamingContentRef (ref,无渲染)
      → rAF+33ms 节流 → startTransition(setMessages)
      → MessagesContext value 变 → App + ChatScreen 重渲染(顶层,成本低)
      → MessageList(memo) 收新 messages prop
      → 仅流式那条 MessageItem(memo)重渲染 → DOM 文本更新
```

**这是接近最优的 JCEF 流式渲染架构。Context 在流式场景下不是瓶颈。**

---

## 7. God Component 观察(App.tsx)

`App.tsx` 553 行,调 ~20 个 hooks(`useScrollBehavior` / `useStreamingMessages` / `useModelProviderState` / `useWindowCallbacks` / `useMessageSender` / `useSessionManagement` / `useChatComputations` …),是事实上的 God Component。

**与 A1 的关系**:Context value 在 App 解构后,**再通过 props 下传**给 `ChatScreen` / `SettingsView` / `HistoryView`(`App.tsx:479-514` 传 ~30 props 给 ChatScreen)。即:**当前 Context 并未真正消除 prop drilling,只是集中了 state 来源**。这意味着:

- Context 迁 Zustand **不会减少** App→ChatScreen 的 prop drilling(Zustand 也得在 ChatScreen 取再下发,或 ChatScreen 子树各自订阅);
- 真正要减 prop drilling,需让 ChatScreen **子树**(ChatInputBox / WelcomeScreen / MessageList / StatusPanel)直接 `useModelProvider()`——目前仅 ChatScreen 一层消费(`ChatScreen.tsx:122`)。

此为架构观察,非性能判定:God Component 是可读性/可维护性债务,但**与 Context 是否瓶颈无关**。

---

## 8. 量化基线结论

| 判定项 | 结论 | 证据 |
| --- | --- | --- |
| Context value 是否泄漏引用 | ❌ 否,全 `useMemo` | §3 |
| 是否存在叶子组件全量订阅 | ❌ 否,consumer 全顶层 | §2.2 / §4.2 |
| 流式是否 O(n) 重渲染 | ❌ 否,四层隔离到 O(1) | §6 |
| `bridgeState` 是否可迁 store | ❌ 否,同步黑板语义不可替代 | §5 |
| 是否有 runtime 基线证明 Context 是瓶颈 | ❌ 无(亦无 runtime 基线) | §1 / §11.4 |

**核心结论**:**当前 React Context 架构健康,不存在支持「迁移 Zustand」的结构性证据**。流式场景(性能最敏感路径)已被四层隔离优化到 O(1) 重渲染,Context 不是瓶颈。

---

## 9. A1 迁移建议

### 9.1 主结论:**当前不迁移 Zustand**

依据 §8 五项判定,迁移 Zustand 的收益( selector 订阅 / 减 prop drilling)**在当前 consumer 拓扑下不可见**,而成本(6 Context + App God Component 全量重构 + `bridgeState` 语义适配)与风险(流式竞态防护回归)高。符合 §A1「只有测量证明存在系统性瓶颈后,再按 Context 逐个迁移」的纪律——**测量结论是「未达迁移门槛」**。

### 9.2 可选局部治理(预防性,低优先,非本次必须)

仅当未来出现以下信号时才推进,且每项独立立项:

1. **新增叶子 consumer 只读单字段**:届时拆分 selector hook(如 `useMessagesStatus()` 只订阅 `status`),而非全量 `useMessages()`。当前无此 consumer。
2. **ModelProviderContext 手写 24 字段依赖**:新增字段易漏(`ModelProviderContext.tsx:61-87`)。可加注释提醒或转 `useMemo(() => value, [value])` 整体引用(取舍:整体引用退化为每次新值都变,失去字段级稳定)。当前功能正确,性能无影响,保持现状。

### 9.3 Runtime Profiling(独立立项,补充 §11.4 验收门槛)

若要 100% 确认,需在 JCEF 内采集以下基线(静态分析无法替代):

- React Profiler:App / ChatScreen 流式时每帧 render 耗时与 render count;
- performance trace:streaming FPS、long task、first paint、TTI;
- 历史恢复大列表(1000+ 消息)scroll anchoring。

**触发条件**:仅当用户报告可感知卡顿,或 §11.4 性能门槛纳入硬性 CI 时启动。当前无卡顿报告,静态结论预示瓶颈概率低,暂不立项。

---

## 10. 证据索引

| 结论 | 文件:行 |
| --- | --- |
| Provider 嵌套 4 层 | `webview/src/main.tsx:102-113` |
| ModelProvider 条件挂载 | `webview/src/App.tsx:468` |
| MessagesContext value memo | `webview/src/contexts/MessagesContext.tsx:49-71` |
| SessionContext value memo | `webview/src/contexts/SessionContext.tsx:36-48` |
| UIStateContext value memo | `webview/src/contexts/UIStateContext.tsx:83-100` |
| ModelProvider 手写 24 字段 | `webview/src/contexts/ModelProviderContext.tsx:61-87` |
| SubagentContext getter 空依赖 | `webview/src/contexts/SubagentContext.tsx:55-65` |
| consumer 全顶层 | `App.tsx:40,52,60,70` / `ChatScreen.tsx:122,127,128,136` / `AppDialogs.tsx:65,68` |
| 流式全 ref | `webview/src/hooks/useStreamingMessages.ts:16-45` / `App.tsx:121-128` |
| THROTTLE_INTERVAL = 33 | `webview/src/hooks/useStreamingMessages.ts:14` |
| rAF + startTransition 调度 | `webview/src/hooks/windowCallbacks/registerCallbacks/streamingCallbacks.ts:508-554` |
| MessageList memo | `webview/src/components/MessageList.tsx:138` |
| MessageItem memo | `webview/src/components/MessageItem/MessageItem.tsx:266` |
| bridgeState 同步黑板 | `webview/src/bridge/store.ts:11-18, 80-97` |
| 流式 updater 时序注释 | `streamingCallbacks.ts:835-843, 962-964` |
