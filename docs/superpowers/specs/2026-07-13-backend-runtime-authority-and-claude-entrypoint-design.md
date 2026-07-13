# Backend Runtime Authority and Claude Entrypoint Normalization Design

## 1. Background

The plugin supports Claude, Codex, and OpenCode through SDK and CLI runtimes. The current send path can receive an invocation mode from the webview, retain a session-level mode snapshot, and combine those values with backend configuration. This creates multiple authorities for the same routing decision and conflicts with the project rule that business decisions must remain in the backend.

Claude CLI sessions expose an additional symptom. The plugin correctly launches `claude -p --output-format stream-json` when CLI mode is selected, but Claude Code classifies non-interactive print-mode processes as `sdk-cli`. Claude Code rewrites `CLAUDE_CODE_ENTRYPOINT=cli` to `sdk-cli`, so setting the environment variable does not produce a CLI entrypoint in the persisted JSONL history.

## 2. Goals

1. Make backend runtime configuration the only authority for SDK/CLI routing.
2. Apply configuration changes to the next message of every existing session.
3. Remove request-level and session-level invocation mode inputs from routing.
4. Keep Claude, Codex, and OpenCode behavior symmetric.
5. Normalize newly written Claude CLI history records from `sdk-cli` to `cli` after the CLI process exits.
6. Preserve existing SDK/CLI streaming, cancellation, resume, and process lifecycle behavior.

## 3. Non-goals

- Do not bulk-convert historical Claude sessions.
- Do not convert genuine Claude SDK sessions.
- Do not replace the Claude print-mode process with a PTY or interactive terminal.
- Do not move routing decisions into the webview.
- Do not redesign provider registration or runtime adapter assembly.

## 4. Runtime Configuration Authority

### 4.1 Source of truth

`CodemossSettingsService.getRuntimePolicy()` is the runtime-routing source of truth. For each send, the backend resolves the active provider and reads that provider's latest `ProviderRuntimePolicy.defaultRuntime()`.

The effective flow is:

```text
provider
  -> CodemossSettingsService.getRuntimePolicy()
  -> merge missing providers with backend defaults
  -> ProviderRuntimePolicy
  -> defaultRuntime
  -> SessionRuntimeRegistry
  -> SDK or CLI runtime
```

The runtime policy validator remains responsible for ensuring that the provider is enabled, the default runtime is present, and the default runtime belongs to the supported runtime set.

### 4.2 Immediate application

Runtime mode is resolved for every send operation. Changing a provider from SDK to CLI, or CLI to SDK, affects the next message in all open sessions. Existing sessions do not retain a runtime snapshot.

### 4.3 Legacy Claude invocation setting

The legacy Claude-only `claudeInvocationMode` setting must not remain an independent routing authority. Its setting action must write or migrate to `runtime.providers.claude.default`, or be removed when all consumers use the unified runtime policy. Reading and displaying runtime mode must use the unified backend policy.

There must not be two backend fields whose conflicting values require precedence rules.

## 5. Protocol and Webview Responsibilities

The webview may submit runtime policy changes through the settings action. That is configuration input, not a per-message routing decision.

Normal chat operations must not send invocation mode fields. This includes:

- regular messages;
- messages with attachments;
- retry or regenerate operations;
- continue/resume operations;
- restored tab state.

The webview may display the configured mode and the actual mode reported by the backend, but it must not calculate or select the runtime for an individual message.

Generated protocol constants remain the only source for message names. Removing payload fields must update relevant Java request types, frontend TypeScript types, tests, and compatibility parsing together.

## 6. Backend State Cleanup

Remove invocation mode from the request parameter chain and from persistent session state where it is used as a routing snapshot. Targeted cleanup includes:

- request-level `requestedInvocationMode` parameters;
- `SessionState.claudeInvocationMode` runtime snapshot semantics;
- tab/session persistence of `claudeInvocationMode`;
- session initialization that copies settings into a runtime snapshot;
- session-mode query paths that return the stale snapshot;
- resolver precedence involving request mode or session mode.

If a legacy payload still contains an invocation mode during compatibility rollout, the backend ignores it. It must not influence routing.

The effective runtime used for a send should be retained only as an immutable local result for that send, so logging, diagnostics, and Claude history normalization describe the runtime that actually executed.

## 7. Resolver Behavior

`EffectiveRuntimeResolver` becomes configuration-driven:

1. Convert the provider identifier through `ProviderType`.
2. Load the latest merged runtime policy from the backend settings service.
3. Reject disabled or unknown providers with an explicit backend error.
4. Select `defaultRuntime()` from the provider policy.
5. Route through the existing provider/runtime registry.

It no longer accepts request or session invocation mode values as authorities. It must not silently trust a frontend mode or a restored tab snapshot.

## 8. Claude CLI Entrypoint Normalization

### 8.1 Trigger

Normalization runs only after a Claude CLI child process has fully exited and stdout processing has completed. It uses the runtime selected by the backend for that send as the trusted condition.

The SDK path never triggers normalization.

### 8.2 Reusable component

Extract a UI-independent Claude session entrypoint rewriter. Both automatic CLI normalization and the existing manual history conversion feature use this component.

The component:

- validates the session identifier;
- resolves the correct Claude projects directory for native Windows or WSL;
- locates the current session JSONL using the session identifier and project path;
- rewrites only top-level `entrypoint: "sdk-cli"` fields to `entrypoint: "cli"`;
- leaves `cli`, `claude-vscode`, unknown entrypoints, malformed JSON lines, and unrelated fields unchanged for automatic normalization;
- performs an atomic replacement with appropriate locking and cleanup;
- is idempotent.

The manual conversion facade may continue to support its existing explicitly convertible entrypoints, but automatic CLI normalization is restricted to `sdk-cli`.

### 8.3 Failure semantics

Entrypoint normalization is metadata maintenance. Failure is logged with the session ID and path context but does not turn an otherwise successful model response into a failed send.

No historical directory scan is performed.

## 9. Provider Symmetry

All three providers follow the same routing rule:

| Path | Claude | Codex | OpenCode |
|---|---|---|---|
| Normal message | Latest backend policy | Latest backend policy | Latest backend policy |
| Attachment message | Latest backend policy | Latest backend policy | Latest backend policy |
| Retry/continue | Latest backend policy | Latest backend policy | Latest backend policy |
| Restored tab | No mode snapshot | No mode snapshot | No mode snapshot |
| Setting changed | Next send uses new mode | Next send uses new mode | Next send uses new mode |

Claude-specific JSONL normalization is an intentional provider-specific persistence requirement and does not alter the shared routing rule.

## 10. Testing

Backend tests must cover:

- each provider resolves from the latest backend runtime policy;
- changing policy affects the next send in an existing session;
- request/session mode values cannot override backend policy;
- missing provider policies are filled from backend defaults;
- disabled providers fail explicitly;
- restored session state does not restore a runtime mode snapshot;
- SDK and CLI runtime registry routing remains correct for all three providers;
- automatic Claude normalization rewrites `sdk-cli` to `cli`;
- already-CLI and unrelated entrypoints remain unchanged;
- malformed JSONL lines remain unchanged;
- missing files and rewrite failures do not fail a completed send;
- Claude SDK, Codex, and OpenCode paths do not trigger Claude history normalization.

Frontend tests must verify that chat payloads no longer include invocation mode fields and that settings UI continues to render backend-provided policy.

## 11. Compatibility and Migration

Existing config files are merged with `RuntimePolicyConfig` defaults. If a legacy Claude invocation setting exists and the unified Claude runtime policy has not been explicitly configured, migration may seed `runtime.providers.claude.default` once. After migration, only the unified runtime policy is read for routing.

Existing session/tab snapshots containing `claudeInvocationMode` may still deserialize during transition, but the value is ignored and is no longer written back.

## 12. Compliance

- Runtime selection is backend business logic; the webview only edits and displays configuration.
- Provider and runtime values use `ProviderType`, `RuntimeType`, and centralized constants rather than string literals.
- The existing `SessionRuntimeRegistry` remains the open/closed routing mechanism.
- Claude JSONL rewriting is implemented once and reused.
- The three providers are tested symmetrically across SDK and CLI paths.
