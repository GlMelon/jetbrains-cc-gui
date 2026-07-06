# AI 对话响应流畅度与状态提示优化方案

## 1. 目标

本方案用于优化插件 AI 对话的首响速度、流式响应流畅度和等待阶段反馈体验，重点覆盖：

- 发送后到首个 token 之间的感知响应；
- AI 对话流式输出期间的前端渲染流畅度；
- provider/runtime 冷启动导致的等待感；
- “已连接 / 正在理解问题 / 正在思考 / 正在响应”等状态提示的视觉表达；
- 后续可扩展到 Claude / Codex / OpenCode，SDK / CLI 六条路径的对称处理。

## 2. 当前现状

### 2.1 已有性能保护

当前代码中已经存在一些有效优化：

- 后端 `SessionCallbackAdapter` 对 content/thinking delta 做约 30fps 节流。
- 后端 `StreamMessageCoalescer` 对全量 `updateMessages` 做合并推送，避免高频全量刷新。
- 前端 `streamingCallbacks.ts` 使用 `requestAnimationFrame` 与 `startTransition` 更新 streaming message。
- 后端存在 heartbeat，避免工具执行阶段被前端 stall watchdog 误判。
- Claude SDK 已有 daemon 预热：`ClaudeDaemonCoordinator.prewarmDaemonAsync(...)`。
- 新会话与加载历史时，Claude SDK 模式会触发预热。

### 2.2 当前体验短板

1. **首响前状态反馈不足**
   - 发送后到 `stream_start` 前，用户缺少明确阶段反馈。
   - 当前主要依赖 loading 与空 assistant placeholder。

2. **“已连接，正在理解问题”样式偏弱**
   - 当前样式是简单灰色文本 `.streaming-connect-status`。
   - 缺少层次、阶段感和动效。

3. **状态阶段没有统一协议化**
   - 当前前端通过 placeholder、`__suppressStreamingConnectHint` 等 UI 标记控制提示。
   - 如果继续由前端判断“连接中 / 理解中 / 思考中 / 响应中”，会违背前后端职责分离。

4. **Codex / OpenCode 首响可能受同步上下文构造影响**
   - `SessionContextService.buildCodexContextAppend(...)` 会在发送路径同步读取 active file 与 referenced files。
   - 大文件或多引用文件场景可能增加首响耗时。

5. **预热覆盖不均衡**
   - Claude SDK 已有预热。
   - Codex / OpenCode SDK 或 CLI 预热尚未统一设计。
   - 后续若新增预热，需遵循多 provider × runtime 对称性原则。

## 3. 架构约束

本项目应继续遵循 `AGENTS.md` 中的架构准则：

- 前端只做渲染、输入采集和纯 UI 状态。
- 业务语义、阶段判定、默认值、能力判断必须下沉后端。
- 下行事件必须使用 `DownstreamEvent`，前端从 `webview/src/generated/protocol.ts` 消费协议常量。
- provider 值不得硬编码，应使用 `ProviderType` / `CommonConstants` 等 SSOT 常量。
- 修改 provider/runtime 横切能力时，必须检查 Claude / Codex / OpenCode × SDK / CLI 的覆盖情况。

因此，“正在连接 / 正在理解 / 正在思考 / 正在响应 / 正在调用工具”等阶段，不应由前端根据 delta 自行推断，而应由后端统一下发 phase 或 UI-ready payload，前端只负责展示。

## 4. 推荐优化路线

### Phase 1：优先提升感知速度与提示体验

#### 4.1 新增 AI 响应阶段模型

建议后端维护并下发统一响应阶段：

| phase | 含义 | 触发建议 |
|---|---|---|
| `queued` | 请求已提交 | 用户发送后，后端接受请求时 |
| `connecting` | 正在连接 provider/runtime | runtime send 前或冷启动前 |
| `connected` | 已连接 | provider stream_start 或 daemon/CLI 建连成功 |
| `understanding` | 正在理解问题 | stream_start 后、首个 thinking/content delta 前 |
| `thinking` | 正在思考 | 首个 thinking delta 或 reasoning status 到达 |
| `responding` | 正在响应 | 首个 content delta 到达 |
| `tooling` | 正在调用工具 | tool_use / command / file operation 开始 |
| `finalizing` | 正在整理回答 | stream_end 前后短暂阶段，可选 |
| `done` | 完成 | stream_end 完成 |
| `error` | 出错 | provider error / runtime error |

建议 payload 由后端计算完成，例如：

```json
{
  "phase": "thinking",
  "providerLabel": "Codex",
  "title": "正在思考",
  "description": "正在分析上下文和你的问题",
  "elapsedMs": 1280,
  "active": true
}
```

前端不解释 phase 的业务含义，只渲染 title、description、providerLabel、active 等字段。

#### 4.2 替换当前连接提示组件

当前 `MessageItem.tsx` 中空 assistant placeholder 会渲染 `.streaming-connect-status`。

建议新增纯展示组件：

```tsx
<AssistantResponseStatus payload={statusPayload} />
```

组件职责：

- 展示 provider 名称；
- 展示阶段标题与说明；
- 展示轻量动效；
- 内容开始输出后淡出或折叠；
- 不做业务判断。

#### 4.3 发送后立即展示 optimistic 状态

后端接收到 send action 后尽快下发 `queued` 或 `connecting`，让用户立即看到反馈。

建议流程：

1. 用户发送消息；
2. 后端接受请求，立刻下发 `queued`；
3. runtime 路由前下发 `connecting`；
4. provider stream_start 后下发 `understanding`；
5. thinking delta 到达后下发 `thinking`；
6. content delta 到达后下发 `responding`；
7. stream_end 后下发 `done`。

### Phase 2：降低真实首响耗时

#### 4.4 设置与运行时快照缓存

发送路径中可缓存这些只读配置：

- selected agent prompt；
- streaming enabled；
- runtime policy；
- model registry resolved selection；
- Codex runtime access mode；
- provider display metadata。

缓存失效来源：

- settings changed；
- provider/model/runtime mode changed；
- project changed；
- user explicitly refreshes configuration。

收益：减少每次 send 的同步配置读取与 JSON 解析。

#### 4.5 IDE 上下文异步快照

当前 `buildCodexContextAppend(...)` 在发送路径同步构造上下文。建议改为后台维护快照：

- active editor 变化时异步读取 active file；
- selection 变化时更新 selected text；
- file tag 变化时异步读取引用文件；
- 发送时直接读取最近有效快照；
- 快照过期或缺失时再降级同步构造。

需要注意：

- 文件读取大小仍受 `maxFileSizeBytes` 限制；
- 快照要带 version / timestamp，避免使用明显过期内容；
- 发送时可在 prompt 中标注上下文快照时间，必要时后端刷新。

#### 4.6 Streaming Markdown 轻渲染

流式输出期间可采用轻渲染策略：

- streaming 中优先渲染纯文本 / 简单 markdown；
- 暂缓复杂 code highlight、mermaid、表格重排；
- stream end 后再触发完整 markdown 渲染。

收益：降低前端流式期间的 CPU 压力，提升滚动和输入响应。

### Phase 3：深度协议与 runtime 预热

#### 4.7 Tail message patch 协议

当前已有 delta 快速通道，但结构性变化仍可能触发全量消息同步。

后续可新增 tail patch 协议：

- content delta：继续走现有快速通道；
- tool block / usage / status：走结构 patch；
- stream end：再推权威 full snapshot。

该阶段风险较高，需要完整兼容历史消息、工具块、usage、错误恢复。

#### 4.8 RuntimePrewarmService

建议设计统一预热服务：

```text
RuntimePrewarmService
  ├─ ClaudeSdkPrewarmer
  ├─ CodexSdkPrewarmer
  ├─ OpenCodeSdkPrewarmer
  └─ CliProbePrewarmer
```

预热策略建议：

| 策略 | 行为 | 风险 |
|---|---|---|
| off | 不预热 | 无资源占用 |
| current-provider | 只预热当前 provider 的 SDK runtime | 推荐默认 |
| recent-provider | 预热最近使用 provider | 中等资源占用 |
| all-sdk | 预热所有 SDK provider | 资源占用较高 |

CLI 模式默认不建议后台启动真实对话进程，可只做：

- executable path 检查；
- version probe；
- 登录状态轻量检查；
- 失败结果缓存。

## 5. 状态提示视觉方案

静态 HTML 效果图见：

- `docs/ai-response-status-preview.html`

包含三套方案：

### 5.1 方案 A：极简内联胶囊

特点：

- 最轻量；
- 不抢聊天空间；
- 适合默认方案；
- 可以替换当前 `.streaming-connect-status`。

适用场景：希望提示优雅但不过分醒目。

### 5.2 方案 B：玻璃质感状态卡片

特点：

- 展示 provider、阶段、进度轨；
- 视觉高级；
- 信息密度更高；
- 占用空间略多。

适用场景：希望用户明确看到 AI 当前进度。

### 5.3 方案 C：助手气泡内呼吸式 Loading Block

特点：

- 与 assistant 回复融为一体；
- 内容开始输出后自然淡出；
- 体验最自然；
- 推荐作为最终方向。

适用场景：希望等待状态像 AI 回复的一部分，而不是额外 toast。

## 6. 推荐决策

建议优先采用：

1. **视觉采用方案 C**：助手气泡内呼吸式 Loading Block。
2. **紧凑模式保留方案 A**：适合用户配置或窄屏场景。
3. **暂不默认采用方案 B**：可作为可选增强样式。

建议第一轮实现范围：

- 后端新增响应阶段下发；
- 前端新增 `AssistantResponseStatus` 纯展示组件；
- 替换当前空 placeholder 的连接提示；
- 增加发送后即时 `queued/connecting` 状态；
- 不先做 tail patch 协议，避免范围过大。

## 7. 验收标准

### 7.1 体验验收

- 用户点击发送后 100ms 内看到明确反馈。
- provider 连接成功后状态从“连接中”切换到“正在理解问题”。
- thinking delta 到达后显示“正在思考”。
- content delta 到达后显示“正在响应”，随后状态提示弱化或淡出。
- tool_use 阶段可显示“正在调用工具”。
- stream_end 后状态消失或转为完成态。

### 7.2 性能验收

- 长回答流式输出时，输入框和滚动不明显卡顿。
- 大 active file / 多引用文件场景下，发送后 UI 不冻结。
- 设置缓存失效后不会使用旧 provider/model/runtime 配置。

### 7.3 架构验收

- 前端不自行推断业务 phase。
- 新下行事件使用 `DownstreamEvent`。
- 前端协议常量从 generated protocol 导入。
- provider 字符串不硬编码。
- 涉及预热时，对 Claude / Codex / OpenCode × SDK / CLI 做覆盖矩阵检查。

## 8. 后续实施拆分建议

1. `feat(session): push assistant response phase updates`
   - 后端阶段模型与下行事件。

2. `feat(webview): render assistant response status indicator`
   - 前端状态提示组件与样式。

3. `refactor(session): cache send-time settings snapshot`
   - 发送路径配置快照缓存。

4. `feat(context): prepare asynchronous IDE context snapshot`
   - 异步上下文快照。

5. `refactor(runtime): introduce provider prewarm service`
   - 统一 runtime 预热服务。
