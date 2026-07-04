# 技术债登记:sessionId 按 provider 解耦

> 登记日期:2026-07-02
> 关联修复:跨 provider sessionId 污染致 Claude CLI `--resume` 崩(见 `cross-provider-session-id-pollution-claude-resume-crash` 记忆)

## 背景

2026-07-02 修跨 provider sessionId 污染致 Claude CLI `--resume` 崩。当时采用**对症最小修复**:
- `SessionState.setProvider` 跨 provider 清空 sessionId(同 provider 内 SDK↔CLI 保留)
- `ClaudeCliSession.maybeResetSessionAfterResumeFailure` 加 UUID 格式校验 + `not a uuid` 关键词防御纵深

用户提出更结构化的方案:**抽象层只定义需要生成会话 id,每个 provider 生成自己的 id,互不影响**。方向正确,但当前 `sessionId` 字段被重载承担三个职责,不能直接拆。登记此技术债待后续重构。

## 现状:`sessionId` 单字段被三职责重载

| 职责 | 格式要求 | 能否 per-provider 拆 |
|---|---|---|
| ① 会话续接(`--resume`/threadId) | provider 特定(Claude/Codex=UUID, OpenCode=`ses_` ULID) | ✅ 应拆,这是用户方案核心 |
| ② Permission 路由对齐 | 必须跨 bridge 统一 | ❌ 不能拆,拆了权限路由断 |
| ③ Tab/窗口身份(DetachTabAction/NodeProcessRegistry key) | 全局唯一即可 | ➖ 可独立或复用② |

**职责②铁证** `src/main/java/com/github/claudecodegui/ui/ChatWindowDelegate.java:462-464`:
```java
claudeSDKBridge.setSessionId(sessionId);
if (codexSDKBridge != null) {
    codexSDKBridge.setSessionId(sessionId);   // 故意把同一个 id 灌给两个 bridge
}
LOG.info("Unified bridge sessionId for PermissionService routing: " + sessionId);
```
权限请求要能从任意 bridge 路由到同一个 `PermissionService` 实例,故②必须统一。

## 对用户原始提案的两点修正

1. **不全是 UUID**:OpenCode 是 `ses_` 前缀 ULID,非 UUID。抽象应是"每 provider 拥有自己的会话续接 id,格式自定",而非"生成 UUID"。
2. **只拆职责①,职责②③必须留统一 id**,否则拆掉权限路由引入新 bug。

## 目标重构结构

```
Map<ProviderType, String> providerSessionIds;   // 职责①:每 provider 一个,格式自定
String permissionRoutingId;                      // 职责②:统一(envConfigurator 已有懒生成 UUID)
// 职责③ tab 身份可复用 permissionRoutingId
```

### 收益
- `setProvider` 不再清空,改为 `getProviderSessionId(provider)` 取当前 provider 槽位。
- 跨 provider 切回时**能续接原 provider 会话**(当前清空方案丢此能力)。
- 职责②③不受 provider 切换影响。
- 彻底消除"污染串台"类 bug 的根因(每 provider 隔离),不再依赖 setProvider 单点覆盖全路径 + maybeReset 防御纵深。

## 为何当前不立即重构(务实权衡)

- **跨 provider 续接同一会话非真实用例**:Claude `--resume` 续 Claude 历史,Codex 读不了;切 provider 实质=开新对话。当前"切就清空"丢的能力用户用不到。
- **重构面大**:`getSessionId()` 调用点遍布 PermissionService / EnvironmentConfigurator / DetachTabAction / NodeProcessRegistry / OpenCodeSDKBridge,需逐一理清是职责①还是②③,风险高于收益。
- **当前修复已根治崩溃**:setProvider 隔离(治本)+ maybeReset UUID 守卫(防御纵深)已落地且测试绿。

## 落地前置(重构时必做)

1. 全量 grep `getSessionId()` / `setSessionId(` 调用点(2026-07-02 已摸,见下表)。
2. 每个调用点判定职责①②③,分类迁移。
3. 职责②统一 id 复用 `EnvironmentConfigurator` 现有懒生成 UUID 逻辑(L262-265),不另造。
4. 职责① per-provider 槽位:ProviderType 作 key,值格式由各 provider 自己产生(Claude/Codex 收 CLI/SDK 回灌的 UUID;OpenCode 收 `ses_` ULID)。
5. 回归:既有 `SessionStateTest.switchingProviderClearsIncompatibleSessionId` / `sameProviderSetKeepsSessionId` 需改写为"切 provider 各自槽位隔离且切回可续"。

### 2026-07-02 摸清的调用点清单

| 文件:行 | 说明 |
|---|---|
| `session/SessionState.java:212` | 字段定义 + setter(setProvider 清空逻辑在此) |
| `ui/ChatWindowDelegate.java:462-464` | 职责②:统一灌给 claude/codex bridge |
| `bridge/EnvironmentConfigurator.java:192,262` | 职责②:env 变量 + 懒生成 UUID |
| `provider/common/BaseSDKBridge.java:145` | 职责②:转发给 envConfigurator |
| `action/tab/DetachTabAction.java:82,149` | 职责③:tab 身份 key |
| `service/NodeProcessRegistry.java:471` | 职责③:node 进程归属 |
| `permission/PermissionService.java:140` | 职责②:权限路由实例 key |
| `provider/opencode/OpenCodeSDKBridge.java:59` | 职责①:作 threadId 用 |
| `cli/claude/ClaudeCliSession.java:529` | 职责①:回灌 --resume sessionId |
