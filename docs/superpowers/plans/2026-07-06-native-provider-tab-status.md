# Native Provider Tab Status Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace colored status dots in tool-window tabs with a native-looking provider icon plus weak status text.

**Architecture:** Keep status presentation fully backend-side in Java. Extract pure tab status presentation helpers from `ChatWindowDelegate` so text, colors, and provider icon behavior are testable without JCEF. `ChatWindowDelegate` only reads session provider/status and applies the prepared icon/display name to IntelliJ `Content`.

**Tech Stack:** Java, Swing `Icon`, IntelliJ Platform `Content`, JUnit 4.

## Global Constraints

- Use `ProviderType.fromString` for provider routing; do not hardcode provider protocol strings.
- Status display text is `排队中`, `运行中`, `已完成`; idle hides status text.
- Status colors are soft native colors: queued `#E1B56F`, running `#8FBFFF`, completed `#94D9A8`.
- Do not touch frontend protocol or webview code.

---

### Task 1: Extract tab presentation helper

**Files:**
- Create: `src/main/java/com/github/claudecodegui/ui/TabStatusPresentation.java`
- Test: `src/test/java/com/github/claudecodegui/ui/TabStatusPresentationTest.java`

**Interfaces:**
- Consumes: `ChatWindowDelegate.TabAnswerStatus`, `ProviderType.fromString(String)`.
- Produces: `displayName(String, TabAnswerStatus)`, `stripStatusText(String)`, `createProviderIcon(String, TabAnswerStatus)`.

- [ ] Add helper with status text/color mapping and provider icon.
- [ ] Add JUnit tests for display names, suffix stripping, and icon dimensions.

### Task 2: Wire helper into ChatWindowDelegate

**Files:**
- Modify: `src/main/java/com/github/claudecodegui/ui/ChatWindowDelegate.java`

**Interfaces:**
- Consumes: helper methods from Task 1.
- Produces: tool-window tab display names and icons matching the approved design.

- [ ] Replace status dot switch with `TabStatusPresentation`.
- [ ] Track provider changes so idle tabs can still render provider icons.
- [ ] Preserve external rename detection by stripping known status suffixes before saving original tab name.

### Task 3: Validate

**Files:**
- Test: `src/test/java/com/github/claudecodegui/ui/TabStatusPresentationTest.java`

- [ ] Run focused tests with `gradlew test --tests com.github.claudecodegui.ui.TabStatusPresentationTest`.
- [ ] Run `gradlew test` if focused validation passes and time permits.
