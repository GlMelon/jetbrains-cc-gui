# 思考强度(Reasoning Effort)多 Provider 档位适配方案与实施记录

> 日期:2026-09-07(2026-09-08 收尾)
> 状态:**已完成**(阶段一/二/三全部落地,验证通过;见 §3/§4)
> 背景:各 provider 思考强度档位不一(Claude 最高 max、Codex 最高 xhigh、Kimi k3 仅 low/high/max……),原实现为前端写死 + 各 session 各自降级,本次改为「协议全集词表 + 后端数据驱动能力表 + 通用 clamp 降级 + 动态接口查询」。

---

## 1. 官方调研结论(2026-09-07,一手文档核实)

### 1.1 各 provider 档位全集与传递方式

| Provider | 官方档位(低→高) | 传递方式 | per-model 差异 |
|---|---|---|---|
| Claude | low/medium/high/xhigh/max | `--effort` / API `output_config.effort` | 4.6 代无 xhigh;Haiku 不支持 effort |
| Codex | minimal/low/medium/high/xhigh(CLI `model_reasoning_effort` 枚举) | 无专用 flag,`-c model_reasoning_effort=` | gpt-5.1 → [none,low,medium,high];gpt-5.2* → [low..xhigh];gpt-5 → [minimal..high] |
| OpenCode | 无统一词表,variant 为 per-model catalog 元数据 | `--variant`;serve HTTP API 可查 | 差异极大(gpt-5-pro 仅 high 等) |
| Grok | CLI 词表 7 档 none..max;API 实义 low/medium/high/xhigh | `--reasoning-effort` | 4.3 有 none;4.6+ 有 xhigh;3-mini 仅 low/high |
| Kimi | CLI 词表 5 档 low..max;**模型实际更窄**:k3 仅 low/high/max | ACP `set_config_option`(无 CLI flag) | 档位由模型元数据 `support_efforts` 动态声明 |
| Pi / OMP | off/minimal/low/medium/high/xhigh/max | `--thinking` | `thinkingLevelMap` per-model |
| DSH / MiniMax | 不支持(模型驱动) | 不透传 | — |

### 1.2 收到「不支持的档位」时的行为(决定必须本地钳制)

| Provider | 行为 |
|---|---|
| Claude | **静默回退**到 ≤ 所设档的最高支持档(透传安全) |
| Grok | **服务端安全降级**(xhigh→high);例外:grok-4.5 传 none 被拒 |
| Codex | **HTTP 400 报错** |
| OpenCode | **fail-fast**(未知 variant 模型解析失败) |
| Kimi | ACP 通道校验词表**拒绝非法值**;官方映射 medium→high、xhigh→max |
| Pi | warning + **忽略该参数**(静默丢档) |

### 1.3 典型场景结论

k3 传入 xhigh:ACP 会校验拒绝,正确做法是发送前本地映射 xhigh→max(官方映射表)。现有 `KimiAcpCliSession.mapThinkingEffort` 方向正确。

## 2. 最终方案(用户拍板)

**原则**:能接口查询的动态查(失败回退内置表);不能查的内置枚举+降级;任何情况下不允许因档位报错给用户。配置外置(用户自选菜单/JSON)判定为**非必要,不做**。

三层关注点分离:

1. **协议词表**:`ReasoningEffort` 枚举扩为 7 档 `none/minimal/low/medium/high/xhigh/max`(各 provider 词表并集),声明顺序 = 强度升序(ordinal 即强度序,clamp 依赖)。
2. **能力集 = 数据**:(provider, model) → 有序档位子集。权威来源优先级:
   - 运行时动态查询(kimi ACP configOptions / opencode serve / pi RPC)> 内置表
   - 内置表按**模型族前缀**匹配(非精确 id),同族新模型自动继承
   - 内置表角色 = UI 提示 + fail-fast provider 的本地拦截,**claude/grok 服务端可降级,表过时无害**
3. **降级收敛**:通用纯函数 `clamp(requested, supported)` = 取不超过请求值的最高支持档;provider 官方非单调映射(如 kimi xhigh→max,k3 有 max 无 xhigh 的"洞")留在各 session 的 wire 映射表。

## 3. 实施进度

### ✅ 阶段一:后端地基(已完成,36 测试全过,checkstyle 过)

- `protocol/ReasoningEffort.java`:扩 7 档;`webview/src/generated/protocol.ts` 已重新生成同步。
- 新增 `reasoning/ReasoningEffortResolver.java`:`clamp(requested, supportedLevels)`;非法/空 → null(不透传)。
- 新增 `reasoning/ReasoningCapabilities.java`:`levelsFor(provider, modelId)` + `providerDefaults()`;内置表 = provider 默认集 + 模型前缀规则(最长前缀优先,先剥 `[1m]` 容量后缀)。codex 三条规则、grok 五条(含 4.6+ 的 xhigh)、pi/omp 全集、opencode 安全子集 [low,medium,high]、dsh/minimax → null。claude 不在表内(保持 `ClaudeRole.reasoningLevels()` role 派生)。
- `settings/ModelRegistryService.java`:`CAPABILITY_PROVIDERS` 扩至全 provider;serialize 对所有有能力 provider 下发 item 级 `supportedReasoningLevels`;root 新增 **`providerDefaults`** 字段(codex/grok/kimi/pi/omp/opencode 六家默认档)。
- 新增测试:`ReasoningEffortResolverTest`(8 例)、`ReasoningCapabilitiesTest`(7 例)、`ModelRegistryServiceReasoningLevelsTest`(5 例)。
- 偏差说明:`providerDefaults` 未列入 `ModelRegistryPayloadField` 白名单(该枚举是 per-item SSOT,root 字段列入会破坏守门测试);key 由 `ModelRegistryService.PROVIDER_DEFAULTS_KEY` 常量承载。

### ✅ 阶段二:session 收敛 + 动态查询(2026-09-08 收尾完成)

- **codex**:exec / exec resume 两路径的 `-c model_reasoning_effort=` 均改为发送前 clamp
  (`CodexCliSession.clampReasoningEffort` → `ReasoningCapabilities.levelsFor(codex, model)`;gpt-5.1 无
  xhigh、gpt-5 仅 minimal..high、未知默认 [low..xhigh])。**400 兜底降档重试不落地**(偏差决策):
  clamp 后残余 400 仅来自内置表滞后,重试无法修复数据问题,反而引入进程级重发复杂度——按本方案
  原文「侵入性过大则仅 clamp」条款执行。
- **grok**:`normalizeEffort` 的 xhigh/max→high 本地钳制已移除,改为能力表 clamp 后透传
  (签名加 model 参数;查表用原始 model 而非 remap 后的 profile)。4.6+ xhigh 直发,
  max→xhigh(词表有 max 但 API 实义上限 xhigh),3-mini medium→low,4.3 none 直发,
  未知族(含 grok-4.5 legacy id)默认 [low,medium,high]——4.5 传 none 被拒的官方例外由
  clamp 到 low 覆盖。
- **kimi**:核对结论——`mapThinkingEffort` 的 xhigh/max→max 与官方一致;但协商层
  `negotiateThinkingValue` 在等距 tie 时取低档,与官方 k3 映射 **medium→high 相悖**。
  已修:tie 改取高档(官方映射方向),ACP 动态协商机制未动。
- **pi**:`resolveThinkingLevel` 改为能力表 clamp + 协议 `none` → wire `off` 映射
  (原白名单缺 none→选 none 会静默丢 flag)。pi 收到不支持档位 warning+忽略,clamp 防丢档。
- **omp**:clamp 落在 Java 侧 `ChannelCliSession.clampReasoningEffort`(能力表 SSOT 在 Java,
  总则三/四;dsh/minimax 无表 → 原样透传不拦,对齐「不动」决策);wire 映射
  `none`→`off` 落在 ai-bridge `services/omp/message-service.js`(Node 只做 wire 映射)。
- **opencode**:one-shot 与 serve 共用新 SSOT `AbstractRunOnceCliSession.resolveReasoningVariant`
  (effort, model, availableVariantIds):
  - one-shot(无目录)→ 内置安全子集 [low,medium,high] clamp 后走 `mapReasoningVariant`
    (xhigh/max 钳 high,不盲发;unknown variant 模型解析 fail-fast,本地拦)。
  - serve → `OpenCodeServeClient.modelVariants`(**GET /provider** 动态目录,opencode v1.18.26
    实测契约:`all[].models[modelID].variants` 的 keys;按 model 缓存含空目录负缓存,查询失败
    不缓存下轮重试)→ 目录 id 反查协议档位 clamp 后取目录内实际 id(LOW 优先 minimal,
    MEDIUM 省略=默认档,空/未知 id 目录 → 不携带)。失败回退内置表。
- **dsh/minimax**:未动(dsh selectModel 的 effort 透传保持,由 f3853edb 测试锁定)。

### ✅ 阶段三:前端去硬编码(2026-09-08 收尾完成)

- `reasoningUtils.ts`:删除 grok/codex/claude 硬编码分支;档位列表 = 模型 `supportedReasoningLevels` → `providerDefaults[provider]` → 未知(全量展示但 guard 不改写持久化值)。三态契约:条目在但无字段 = 已知无能力(隐藏);条目不在 = 未知(展示全集)。
- `modelRegistry.ts`:新增 `getProviderDefaultReasoningLevels(provider)` + `resolveReasoningLevels(provider, modelId)`(三态解析)+ `providerDefaults` root 字段解析(非法档位过滤)。
- `types.ts`:REASONING_LEVELS 补 none/minimal;全 locale 补 i18n key(none=关闭思考,minimal=极轻思考)。
- **modelRegistry.test.ts 的 16 个预先存在失败已修复**(2026-09-08):fixture 补 `identifier`(parse 自 4324bc09 起必填);删除已无消费方的 `resolveClaudeRoleForModel` describe 块(源导出早已移除的 test/src 漂移);并为 `resolveReasoningLevels` / `getProviderDefaultReasoningLevels` / providerDefaults 解析补 7 个用例。
- ReasoningSelect.test.tsx 同步三态语义:自定义 claude 模型「在 registry 但无 role」→ 隐藏;「不在 registry」→ 未知 → 展示全集。

## 4. 收尾验证记录(2026-09-08)

1. ~~收 agent-7 / agent-8 的报告,核对偏差决策~~ → 已由主线核对收尾,偏差决策见阶段二各项。
2. 最终验证已通过:
   - `./gradlew compileJava compileTestJava --offline` ✓;`checkstyleMain checkstyleTest` ✓
   - 定向 `./gradlew test`(reasoning / grok / pi / kimi / codex / opencode serve 共 118 例)✓
   - `cd ai-bridge && node --import tsx --test test/services/omp/ test/services/dsh/` ✓
   - `cd webview && npx vitest run`(modelRegistry / ReasoningSelect / ButtonArea / reasoningEffort)✓;`tsc --noEmit` ✓
   - `node scripts/check-i18n-keys.mjs` ✓(主语言完整,coverage 未低于 baseline)
3. 按总则八分批 commit(见 §5 切分)。
4. 偏差/未决决议:
   - **codex 400 重试:不落地**(仅 clamp)。理由见阶段二 codex 项。
   - **kimi tie 方向修正**:协商层并列取高档(官方 medium→high),原「烧 token 保守取低档」
     与官方行为相悖,已修并更新测试。
   - claude HAIKU 内置表现状保留 3 档(官方称 Haiku 不支持 effort;claude 服务端静默降级,
     表仅作 UI 提示,未改行为)。
   - ~~modelRegistry.test.ts 的 16 个预先存在失败~~ → 已修复(见阶段三)。
   - 内置表防滞后:后续若官方出新模型族前缀,需更新 `ReasoningCapabilities`
     (kimi/opencode/pi 走动态查询不受影响;claude/grok 服务端兜底)。
   - 已知无关漂移(不在本任务范围,未处理):webview `permissionMode.test.ts` 锁 5 值但
     权限模式已 6 值;`useGlobalCallbacks` / `NodeProcessSelect` 定位测试 3 例失败——均为
     预先存在,与本方案改动无关。

## 5. 提交切分(实际)

- `feat(protocol): extend reasoning effort vocabulary to 7 levels`(协议枚举 + 生成产物 + SSOT 测试)
- `feat(model-registry): derive reasoning levels for all providers`(后端能力表 + Resolver + 下发 + 测试)
- `refactor(cli): unify effort clamping via resolver`(codex/grok/pi/omp/java 各 session + ai-bridge omp wire 映射 + kimi tie 修正 + opencode variant SSOT,含测试)
- `feat(opencode): query serve model variants dynamically`(serve GET /provider 动态目录 + 缓存 + 测试)
- `feat(webview): reasoning levels sourced from backend registry`(前端三态解析 + i18n + 测试)
- `test(webview): repair model registry fixtures and cover level resolution`(16 例预存失败修复 + 新增覆盖)
- `docs(reasoning): record capability adaptation completion`(本文档)
