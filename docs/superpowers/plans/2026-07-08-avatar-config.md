# Avatar Config Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add configurable assistant and user message avatars with backend-owned persistence outside the main `config.json`.

**Architecture:** Add a backend avatar service that stores selection metadata and custom base64 JSON files under `~/.codemoss/avatars`. Add typed protocol handlers for get, set, and upload actions. Add frontend avatar config state, settings UI, and message avatar rendering.

**Tech Stack:** Java IntelliJ plugin, Gson, React, TypeScript, JCEF typed bridge.

## Global Constraints

- Backend is the single source of truth for avatar defaults, validation, custom image persistence, and active selection.
- Webview uses generated `UPSTREAM` / `DOWNSTREAM` constants only.
- Provider identifiers use generated provider constants on the frontend and `ProviderType` on the backend.
- Custom image payloads are not stored in main `config.json`.
- Maximum custom image bytes is `1048576`.

---

### Task 1: Backend avatar storage and protocol

**Files:**
- Create: `src/main/java/com/github/claudecodegui/settings/avatar/AvatarConfigService.java`
- Create: `src/main/java/com/github/claudecodegui/settings/avatar/AvatarConfigResult.java`
- Create: `src/main/java/com/github/claudecodegui/handler/settings/GetAvatarConfigActionHandler.java`
- Create: `src/main/java/com/github/claudecodegui/handler/settings/SetAvatarConfigActionHandler.java`
- Create: `src/main/java/com/github/claudecodegui/handler/settings/UploadCustomAvatarActionHandler.java`
- Modify: `src/main/java/com/github/claudecodegui/protocol/UpstreamAction.java`
- Modify: `src/main/java/com/github/claudecodegui/protocol/DownstreamEvent.java`
- Modify: `src/main/java/com/github/claudecodegui/ui/ChatWindowDelegate.java`
- Modify: `src/main/java/com/github/claudecodegui/ui/WebviewInitializer.java`

**Interfaces:**
- Produces: `AvatarConfigService.getConfigJson()`, `applySelection(String)`, `uploadCustom(String, Project)`.
- Produces event: `DownstreamEvent.AVATAR_CONFIG_APPLY`.

- [ ] Add protocol enum constants.
- [ ] Implement service defaults, validation, disk persistence, custom image upload, and data URL hydration.
- [ ] Implement typed handlers and register them in `ChatWindowDelegate`.
- [ ] Inject avatar config during webview initialization.

### Task 2: Frontend avatar state and rendering

**Files:**
- Create: `webview/src/types/avatar.ts`
- Create: `webview/src/hooks/useAvatarConfig.ts`
- Modify: `webview/src/bootstrap/appearance.ts`
- Modify: `webview/src/components/MessageItem/MessageAvatar.tsx`
- Modify: `webview/src/components/MessageItem/MessageItem.tsx`
- Modify: `webview/src/components/MessageList.tsx`

**Interfaces:**
- Consumes: `DOWNSTREAM.AVATAR_CONFIG_APPLY`.
- Produces: `AvatarConfig`, `resolveMessageAvatar` rendering inputs.

- [ ] Add avatar TypeScript types.
- [ ] Subscribe to backend avatar config and request initial config.
- [ ] Render assistant provider, provider preset, default preset, and custom data URL avatars.
- [ ] Pass avatar config through message item/list render paths.

### Task 3: Appearance UI

**Files:**
- Modify: `webview/src/components/settings/BasicConfigSection/AppearanceTab.tsx`
- Modify: `webview/src/components/settings/BasicConfigSection/index.tsx`
- Modify: `webview/src/components/settings/BasicConfigSection/style.module.less`
- Modify: locale JSON files under `webview/src/locales/`

**Interfaces:**
- Consumes: `avatarConfig`, `onAvatarSelectionChange`, `onAvatarUpload` props.
- Sends: `UPSTREAM.AVATAR_SET_CONFIG`, `UPSTREAM.AVATAR_UPLOAD_CUSTOM`.

- [ ] Add assistant and user avatar option rows.
- [ ] Add upload buttons that call backend file chooser.
- [ ] Add styles and i18n keys.

### Task 4: Verification

**Files:**
- Modify or create targeted tests for avatar rendering and settings UI.

**Interfaces:**
- Verifies protocol generation, TypeScript build, and Gradle compile/test where practical.

- [ ] Run protocol generation.
- [ ] Run focused frontend tests.
- [ ] Run Java compile or focused Gradle tests.
