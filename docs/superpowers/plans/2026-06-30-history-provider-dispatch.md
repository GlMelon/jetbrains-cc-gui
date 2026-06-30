# History Provider Dispatch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route history list, load, export, delete, and cache clearing through one provider adapter layer while preserving a single normalized message entrypoint.

**Architecture:** Add a `HistoryProviderAdapter` registry under `handler/history`. Existing history action services keep protocol/UI responsibilities only, while Claude/Codex/OpenCode storage differences live behind provider-specific adapters. Session reload continues through `SessionLifecycleManager` and `SessionMessageOrchestrator` so rendered messages share the same normalized load path.

**Tech Stack:** Java, Gson, IntelliJ Platform APIs, existing provider history readers and ai-bridge OpenCode history query.

## Global Constraints

- Default response language is Simplified Chinese; code identifiers stay original.
- Frontend must not gain provider-specific business logic.
- Provider protocol values must use existing SSOT constants/enums, not hardcoded provider strings.
- Existing dirty worktree changes must not be reverted or overwritten.
- This plan intentionally avoids changing SDK/CLI send routing; history routing is provider-first.

---

### Task 1: Add History Provider Adapter Registry

**Files:**
- Create: `src/main/java/com/github/claudecodegui/handler/history/HistoryProviderAdapter.java`
- Create: `src/main/java/com/github/claudecodegui/handler/history/HistoryProviderRegistry.java`
- Create: `src/main/java/com/github/claudecodegui/handler/history/HistoryDeleteResult.java`
- Create: `src/main/java/com/github/claudecodegui/handler/history/ClaudeHistoryProviderAdapter.java`
- Create: `src/main/java/com/github/claudecodegui/handler/history/CodexHistoryProviderAdapter.java`
- Create: `src/main/java/com/github/claudecodegui/handler/history/OpenCodeHistoryProviderAdapter.java`

**Interfaces:**
- Produces: `HistoryProviderAdapter.provider()`, `loadSessionsJson(projectPath)`, `loadMessages(sessionId, projectPath)`, `deleteSession(sessionId, projectPath)`, `clearCache(projectPath)`.
- Produces: `HistoryProviderRegistry.createDefault(context)`, `adapter(provider)`, `loadMessages(provider, sessionId, projectPath)`.

- [ ] **Step 1: Write adapter interfaces and registry**

Define the adapter contract and registry keyed by `ProviderType`.

- [ ] **Step 2: Implement Claude/Codex adapters**

Move existing reader/delete/cache behavior from `HistoryLoadService`, `HistoryDeleteService`, and `HistoryExportService` into provider-specific adapters.

- [ ] **Step 3: Implement OpenCode adapter as normalized-message capable**

Expose `loadMessages` through `OpenCodeSDKBridge.getSessionMessages`. Return empty session list and no-op delete/cache until OpenCode list/delete storage is explicitly implemented.

### Task 2: Route Existing History Services Through Registry

**Files:**
- Modify: `src/main/java/com/github/claudecodegui/handler/history/HistoryActionHandlers.java`
- Modify: `src/main/java/com/github/claudecodegui/handler/history/HistoryLoadService.java`
- Modify: `src/main/java/com/github/claudecodegui/handler/history/HistoryDeleteService.java`
- Modify: `src/main/java/com/github/claudecodegui/handler/history/HistoryExportService.java`
- Modify: `src/main/java/com/github/claudecodegui/handler/history/HistoryMessageInjector.java`

**Interfaces:**
- Consumes: `HistoryProviderRegistry`.
- Produces: existing public handler methods with unchanged upstream protocol behavior.

- [ ] **Step 1: Inject registry into history services**

Construct one registry in `HistoryActionHandlers` and pass it to load/delete/export/message injector services.

- [ ] **Step 2: Remove service-level provider branches**

Replace `if codex else claude` branches with registry calls.

- [ ] **Step 3: Preserve common enrichment**

Keep favorites and titles in `HistoryLoadService` after provider session listing, because those are cross-provider metadata.

### Task 3: Collapse Codex History Reload Bypass

**Files:**
- Modify: `src/main/java/com/github/claudecodegui/handler/history/HistoryMessageInjector.java`
- Modify: `src/main/java/com/github/claudecodegui/session/SessionLifecycleManager.java` only if needed.

**Interfaces:**
- Consumes: `HistoryActionHandlers.SessionLoadCallback`.
- Produces: all providers load via callback into `SessionLifecycleManager.loadHistorySession(sessionId, projectPath, provider)`.

- [ ] **Step 1: Parse provider payload as before**

Keep backward compatibility for raw `sessionId` payload and JSON `{sessionId, provider}` payload.

- [ ] **Step 2: Always call session load callback**

Do not directly inject Codex messages from `HistoryMessageInjector`; let `ClaudeSession.loadFromServer()` call provider history loading.

- [ ] **Step 3: Keep Codex conversion static methods**

Leave `convertCodexMessagesToFrontendBatch` as the single Codex raw-history normalization helper because `CodexSDKBridge.getSessionMessages` consumes it.

### Task 4: Tests and Verification

**Files:**
- Add or modify focused tests under `src/test/java/com/github/claudecodegui/handler/history/`.

**Interfaces:**
- Verifies registry routing, OpenCode non-fallback behavior, delete result aggregation, and Codex load callback behavior.

- [ ] **Step 1: Add registry/unit tests where platform dependencies allow**

Cover duplicate registration failure and unknown provider failure.

- [ ] **Step 2: Run targeted Gradle tests**

Run `rtk .\gradlew.bat test --tests "*History*"` or narrower tests if the suite is large.

- [ ] **Step 3: Run compile check if tests are blocked**

Run `rtk .\gradlew.bat test` if feasible; otherwise report the exact blocked command and reason.
