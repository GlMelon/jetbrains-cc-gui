# Backend Runtime Authority and Claude Entrypoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the backend runtime policy the sole authority for every Claude, Codex, and OpenCode send, and normalize Claude CLI history entrypoints from `sdk-cli` to `cli` after the plugin-owned CLI process exits.

**Architecture:** Remove request/session invocation-mode precedence from the send path and resolve `RuntimeType` directly from the current `RuntimePolicyConfig` on every operation. Keep settings UI as a configuration editor/display only; ordinary chat payloads do not carry runtime choices. Extract JSONL rewriting into a UI-independent service reused by manual conversion and by Claude CLI completion.

**Tech Stack:** Java 17, IntelliJ Platform, Gson, React/TypeScript, Vitest, Gradle/JUnit 5.

## Global Constraints

- Frontend code may render backend state and submit settings changes, but must not decide the runtime for a chat turn.
- Runtime selection must read `CodemossSettingsService.getRuntimePolicy()` for every send/interrupt/SDK-validation decision.
- Existing sessions must use a newly selected backend runtime on their next message.
- No provider protocol string literals may be introduced; use `ProviderType`, `CommonConstants`, and existing protocol enums.
- Claude CLI history rewriting runs only after the owned CLI process exits, only for the current session, and only changes `sdk-cli` to `cli`.
- Rewriting failure is logged and must not turn a successful model response into a failed response.
- Existing uncommitted changes in `ProjectConfigHandler`, `SessionLifecycleManager`, and `SessionLifecycleManagerTest` must be integrated rather than reverted wholesale.

---

### Task 1: Make runtime policy the sole resolver input

**Files:**
- Modify: `src/main/java/com/github/claudecodegui/session/runtime/EffectiveRuntimeResolver.java`
- Modify: `src/main/java/com/github/claudecodegui/session/SessionSendService.java`
- Modify: `src/main/java/com/github/claudecodegui/session/ClaudeSession.java`
- Modify: `src/main/java/com/github/claudecodegui/handler/session/SessionActionHandlers.java`
- Test: `src/test/java/com/github/claudecodegui/session/runtime/EffectiveRuntimeResolverTest.java`

**Interfaces:**
- Produces: `EffectiveRuntimeResolver.resolve(String provider, RuntimePolicyConfig policy)` and `isCliMode(String provider, RuntimePolicyConfig policy)`.
- Consumes: `RuntimePolicyConfig.of(ProviderType)` and `ProviderRuntimePolicy.defaultRuntime()`.

- [ ] **Step 1: Replace resolver tests with backend-policy authority cases**

Add tests proving all three providers return `defaultRuntime()`, that changing policy changes the result for an existing caller, and that disabled providers fail fast.

- [ ] **Step 2: Run the focused resolver test and verify the old API/behavior fails**

Run: `./gradlew test --tests '*EffectiveRuntimeResolverTest'`
Expected: FAIL until the resolver no longer accepts request/session modes.

- [ ] **Step 3: Simplify `EffectiveRuntimeResolver`**

Implement only policy lookup, enabled validation, and `defaultRuntime()` return. Remove normalization, explicit-choice precedence, degradation state, and request/session-mode overloads.

- [ ] **Step 4: Remove request runtime parameters from the send chain**

Change `ClaudeSession.send`, `SessionSendService.sendMessageToProvider`, `sendToClaude`, `sendToCodex`, and `sendToOpenCode` so no `requestedInvocationMode`/`effectiveInvocationMode` is accepted or forwarded. Resolve each provider runtime with the current settings policy immediately before constructing `SessionRequest`.

- [ ] **Step 5: Update interrupt and SDK-validation decisions**

Use the same two-argument resolver in `SessionSendService.interruptRuntime`, `ClaudeSession.isCliRuntime`, and `SessionActionHandlers.isCliModeActive`; update diagnostic logging to report the resolved backend runtime rather than a session snapshot.

- [ ] **Step 6: Run focused tests**

Run: `./gradlew test --tests '*EffectiveRuntimeResolverTest' --tests '*SessionSendServiceTest' --tests '*SessionActionHandlersTest'`
Expected: PASS (Gradle may report no matching optional test class only if it does not exist; rerun existing matching classes explicitly).

### Task 2: Remove session invocation-mode snapshots and legacy chat plumbing

**Files:**
- Modify: `src/main/java/com/github/claudecodegui/session/SessionState.java`
- Modify: `src/main/java/com/github/claudecodegui/session/SessionLifecycleManager.java`
- Modify: `src/main/java/com/github/claudecodegui/ui/ChatWindowDelegate.java`
- Modify: `src/main/java/com/github/claudecodegui/ui/toolwindow/ClaudeChatWindow.java`
- Modify: `src/main/java/com/github/claudecodegui/handler/ProjectConfigHandler.java`
- Modify: `src/main/java/com/github/claudecodegui/handler/context/GetContextUsageActionHandler.java`
- Modify: `src/main/java/com/github/claudecodegui/settings/TabStateService.java`
- Test: `src/test/java/com/github/claudecodegui/session/SessionStateTest.java`
- Test: `src/test/java/com/github/claudecodegui/session/SessionLifecycleManagerTest.java`

**Interfaces:**
- Produces: session state without `claudeInvocationMode` persistence or inheritance.
- Consumes: backend runtime policy for any runtime display/status calculation.

- [ ] **Step 1: Update tests to reject snapshot semantics**

Delete tests for `SessionState.get/setClaudeInvocationMode`; update lifecycle tests so session reset/new-session behavior has no invocation-mode assertion or inheritance setup.

- [ ] **Step 2: Run focused tests and verify compilation fails against the old snapshot API edits**

Run: `./gradlew test --tests '*SessionStateTest' --tests '*SessionLifecycleManagerTest'`
Expected: FAIL until product references are removed consistently.

- [ ] **Step 3: Remove snapshot state and persistence**

Delete the `claudeInvocationMode` field/accessors/validation from `SessionState`, remove initialization/inheritance/runtime-state payload code from `SessionLifecycleManager` and `ChatWindowDelegate`, and remove tab snapshot save/restore fields in `ClaudeChatWindow`/`TabStateService`.

- [ ] **Step 4: Remove session mutation from settings actions**

Keep settings actions as backend configuration writes and broadcasts, but delete `context.getSession().setClaudeInvocationMode(...)` and session-first reads. Runtime status must derive from current policy rather than a session object.

- [ ] **Step 5: Update context-usage/runtime diagnostics**

Replace session snapshot reads with `EffectiveRuntimeResolver.resolve(provider, settings.getRuntimePolicy())` and serialize/display the resolved runtime only where the UI genuinely needs backend state.

- [ ] **Step 6: Run focused tests**

Run: `./gradlew test --tests '*SessionStateTest' --tests '*SessionLifecycleManagerTest' --tests '*ProjectConfigHandlerTest'`
Expected: PASS.

### Task 3: Align legacy Claude invocation setting with runtime policy

**Files:**
- Modify: `src/main/java/com/github/claudecodegui/settings/CodemossSettingsService.java`
- Modify: `src/main/java/com/github/claudecodegui/handler/ProjectConfigHandler.java`
- Modify: `src/main/java/com/github/claudecodegui/ui/toolwindow/ClaudeSDKToolWindow.java`
- Modify: `src/main/java/com/github/claudecodegui/ui/WebviewInitializer.java`
- Test: relevant settings/runtime-policy tests under `src/test/java/com/github/claudecodegui/settings/` and `src/test/java/com/github/claudecodegui/handler/`.

**Interfaces:**
- Produces: compatibility `get/setClaudeInvocationMode` that reads/writes Claude `ProviderRuntimePolicy.defaultRuntime()` rather than a second independent setting.
- Consumes: `RuntimeType.fromInvocationMode` and the existing runtime policy persistence API.

- [ ] **Step 1: Add compatibility tests**

Prove a legacy mode read reflects Claude runtime policy and a legacy mode write updates Claude runtime policy without creating conflicting state.

- [ ] **Step 2: Run focused settings tests and verify failure**

Run: `./gradlew test --tests '*CodemossSettingsServiceTest' --tests '*RuntimePolicy*Test'`
Expected: FAIL before compatibility methods delegate to runtime policy.

- [ ] **Step 3: Implement migration-compatible delegation**

Read the Claude default from `getRuntimePolicy().of(ProviderType.CLAUDE)`. On write, preserve Claude `supported/enabled` values while replacing `defaultRuntime`, then persist through the existing runtime-policy setter. Retain old persisted value only as one-time migration input if current policy is absent.

- [ ] **Step 4: Update tool-window/webview initialization**

Ensure broadcasts and CLI-specific initialization checks use the resolved backend policy and do not create session state.

- [ ] **Step 5: Run focused tests**

Run: `./gradlew test --tests '*CodemossSettingsServiceTest' --tests '*RuntimePolicy*Test' --tests '*ProjectConfigHandlerTest'`
Expected: PASS.

### Task 4: Extract a UI-independent Claude history entrypoint rewriter

**Files:**
- Create: `src/main/java/com/github/claudecodegui/history/ClaudeSessionEntrypointRewriter.java`
- Modify: `src/main/java/com/github/claudecodegui/handler/history/SessionConversionService.java`
- Test: `src/test/java/com/github/claudecodegui/history/ClaudeSessionEntrypointRewriterTest.java`
- Test: `src/test/java/com/github/claudecodegui/handler/history/SessionConversionServiceTest.java`

**Interfaces:**
- Produces: `RewriteResult rewrite(Path projectDirectory, String sessionId, Set<String> acceptedSourceEntrypoints, String targetEntrypoint)`.
- Consumes: Claude project-history path resolution and atomic temp/backup replacement currently embedded in `SessionConversionService`.

- [ ] **Step 1: Add rewriter tests**

Cover `sdk-cli -> cli`, no-op for `cli`, refusal/no-op for unrelated entrypoints, session-specific file selection, missing file, malformed JSONL, and preservation of all unrelated JSON fields/lines.

- [ ] **Step 2: Run focused tests and verify the missing class failure**

Run: `./gradlew test --tests '*ClaudeSessionEntrypointRewriterTest'`
Expected: FAIL because the class does not exist.

- [ ] **Step 3: Move file-system rewriting into the new service**

Preserve existing WSL/path handling, per-session lock, temp file, backup, and atomic replacement behavior. Return a structured result instead of dispatching UI events.

- [ ] **Step 4: Make manual conversion delegate to the rewriter**

Keep active-session rejection and UI notifications in `SessionConversionService`; pass accepted sources `{sdk-cli, claude-vscode}` and target `cli` to the rewriter.

- [ ] **Step 5: Run focused history tests**

Run: `./gradlew test --tests '*ClaudeSessionEntrypointRewriterTest' --tests '*SessionConversionServiceTest'`
Expected: PASS.

### Task 5: Normalize Claude CLI history after process exit

**Files:**
- Modify: `src/main/java/com/github/claudecodegui/cli/claude/ClaudeCliSession.java`
- Modify: `src/main/java/com/github/claudecodegui/cli/CliConstants.java` or existing entrypoint constants owner if required.
- Test: `src/test/java/com/github/claudecodegui/cli/claude/ClaudeCliSessionTest.java`
- Test: `src/test/java/com/github/claudecodegui/provider/claude/ClaudeSDKBridgeRefactorTest.java`

**Interfaces:**
- Consumes: `ClaudeSessionEntrypointRewriter` and the final Claude session UUID captured from CLI stream events.
- Produces: best-effort post-exit normalization for plugin-owned Claude CLI sessions.

- [ ] **Step 1: Add source-level/lifecycle tests**

Prove the invalid `CLAUDE_CODE_ENTRYPOINT=cli` environment workaround is removed, the rewriter is invoked only after `process.waitFor`, only with a nonblank valid Claude session UUID, and accepts only `sdk-cli` for automatic conversion.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./gradlew test --tests '*ClaudeCliSessionTest' --tests '*ClaudeSDKBridgeRefactorTest'`
Expected: FAIL before integration.

- [ ] **Step 3: Remove the ineffective environment override**

Delete the `ENV_CLAUDE_CODE_ENTRYPOINT` injection and its misleading comments from `ClaudeCliSession`.

- [ ] **Step 4: Invoke the rewriter after process exit**

After stdout is drained and the process has exited, snapshot the session UUID and project cwd, call the rewriter with accepted source `{sdk-cli}` and target `cli`, and log no-op/failure results without changing callback success.

- [ ] **Step 5: Run focused CLI tests**

Run: `./gradlew test --tests '*ClaudeCliSessionTest' --tests '*ClaudeSDKBridgeRefactorTest' --tests '*ClaudeSessionEntrypointRewriterTest'`
Expected: PASS.

### Task 6: Verify frontend payload independence and full symmetry

**Files:**
- Modify only if tests reveal regressions: `webview/src/hooks/useMessageSender.ts`
- Test: `webview/src/hooks/useMessageSender.context.test.ts`
- Test: runtime/session Java tests changed above.

**Interfaces:**
- Validates: normal and attachment chat payloads omit `invocationMode`; all three provider sends use current backend policy.

- [ ] **Step 1: Run frontend payload tests**

Run: `cd webview && npm test -- --run src/hooks/useMessageSender.context.test.ts`
Expected: PASS with assertions that both send payload variants lack `invocationMode`.

- [ ] **Step 2: Run Java tests**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 3: Build the webview and plugin**

Run: `cd webview && npm run build`
Expected: PASS.

Run: `./gradlew buildPlugin`
Expected: PASS.

- [ ] **Step 4: Perform source compliance searches**

Run: `rg -n "requestedInvocationMode|state\.getClaudeInvocationMode|state\.setClaudeInvocationMode" src/main/java`
Expected: no matches.

Run: `rg -n "invocationMode" webview/src/hooks/useMessageSender.ts`
Expected: no matches.

- [ ] **Step 5: Review working-tree scope**

Run: `git diff --check && git status --short`
Expected: no whitespace errors; only intended source, test, and plan files are modified.
