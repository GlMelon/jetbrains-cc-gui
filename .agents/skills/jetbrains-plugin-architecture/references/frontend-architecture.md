---
title: 前端架构
category: architecture
tags: 前端, webview, hooks
---

## 前端架构

### 目录结构

```
webview/src/
├── App.tsx                    # 主编排组件
├── hooks/
│   ├── index.ts               # Barrel exports
│   ├── useWindowCallbacks.ts  # Java bridge callbacks (~770 lines)
│   ├── useStreamingMessages.ts# Streaming message handling
│   ├── useDialogManagement.ts # Dialog state management
│   ├── useSessionManagement.ts# Session CRUD operations
│   ├── useRewindHandlers.ts   # Rewind functionality (~135 lines)
│   ├── useScrollBehavior.ts   # Auto-scroll behavior
│   ├── useHistoryLoader.ts    # History data loading (~42 lines)
│   └── useUsageStats.ts       # Usage statistics polling (~29 lines)
├── components/
│   ├── ChatHeader/            # Header navigation
│   ├── WelcomeScreen/         # Empty state welcome
│   ├── MessageItem/           # Message rendering
│   │   ├── MessageItem.tsx    # Main message component
│   │   └── ContentBlockRenderer.tsx # Content block rendering
│   ├── ChatInputBox/          # Input area with controls
│   ├── history/               # History view components
│   └── settings/              # Settings view components
└── utils/
    ├── toolConstants.ts       # Shared tool name constants
    ├── localizationUtils.ts   # Translation helpers
    ├── messageUtils.ts        # Message processing utilities
    └── helpers.ts             # General helpers
```

### 自定义 Hooks

- **useWindowCallbacks**：处理所有 `window.xxx` 回调注册，负责 Java bridge 通信
- **useStreamingMessages**：管理 streaming message 状态和渲染辅助
- **useDialogManagement**：管理所有 dialog 状态，支持请求队列
- **useSessionManagement**：处理 session CRUD 操作
- **useRewindHandlers**：处理 rewind（时间旅行）功能
- **useScrollBehavior**：管理 streaming 时的 auto-scroll 行为
- **useHistoryLoader**：加载历史数据
- **useUsageStats**：轮询使用统计

### 数据流

```
User intent -> sendAction(UPSTREAM.*) -> Java FrontendActionDispatcher
Java result -> context.dispatchEvent(DOWNSTREAM.*) -> subscribeEvent(...) -> React state
```

---

> ← 返回 [SKILL.md 索引](../SKILL.md)
