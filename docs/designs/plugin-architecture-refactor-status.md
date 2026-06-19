# Plugin Architecture Refactor Status

## Milestone Scope

This milestone implements the first maintainable refactor slice for the plugin
architecture:

- Java protocol enums are the single source of truth for upstream actions and
  downstream events.
- Java has a typed frontend action dispatcher foundation plus a legacy
  `MessageHandler` adapter.
- Java provider routing has `ProviderId`, `ProviderAdapter`, and
  `ProviderRegistry`.
- Session SDK routing delegates through provider adapters while preserving the
  existing `SessionProviderRouter(ClaudeSDKBridge, CodexSDKBridge)` constructor.
- Model selection results are resolved by backend `DefaultModelCapabilityResolver`
  and emitted as `model.selection`.
- Webview model-registry calls use generated protocol constants through
  `bridge/typed.ts`.
- Webview model-provider display state consumes backend `model.selection`.
- `ai-bridge` provider command routing has a descriptor registry and dispatch
  helper for generic Claude/Codex commands.

## Frontend Boundary

The frontend should only render state and collect user intent:

- Use `sendAction(UPSTREAM.*)` for migrated upstream messages.
- Use `subscribeEvent(DOWNSTREAM.*)` for migrated downstream events.
- Treat `model.selection` as backend-authoritative for display state.
- Do not add new business rules for model capability, max tokens, provider
  routing, or command support in React components.

Remaining frontend compatibility paths:

- Many chat/session/settings areas still call `sendBridgeEvent()` with raw
  strings.
- Many backend callbacks are still registered through legacy `window.xxx`
  compatibility.
- Local persistence in model/provider hooks remains and should be reduced in a
  later milestone once backend ViewModels cover the full bootstrapping state.

## Backend Boundary

Java now owns the first slice of business rules:

- `UpstreamAction` / `DownstreamEvent` define protocol values.
- `FrontendActionDispatcher` registers one handler per action and rejects
  duplicate actions.
- `LegacyMessageHandlerAdapter` allows existing handlers to be migrated without
  a flag day.
- `ProviderRegistry` rejects duplicate providers and resolves adapters by
  normalized `ProviderId`.
- `DefaultModelCapabilityResolver` computes stored model ID, resolved actual
  model, effective context window, max tokens, and 1M support.
- `ModelProviderHandler` emits legacy `model.confirmed` plus typed
  `model.selection` for frontend consumption.

Remaining backend compatibility paths:

- Most existing Java handlers still use `MessageHandler` and switch statements.
- Provider-specific command behavior is still partly inside handlers and bridge
  classes.
- `model.confirmed` remains for compatibility until all frontend consumers move
  to `model.selection`.

## Node Boundary

`ai-bridge` now has provider descriptors:

- `claudeChannelDescriptor`
- `codexChannelDescriptor`
- `createProviderRegistry()`
- `registry.dispatch(provider, command, args, stdinData)`

Remaining Node compatibility paths:

- Claude persistent daemon commands remain special-cased in `daemon.js`.
- System commands remain outside the provider registry.
- Channel command handlers still contain provider-local switch statements; this
  is acceptable because they are provider adapters, not cross-provider routing.

## Verification Snapshot

Passing targeted checks:

- `rtk .\gradlew.bat test --tests com.github.claudecodegui.protocol.ProtocolEnumCoverageTest --tests com.github.claudecodegui.handler.core.FrontendActionDispatcherTest --tests com.github.claudecodegui.provider.ProviderRegistryTest --tests com.github.claudecodegui.session.SessionProviderRouterProviderRegistryTest --tests com.github.claudecodegui.model.selection.DefaultModelCapabilityResolverTest --tests com.github.claudecodegui.handler.provider.ModelProviderHandlerTest`
- `cmd /c node_modules\.bin\vitest.cmd run src/bridge/__tests__/typed.test.ts src/components/settings/ModelRegistrySection/index.test.tsx src/hooks/useModelProviderState.test.ts`
- `node ai-bridge\channels\provider-registry.test.js`
- `node ai-bridge\permission-ipc.test.js`

Broader smoke status:

- `rtk .\gradlew.bat test` currently fails in existing broad-suite areas
  outside this milestone, including SDK bridge lifecycle/env/history tests,
  message parser/orchestrator tests, and slash-command path policy tests.
- `cmd /c npm test` currently fails in existing broad frontend tests, including
  scroll behavior, dialog management, button-area model mapping, message item
  groupBlocks export expectations, provider quota tests, and later test-type
  checking issues.

## Next Iteration Candidates

- Migrate `SettingsHandler` model-registry actions into typed
  `FrontendActionHandler` implementations.
- Replace raw `sendBridgeEvent()` calls in provider/model/session hooks with
  generated `UPSTREAM` constants.
- Move more model/provider bootstrapping state into backend ViewModels.
- Add typed error events for dispatcher payload parse failures.
- Split Node provider command switches into smaller command descriptors inside
  each provider adapter.
