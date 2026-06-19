# Plugin Architecture Major Refactor Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first maintainable refactor milestone: typed protocol coverage, action-handler registration, Provider adapter registry, and backend-owned model selection results.

**Architecture:** Keep behavior compatible while adding new abstractions beside the legacy paths, then migrate targeted callers through adapters. Java becomes the business source of truth; React consumes generated protocol constants and backend-returned ViewModels; Node keeps execution-specific provider command adapters.

**Tech Stack:** Java 17, IntelliJ Platform plugin APIs, Gson, JUnit 4, Gradle, React 19, TypeScript, Vitest, Node ESM.

---

## Scope

This plan implements the first three phases from `docs/superpowers/specs/2026-06-19-plugin-architecture-major-refactor-design.md`:

1. Protocol closure and typed dispatcher foundation.
2. Provider adapter registry foundation.
3. Backend-owned model selection ViewModel and minimal frontend consumption.

Later plans will cover command-router migration, localStorage business-state removal, and legacy callback cleanup.

## File Structure

### Java Protocol And Dispatcher

- Create: `src/main/java/com/github/claudecodegui/protocol/ProtocolValue.java`
- Modify: `src/main/java/com/github/claudecodegui/protocol/UpstreamAction.java`
- Modify: `src/main/java/com/github/claudecodegui/protocol/DownstreamEvent.java`
- Modify: `src/main/java/com/github/claudecodegui/protocol/ProtocolManifestGenerator.java`
- Create: `src/main/java/com/github/claudecodegui/handler/core/FrontendActionHandler.java`
- Create: `src/main/java/com/github/claudecodegui/handler/core/FrontendActionContext.java`
- Create: `src/main/java/com/github/claudecodegui/handler/core/FrontendActionDispatcher.java`
- Create: `src/main/java/com/github/claudecodegui/handler/core/LegacyMessageHandlerAdapter.java`
- Test: `src/test/java/com/github/claudecodegui/protocol/ProtocolEnumCoverageTest.java`
- Test: `src/test/java/com/github/claudecodegui/handler/core/FrontendActionDispatcherTest.java`

### Java Provider Adapter Foundation

- Create: `src/main/java/com/github/claudecodegui/provider/ProviderId.java`
- Create: `src/main/java/com/github/claudecodegui/provider/ProviderAdapter.java`
- Create: `src/main/java/com/github/claudecodegui/provider/ProviderRegistry.java`
- Create: `src/main/java/com/github/claudecodegui/provider/ProviderContext.java`
- Create: `src/main/java/com/github/claudecodegui/provider/ProviderViewModel.java`
- Create: `src/main/java/com/github/claudecodegui/provider/claude/ClaudeProviderAdapter.java`
- Create: `src/main/java/com/github/claudecodegui/provider/codex/CodexProviderAdapter.java`
- Modify: `src/main/java/com/github/claudecodegui/session/SessionProviderRouter.java`
- Test: `src/test/java/com/github/claudecodegui/provider/ProviderRegistryTest.java`
- Test: `src/test/java/com/github/claudecodegui/session/SessionProviderRouterProviderRegistryTest.java`

### Backend-Owned Model Selection

- Create: `src/main/java/com/github/claudecodegui/model/selection/ModelSelectionRequest.java`
- Create: `src/main/java/com/github/claudecodegui/model/selection/ModelSelectionResult.java`
- Create: `src/main/java/com/github/claudecodegui/model/selection/ModelSelectorViewModel.java`
- Create: `src/main/java/com/github/claudecodegui/model/selection/ModelCapabilityResolver.java`
- Create: `src/main/java/com/github/claudecodegui/model/selection/DefaultModelCapabilityResolver.java`
- Modify: `src/main/java/com/github/claudecodegui/handler/provider/ModelProviderHandler.java`
- Test: `src/test/java/com/github/claudecodegui/model/selection/DefaultModelCapabilityResolverTest.java`
- Test: update `src/test/java/com/github/claudecodegui/handler/provider/ModelProviderHandlerTest.java`

### Webview Protocol Consumption

- Regenerate: `webview/src/generated/protocol.ts`
- Create: `webview/src/bridge/typed.ts`
- Modify: `webview/src/utils/modelRegistry.ts`
- Modify: `webview/src/components/settings/ModelRegistrySection/index.tsx`
- Modify: `webview/src/hooks/useModelProviderState.ts`
- Test: `webview/src/bridge/__tests__/typed.test.ts`
- Test: update `webview/src/components/settings/ModelRegistrySection/index.test.tsx`
- Test: update `webview/src/hooks/useModelProviderState.test.ts`

### Node Provider Command Registry

- Create: `ai-bridge/channels/provider-registry.js`
- Modify: `ai-bridge/channels/claude-channel.js`
- Modify: `ai-bridge/channels/codex-channel.js`
- Modify: `ai-bridge/channel-manager.js`
- Modify: `ai-bridge/daemon.js`
- Test: `ai-bridge/channels/provider-registry.test.js`

---

## Chunk 1: Protocol Coverage And Typed Dispatcher Foundation

### Task 1: Add Protocol Value Interface And Missing Protocol Constants

**Files:**
- Create: `src/main/java/com/github/claudecodegui/protocol/ProtocolValue.java`
- Modify: `src/main/java/com/github/claudecodegui/protocol/UpstreamAction.java`
- Modify: `src/main/java/com/github/claudecodegui/protocol/DownstreamEvent.java`
- Test: `src/test/java/com/github/claudecodegui/protocol/ProtocolEnumCoverageTest.java`

- [ ] **Step 1: Write the failing coverage test**

Create `ProtocolEnumCoverageTest`:

```java
package com.github.claudecodegui.protocol;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ProtocolEnumCoverageTest {
    @Test
    public void upstreamContainsActionsUsedBySettingsAndFrontend() {
        assertEquals("set_appearance_config", UpstreamAction.SET_APPEARANCE_CONFIG.value());
        assertEquals("get_model_registry", UpstreamAction.GET_MODEL_REGISTRY.value());
        assertEquals("set_model_registry", UpstreamAction.SET_MODEL_REGISTRY.value());
        assertEquals("reset_model_registry", UpstreamAction.RESET_MODEL_REGISTRY.value());
        assertEquals("get_model_registry_schema", UpstreamAction.GET_MODEL_REGISTRY_SCHEMA.value());
    }

    @Test
    public void downstreamContainsEventsUsedBySettingsAndFrontend() {
        assertEquals("appearance.apply", DownstreamEvent.APPEARANCE_APPLY.value());
        assertEquals("model_registry", DownstreamEvent.MODEL_REGISTRY.value());
        assertEquals("model_registry_updated", DownstreamEvent.MODEL_REGISTRY_UPDATED.value());
        assertEquals("model_registry_schema", DownstreamEvent.MODEL_REGISTRY_SCHEMA.value());
    }

    @Test
    public void protocolValuesAreUniqueWithinEachDirection() {
        assertUnique(Arrays.stream(UpstreamAction.values()).map(UpstreamAction::value).collect(Collectors.toList()));
        assertUnique(Arrays.stream(DownstreamEvent.values()).map(DownstreamEvent::value).collect(Collectors.toList()));
    }

    private static void assertUnique(List<String> values) {
        assertTrue("duplicate values: " + values, new HashSet<>(values).size() == values.size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk .\gradlew.bat test --tests com.github.claudecodegui.protocol.ProtocolEnumCoverageTest`

Expected: FAIL because model registry constants do not exist.

- [ ] **Step 3: Implement minimal protocol additions**

Create `ProtocolValue`:

```java
package com.github.claudecodegui.protocol;

public interface ProtocolValue {
    String value();
}
```

Update `UpstreamAction` and `DownstreamEvent` to implement `ProtocolValue` and add:

```java
GET_MODEL_REGISTRY("get_model_registry"),
SET_MODEL_REGISTRY("set_model_registry"),
RESET_MODEL_REGISTRY("reset_model_registry"),
GET_MODEL_REGISTRY_SCHEMA("get_model_registry_schema"),
```

```java
MODEL_REGISTRY("model_registry"),
MODEL_REGISTRY_UPDATED("model_registry_updated"),
MODEL_REGISTRY_SCHEMA("model_registry_schema"),
```

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk .\gradlew.bat test --tests com.github.claudecodegui.protocol.ProtocolEnumCoverageTest`

Expected: PASS.

- [ ] **Step 5: Regenerate protocol types**

Run: `rtk .\gradlew.bat generateProtocol`

Expected: `webview/src/generated/protocol.ts` includes new constants.

- [ ] **Step 6: Commit**

```bash
rtk git add src/main/java/com/github/claudecodegui/protocol src/test/java/com/github/claudecodegui/protocol webview/src/generated/protocol.ts
rtk git commit -m "refactor: complete typed protocol constants"
```

### Task 2: Add Typed Frontend Action Dispatcher

**Files:**
- Create: `src/main/java/com/github/claudecodegui/handler/core/FrontendActionHandler.java`
- Create: `src/main/java/com/github/claudecodegui/handler/core/FrontendActionContext.java`
- Create: `src/main/java/com/github/claudecodegui/handler/core/FrontendActionDispatcher.java`
- Test: `src/test/java/com/github/claudecodegui/handler/core/FrontendActionDispatcherTest.java`

- [ ] **Step 1: Write the failing dispatcher tests**

Cover dispatch, duplicate registration, and unknown action:

```java
@Test
public void dispatchesRegisteredAction() {
    AtomicReference<String> seen = new AtomicReference<>();
    FrontendActionHandler<String> handler = new StringActionHandler(UpstreamAction.SET_MODE, seen);
    FrontendActionDispatcher dispatcher = new FrontendActionDispatcher(List.of(handler), null);

    assertTrue(dispatcher.dispatch("set_mode", "plan"));
    assertEquals("plan", seen.get());
}

@Test(expected = IllegalArgumentException.class)
public void rejectsDuplicateActionHandlers() {
    new FrontendActionDispatcher(List.of(
        new NoopStringActionHandler(UpstreamAction.SET_MODE),
        new NoopStringActionHandler(UpstreamAction.SET_MODE)
    ), null);
}

@Test
public void returnsFalseForUnknownAction() {
    FrontendActionDispatcher dispatcher = new FrontendActionDispatcher(List.of(), null);
    assertFalse(dispatcher.dispatch("missing", "{}"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk .\gradlew.bat test --tests com.github.claudecodegui.handler.core.FrontendActionDispatcherTest`

Expected: FAIL because dispatcher classes do not exist.

- [ ] **Step 3: Implement dispatcher classes**

`FrontendActionHandler<T>`:

```java
public interface FrontendActionHandler<T> {
    UpstreamAction action();
    Class<T> payloadType();
    void handle(T payload, FrontendActionContext context);
}
```

`FrontendActionDispatcher` requirements:

- constructor accepts `List<FrontendActionHandler<?>>` and `HandlerContext`.
- stores handlers in `Map<String, FrontendActionHandler<?>>`.
- rejects duplicate `action().value()`.
- `dispatch(String type, String content)` returns false for unknown type.
- `String.class` payload gets raw content.
- other payloads parse using `GsonHolder.GSON`.

- [ ] **Step 4: Run tests**

Run: `rtk .\gradlew.bat test --tests com.github.claudecodegui.handler.core.FrontendActionDispatcherTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add src/main/java/com/github/claudecodegui/handler/core src/test/java/com/github/claudecodegui/handler/core/FrontendActionDispatcherTest.java
rtk git commit -m "refactor: add typed frontend action dispatcher"
```

### Task 3: Add Legacy Adapter For Existing MessageHandler Implementations

**Files:**
- Create: `src/main/java/com/github/claudecodegui/handler/core/LegacyMessageHandlerAdapter.java`
- Modify: `src/main/java/com/github/claudecodegui/protocol/UpstreamAction.java`
- Test: update `src/test/java/com/github/claudecodegui/handler/core/FrontendActionDispatcherTest.java`

- [ ] **Step 1: Write failing adapter tests**

Test that a fake `MessageHandler` with `getSupportedTypes() = {"set_mode"}` is converted to a typed handler that forwards raw content.

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk .\gradlew.bat test --tests com.github.claudecodegui.handler.core.FrontendActionDispatcherTest`

Expected: FAIL because adapter does not exist.

- [ ] **Step 3: Add `UpstreamAction.fromValue`**

Add:

```java
public static Optional<UpstreamAction> fromValue(String value) {
    return Arrays.stream(values()).filter(action -> action.value.equals(value)).findFirst();
}
```

- [ ] **Step 4: Implement legacy adapter**

`LegacyMessageHandlerAdapter.from(MessageHandler legacyHandler)` returns one `FrontendActionHandler<String>` per supported upstream action. Its `handle` delegates to `legacyHandler.handle(action.value(), payload)`.

- [ ] **Step 5: Run tests**

Run: `rtk .\gradlew.bat test --tests com.github.claudecodegui.handler.core.FrontendActionDispatcherTest`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
rtk git add src/main/java/com/github/claudecodegui/handler/core src/main/java/com/github/claudecodegui/protocol/UpstreamAction.java src/test/java/com/github/claudecodegui/handler/core/FrontendActionDispatcherTest.java
rtk git commit -m "refactor: bridge legacy handlers into typed dispatcher"
```

---

## Chunk 2: Provider Adapter Registry Foundation

### Task 4: Add ProviderId, ProviderAdapter, And ProviderRegistry

**Files:**
- Create: `src/main/java/com/github/claudecodegui/provider/ProviderId.java`
- Create: `src/main/java/com/github/claudecodegui/provider/ProviderAdapter.java`
- Create: `src/main/java/com/github/claudecodegui/provider/ProviderRegistry.java`
- Create: `src/main/java/com/github/claudecodegui/provider/ProviderContext.java`
- Create: `src/main/java/com/github/claudecodegui/provider/ProviderViewModel.java`
- Test: `src/test/java/com/github/claudecodegui/provider/ProviderRegistryTest.java`

- [ ] **Step 1: Write failing registry tests**

Cover adapter lookup, unknown provider, and duplicate provider IDs.

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk .\gradlew.bat test --tests com.github.claudecodegui.provider.ProviderRegistryTest`

Expected: FAIL because registry classes do not exist.

- [ ] **Step 3: Implement minimal provider registry**

`ProviderId` is a record with normalized values and constants:

```java
public record ProviderId(String value) {
    public static final ProviderId CLAUDE = new ProviderId(CommonConstants.PROVIDER_CLAUDE);
    public static final ProviderId CODEX = new ProviderId(CommonConstants.PROVIDER_CODEX);
    public static ProviderId of(String value) { return new ProviderId(value == null ? "" : value.trim().toLowerCase(Locale.ROOT)); }
}
```

`ProviderRegistry.require(ProviderId)` returns the matching adapter or throws `IllegalArgumentException`.

- [ ] **Step 4: Run registry tests**

Run: `rtk .\gradlew.bat test --tests com.github.claudecodegui.provider.ProviderRegistryTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add src/main/java/com/github/claudecodegui/provider src/test/java/com/github/claudecodegui/provider/ProviderRegistryTest.java
rtk git commit -m "refactor: add provider adapter registry"
```

### Task 5: Register Claude And Codex Provider Adapters

**Files:**
- Create: `src/main/java/com/github/claudecodegui/provider/claude/ClaudeProviderAdapter.java`
- Create: `src/main/java/com/github/claudecodegui/provider/codex/CodexProviderAdapter.java`
- Modify: `src/main/java/com/github/claudecodegui/provider/ProviderAdapter.java`
- Test: update `src/test/java/com/github/claudecodegui/provider/ProviderRegistryTest.java`

- [ ] **Step 1: Write failing adapter tests**

Assert:

- `ClaudeProviderAdapter.providerId()` equals `ProviderId.CLAUDE`.
- `CodexProviderAdapter.providerId()` equals `ProviderId.CODEX`.
- both expose minimal `ProviderViewModel`.

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk .\gradlew.bat test --tests com.github.claudecodegui.provider.ProviderRegistryTest`

Expected: FAIL because adapter classes do not exist.

- [ ] **Step 3: Implement thin wrappers**

Keep the adapters thin. At this point they identify providers and expose view model metadata only.

- [ ] **Step 4: Run tests**

Run: `rtk .\gradlew.bat test --tests com.github.claudecodegui.provider.ProviderRegistryTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add src/main/java/com/github/claudecodegui/provider src/test/java/com/github/claudecodegui/provider/ProviderRegistryTest.java
rtk git commit -m "refactor: register claude and codex provider adapters"
```

### Task 6: Route SessionProviderRouter Through ProviderRegistry

**Files:**
- Modify: `src/main/java/com/github/claudecodegui/provider/ProviderAdapter.java`
- Modify: `src/main/java/com/github/claudecodegui/provider/claude/ClaudeProviderAdapter.java`
- Modify: `src/main/java/com/github/claudecodegui/provider/codex/CodexProviderAdapter.java`
- Modify: `src/main/java/com/github/claudecodegui/session/SessionProviderRouter.java`
- Test: `src/test/java/com/github/claudecodegui/session/SessionProviderRouterProviderRegistryTest.java`

- [ ] **Step 1: Write failing router delegation tests**

Use fake adapters to verify `launchChannel`, `interruptChannel`, `cleanupProviderSession`, and `getSessionMessages` delegate to the matching adapter.

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk .\gradlew.bat test --tests com.github.claudecodegui.session.SessionProviderRouterProviderRegistryTest`

Expected: FAIL because router does not accept/use registry yet.

- [ ] **Step 3: Extend `ProviderAdapter` routing methods**

Add:

```java
JsonObject launchChannel(String channelId, String sessionId, String cwd);
void interruptChannel(String channelId);
void cleanupProviderSession(String sessionId, String cwd);
List<JsonObject> getSessionMessages(String sessionId, String cwd);
```

- [ ] **Step 4: Keep constructor compatibility**

Keep `SessionProviderRouter(ClaudeSDKBridge, CodexSDKBridge)` and add `SessionProviderRouter(ProviderRegistry)`.

- [ ] **Step 5: Run router and smoke tests**

Run:

```bash
rtk .\gradlew.bat test --tests com.github.claudecodegui.session.SessionProviderRouterProviderRegistryTest
rtk .\gradlew.bat test --tests com.github.claudecodegui.session.SessionSendServiceTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
rtk git add src/main/java/com/github/claudecodegui/provider src/main/java/com/github/claudecodegui/session/SessionProviderRouter.java src/test/java/com/github/claudecodegui/session/SessionProviderRouterProviderRegistryTest.java
rtk git commit -m "refactor: route sessions through provider registry"
```

---

## Chunk 3: Backend-Owned Model Selection

### Task 7: Add Model Selection Resolver

**Files:**
- Create: `src/main/java/com/github/claudecodegui/model/selection/ModelSelectionRequest.java`
- Create: `src/main/java/com/github/claudecodegui/model/selection/ModelSelectionResult.java`
- Create: `src/main/java/com/github/claudecodegui/model/selection/ModelSelectorViewModel.java`
- Create: `src/main/java/com/github/claudecodegui/model/selection/ModelCapabilityResolver.java`
- Create: `src/main/java/com/github/claudecodegui/model/selection/DefaultModelCapabilityResolver.java`
- Test: `src/test/java/com/github/claudecodegui/model/selection/DefaultModelCapabilityResolverTest.java`

- [ ] **Step 1: Write failing resolver tests**

Cover:

- Claude model with 1M requested context stores `[1m]` when the selected model is Claude-family.
- Non-Claude compatible model does not receive `[1m]`.
- registry context window caps max tokens.
- missing requested context falls back to registry or 200k.

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk .\gradlew.bat test --tests com.github.claudecodegui.model.selection.DefaultModelCapabilityResolverTest`

Expected: FAIL because resolver classes do not exist.

- [ ] **Step 3: Implement DTOs and resolver**

Use records:

```java
public record ModelSelectionRequest(String provider, String selectedModel, Integer requestedContextWindow, boolean longContextEnabled) {}
public record ModelSelectionResult(String provider, String selectedModel, String storedModel, String resolvedActualModel, int effectiveContextWindow, int maxTokens, boolean supportsLongContext) {}
```

Move behavior behind `DefaultModelCapabilityResolver` by reusing `ModelRegistryConfig`, `ClaudeRole`, and existing model context limit logic.

- [ ] **Step 4: Run resolver tests**

Run: `rtk .\gradlew.bat test --tests com.github.claudecodegui.model.selection.DefaultModelCapabilityResolverTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add src/main/java/com/github/claudecodegui/model/selection src/test/java/com/github/claudecodegui/model/selection/DefaultModelCapabilityResolverTest.java
rtk git commit -m "refactor: add backend model selection resolver"
```

### Task 8: Emit Backend Model Selection Result From ModelProviderHandler

**Files:**
- Modify: `src/main/java/com/github/claudecodegui/handler/provider/ModelProviderHandler.java`
- Modify: `src/main/java/com/github/claudecodegui/protocol/DownstreamEvent.java`
- Test: update `src/test/java/com/github/claudecodegui/handler/provider/ModelProviderHandlerTest.java`

- [ ] **Step 1: Write failing handler tests**

Assert `handleSetSessionModel` still emits `model.confirmed` and now emits `model.selection` with effective context-window details.

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk .\gradlew.bat test --tests com.github.claudecodegui.handler.provider.ModelProviderHandlerTest`

Expected: FAIL because `model.selection` is not emitted.

- [ ] **Step 3: Add downstream event**

Add:

```java
MODEL_SELECTION("model.selection")
```

- [ ] **Step 4: Use `DefaultModelCapabilityResolver` in `ModelProviderHandler`**

Replace scattered local calculation with `ModelSelectionResult`. Preserve:

- session model update.
- current model update.
- notifier update.
- usage push behavior.
- existing `model.confirmed` event for compatibility.

- [ ] **Step 5: Dispatch model selection event**

Payload shape:

```json
{
  "provider": "claude",
  "selectedModel": "claude-role-sonnet",
  "storedModel": "claude-role-sonnet[1m]",
  "resolvedActualModel": "mimo-v2.5-pro",
  "effectiveContextWindow": 1000000,
  "maxTokens": 1000000,
  "supportsLongContext": true
}
```

- [ ] **Step 6: Run handler tests**

Run: `rtk .\gradlew.bat test --tests com.github.claudecodegui.handler.provider.ModelProviderHandlerTest`

Expected: PASS.

- [ ] **Step 7: Regenerate protocol types**

Run: `rtk .\gradlew.bat generateProtocol`

Expected: `webview/src/generated/protocol.ts` includes `MODEL_SELECTION`.

- [ ] **Step 8: Commit**

```bash
rtk git add src/main/java/com/github/claudecodegui/handler/provider/ModelProviderHandler.java src/main/java/com/github/claudecodegui/protocol/DownstreamEvent.java webview/src/generated/protocol.ts src/test/java/com/github/claudecodegui/handler/provider/ModelProviderHandlerTest.java
rtk git commit -m "refactor: emit backend model selection result"
```

---

## Chunk 4: Webview Typed Protocol Consumption

### Task 9: Add Typed Bridge Wrapper And Replace Model Registry Strings

**Files:**
- Create: `webview/src/bridge/typed.ts`
- Modify: `webview/src/utils/modelRegistry.ts`
- Modify: `webview/src/components/settings/ModelRegistrySection/index.tsx`
- Test: `webview/src/bridge/__tests__/typed.test.ts`
- Test: update `webview/src/components/settings/ModelRegistrySection/index.test.tsx`

- [ ] **Step 1: Write failing typed bridge test**

Assert:

```ts
sendAction(UPSTREAM.GET_MODEL_REGISTRY);
expect(window.sendToJava).toHaveBeenCalledWith(JSON.stringify({ type: 'get_model_registry', content: '' }));
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd webview && npm test -- --run src/bridge/__tests__/typed.test.ts`

Expected: FAIL because `typed.ts` does not exist.

- [ ] **Step 3: Implement `typed.ts`**

Expose:

```ts
export function sendAction(action: UpstreamAction, payload: unknown = ''): boolean
export function subscribeEvent<T = unknown>(event: DownstreamEvent, listener: (payload: T) => void): Unsubscribe
```

Preserve raw payload behavior by default; do not force JSON parsing globally in this milestone.

- [ ] **Step 4: Replace model registry strings**

Use `UPSTREAM.*` and `DOWNSTREAM.*` constants in `modelRegistry.ts` and `ModelRegistrySection`.

- [ ] **Step 5: Run targeted frontend tests**

Run:

```bash
cd webview
npm test -- --run src/bridge/__tests__/typed.test.ts src/components/settings/ModelRegistrySection/index.test.tsx
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
rtk git add webview/src/bridge/typed.ts webview/src/utils/modelRegistry.ts webview/src/components/settings/ModelRegistrySection/index.tsx webview/src/bridge/__tests__/typed.test.ts webview/src/components/settings/ModelRegistrySection/index.test.tsx
rtk git commit -m "refactor: use typed protocol for model registry"
```

### Task 10: Consume Backend Model Selection Event In Provider State Hook

**Files:**
- Modify: `webview/src/hooks/useModelProviderState.ts`
- Test: update `webview/src/hooks/useModelProviderState.test.ts`

- [ ] **Step 1: Write failing hook test**

Simulate `DOWNSTREAM.MODEL_SELECTION` with:

```json
{"provider":"claude","selectedModel":"mimo-v2.5-pro","effectiveContextWindow":1000000,"supportsLongContext":true}
```

Assert selected model and display state update from backend payload.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd webview && npm test -- --run src/hooks/useModelProviderState.test.ts`

Expected: FAIL because hook does not subscribe to `model.selection`.

- [ ] **Step 3: Subscribe to backend model selection**

Use generated `DOWNSTREAM.MODEL_SELECTION`. Apply only display state; do not treat frontend-calculated context window as authoritative after backend confirmation.

- [ ] **Step 4: Run hook tests**

Run: `cd webview && npm test -- --run src/hooks/useModelProviderState.test.ts`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add webview/src/hooks/useModelProviderState.ts webview/src/hooks/useModelProviderState.test.ts
rtk git commit -m "refactor: consume backend model selection state"
```

---

## Chunk 5: Node Provider Command Registry

### Task 11: Add ai-bridge Provider Registry

**Files:**
- Create: `ai-bridge/channels/provider-registry.js`
- Modify: `ai-bridge/channels/claude-channel.js`
- Modify: `ai-bridge/channels/codex-channel.js`
- Test: `ai-bridge/channels/provider-registry.test.js`

- [ ] **Step 1: Write failing Node registry test**

Use `node:assert/strict` and direct ESM imports. Test known provider lookup and duplicate rejection.

- [ ] **Step 2: Run test to verify it fails**

Run: `node ai-bridge/channels/provider-registry.test.js`

Expected: FAIL because registry does not exist.

- [ ] **Step 3: Implement registry and descriptors**

`provider-registry.js` exports `createProviderRegistry` and `getDefaultProviderRegistry`. Claude/Codex channels export descriptors:

```js
export const claudeChannelDescriptor = {
  provider: 'claude',
  commands: getClaudeCommandList(),
  handle: handleClaudeCommand,
};
```

- [ ] **Step 4: Run registry test**

Run: `node ai-bridge/channels/provider-registry.test.js`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add ai-bridge/channels/provider-registry.js ai-bridge/channels/provider-registry.test.js ai-bridge/channels/claude-channel.js ai-bridge/channels/codex-channel.js
rtk git commit -m "refactor: add ai bridge provider registry"
```

### Task 12: Use ai-bridge Registry In Dispatchers

**Files:**
- Modify: `ai-bridge/channel-manager.js`
- Modify: `ai-bridge/daemon.js`
- Test: update `ai-bridge/channels/provider-registry.test.js`

- [ ] **Step 1: Add failing dispatch-helper tests**

Cover unknown provider, unsupported command, and successful command delegation.

- [ ] **Step 2: Run test to verify it fails**

Run: `node ai-bridge/channels/provider-registry.test.js`

Expected: FAIL until dispatch helper exists.

- [ ] **Step 3: Add `registry.dispatch(provider, command, args, stdinData)`**

The helper validates provider and command before calling descriptor `handle`.

- [ ] **Step 4: Replace `providerHandlers` in `channel-manager.js`**

Keep CLI behavior and error text compatible.

- [ ] **Step 5: Replace generic switch in `daemon.js`**

Keep Claude persistent special cases unchanged. Use registry only for generic provider commands.

- [ ] **Step 6: Run Node tests**

Run:

```bash
node ai-bridge/channels/provider-registry.test.js
node ai-bridge/permission-ipc.test.js
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
rtk git add ai-bridge/channel-manager.js ai-bridge/daemon.js ai-bridge/channels/provider-registry.js ai-bridge/channels/provider-registry.test.js
rtk git commit -m "refactor: dispatch ai bridge commands through registry"
```

---

## Chunk 6: Verification And Documentation

### Task 13: Run Milestone Verification

**Files:**
- No source edits unless verification finds issues.

- [ ] **Step 1: Run targeted Java tests**

Run:

```bash
rtk .\gradlew.bat test --tests com.github.claudecodegui.protocol.ProtocolEnumCoverageTest --tests com.github.claudecodegui.handler.core.FrontendActionDispatcherTest --tests com.github.claudecodegui.provider.ProviderRegistryTest --tests com.github.claudecodegui.session.SessionProviderRouterProviderRegistryTest --tests com.github.claudecodegui.model.selection.DefaultModelCapabilityResolverTest --tests com.github.claudecodegui.handler.provider.ModelProviderHandlerTest
```

Expected: PASS.

- [ ] **Step 2: Run targeted frontend tests**

Run:

```bash
cd webview
npm test -- --run src/bridge/__tests__/typed.test.ts src/components/settings/ModelRegistrySection/index.test.tsx src/hooks/useModelProviderState.test.ts
```

Expected: PASS.

- [ ] **Step 3: Run targeted Node tests**

Run:

```bash
node ai-bridge/channels/provider-registry.test.js
node ai-bridge/permission-ipc.test.js
```

Expected: PASS.

- [ ] **Step 4: Run broader smoke checks**

Run:

```bash
rtk .\gradlew.bat test
cd webview
npm test
```

Expected: PASS. If unrelated existing failures appear, record exact failing tests and keep targeted evidence.

### Task 14: Update Architecture Notes

**Files:**
- Modify: `webview/src/ARCHITECTURE.md`
- Create or modify: `docs/designs/plugin-architecture-refactor-status.md`

- [ ] **Step 1: Document milestone state**

Include:

- typed protocol coverage rules.
- `FrontendActionDispatcher`.
- `ProviderAdapter` registry.
- backend-owned model selection result.
- remaining legacy paths.

- [ ] **Step 2: Review docs diff**

Run: `rtk git diff -- webview/src/ARCHITECTURE.md docs/designs/plugin-architecture-refactor-status.md`

Expected: only documentation updates.

- [ ] **Step 3: Commit docs**

```bash
rtk git add webview/src/ARCHITECTURE.md docs/designs/plugin-architecture-refactor-status.md
rtk git commit -m "docs: record architecture refactor milestone"
```

---

## Final Acceptance Checklist

- [ ] Java protocol enums include all model registry and appearance actions/events used in this milestone.
- [ ] Generated `webview/src/generated/protocol.ts` is in sync with Java protocol enums.
- [ ] `FrontendActionDispatcher` supports typed action registration and duplicate detection.
- [ ] Legacy message handlers can be adapted without changing all handlers at once.
- [ ] `ProviderRegistry` resolves Claude/Codex and rejects unknown/duplicate providers.
- [ ] `SessionProviderRouter` delegates through the registry while preserving old constructor compatibility.
- [ ] Backend emits a typed model selection result with effective context window.
- [ ] Webview model registry code uses generated protocol constants.
- [ ] Webview consumes backend model selection state.
- [ ] ai-bridge generic provider command dispatch uses registry descriptors.
- [ ] Targeted Java, frontend, and Node tests pass with fresh output.

## Implementation Notes

- Use TDD for each task: failing test first, minimal implementation, passing test, commit.
- Do not remove legacy callback paths in this milestone unless a task explicitly says so.
- Do not revert existing unrelated user changes in the working tree.
- If a touched file already has unrelated modifications, inspect carefully and preserve them.
- Keep commits small and aligned with task boundaries.
