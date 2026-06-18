/**
 * bridge/events/index.ts
 *
 * 下行事件目录(Central Event Registry)。
 *
 * 这是「新增下行事件」的唯一登记处,也是后端 dispatchEvent type 字符串的事实来源。
 * Phase 0 仅建骨架(空),各 Phase 迁移具体回调时在此追加 BridgeEventDef 条目,
 * 并附 payload 类型与语义标注(kind)。
 *
 * 新增下行事件标准步骤(详见 docs/feat 文档):
 *   1. 在此目录登记一个 BridgeEventDef({ type, kind });
 *   2. 后端 context.dispatchEvent(type, payloadJson);
 *   3. 业务模块 bridgeHub.subscribe(type, handler)。
 *
 * 迁移期:同时调用 compat.registerLegacyAlias(legacyName, type) 保持后端旧调用兼容。
 */

import type { BridgeEventDef } from '../types';

/**
 * 已登记事件清单(100+ 个 type)。详见 docs/feat/bridge-normalization.md。
 */
export const BRIDGE_EVENTS: readonly BridgeEventDef[] = [
  // ── Phase 1:纯事件类(usage/settings)── 旧 window 回调名见 compat 映射
  { type: 'usage.update', kind: 'event' },
  { type: 'setting.streaming_enabled', kind: 'event' },
  { type: 'setting.send_shortcut', kind: 'event' },
  { type: 'setting.auto_open_file', kind: 'event' },
  { type: 'setting.thinking_enabled', kind: 'event' },
  { type: 'setting.permission_dialog_timeout', kind: 'event' },
  // ── Phase 1:mode/model(裸字符串 payload,透明管道原样传递)──
  { type: 'mode.changed', kind: 'event' },      // 旧 onModeChanged
  { type: 'mode.received', kind: 'event' },     // 旧 onModeReceived(有 pending 槽)
  { type: 'model.changed', kind: 'event' },     // 旧 onModelChanged
  // ── Phase 1:provider(provider_list / active_provider / codex 变体)──
  { type: 'provider.list', kind: 'event' },              // 旧 updateProviders
  { type: 'provider.active', kind: 'event' },            // 旧 updateActiveProvider(合并双写)
  { type: 'provider.codex_list', kind: 'event' },        // 旧 updateCodexProviders
  { type: 'provider.active_codex', kind: 'event' },      // 旧 updateActiveCodexProvider
  // ── Phase 1:session 运行时状态(JSON)──
  { type: 'session.invocation_mode', kind: 'event' },    // 旧 updateSessionInvocationMode
  { type: 'session.runtime_state', kind: 'event' },      // 旧 updateSessionRuntimeState
  // 注:onModelConfirmed(modelId, provider) 为两参数,compat 单参别名会丢 provider,
  //     暂不迁移(保留 window.onModelConfirmed),待后续连同后端 payload 归一化处理。
  // ── Phase 3:RPC(请求-响应)──
  { type: 'file_path.resolve', kind: 'rpc' },           // 前端 request → 后端 resolve
  { type: 'file_path.resolved', kind: 'rpc' },          // 后端回包(由 hub request 内部订阅)
  // ── Phase 4:streaming(高频流式增量,passthrough 直通)──
  { type: 'stream.start', kind: 'streaming' },            // 旧 onStreamStart
  { type: 'stream.content_delta', kind: 'streaming' },     // 旧 onContentDelta(最高频)
  { type: 'stream.thinking_delta', kind: 'streaming' },    // 旧 onThinkingDelta(最高频)
  { type: 'stream.end', kind: 'streaming' },               // 旧 onStreamEnd
  { type: 'stream.heartbeat', kind: 'streaming' },         // 旧 onStreamingHeartbeat
  { type: 'stream.permission_denied', kind: 'streaming' }, // 旧 onPermissionDenied
  { type: 'stream.block_reset', kind: 'streaming' },       // 旧 onBlockReset
  // ── Phase 5:bootstrap/DOM 副作用(不进 React state)──
  { type: 'font.apply_editor', kind: 'bootstrap' },        // 旧 applyIdeaFontConfig
  { type: 'font.apply_ui', kind: 'bootstrap' },            // 旧 applyUiFontConfig
  { type: 'language.apply', kind: 'bootstrap' },           // 旧 applyIdeaLanguageConfig
  { type: 'appearance.apply', kind: 'bootstrap' },         // 旧 applyAppearanceConfig
  { type: 'theme.received', kind: 'bootstrap' },           // 旧 onIdeThemeReceived
  { type: 'theme.changed', kind: 'bootstrap' },            // 旧 onIdeThemeChanged
  { type: 'linkify.update', kind: 'bootstrap' },           // 旧 updateLinkifyCapabilities
  { type: 'usage.statistics', kind: 'event' },             // 旧 updateUsageStatistics
  { type: 'context.action', kind: 'event' },               // 旧 execContextAction(裸字符串)
  // ── Phase 5:对话框类(累加型 pending)──
  { type: 'dialog.permission', kind: 'event' },            // 旧 showPermissionDialog
  { type: 'dialog.ask_user_question', kind: 'event' },     // 旧 showAskUserQuestionDialog
  { type: 'dialog.plan_approval', kind: 'event' },         // 旧 showPlanApprovalDialog
  // ── Phase 5:toast/UI 通知 ──
  { type: 'toast.error', kind: 'event' },                  // 旧 showError
  { type: 'toast.success', kind: 'event' },                // 旧 showSuccess
  { type: 'toast.success_i18n', kind: 'event' },           // 旧 showSuccessI18n
  // ── Phase 5:config/settings 配置类 ──
  { type: 'config.working_directory', kind: 'event' },     // 旧 updateWorkingDirectory
  { type: 'config.codex_sandbox_mode', kind: 'event' },    // 旧 updateCodexSandboxMode
  { type: 'config.invocation_mode', kind: 'event' },       // 旧 updateInvocationMode
  { type: 'config.claude_cli_path', kind: 'event' },       // 旧 updateClaudeCliPath
  { type: 'config.commit_prompt', kind: 'event' },         // 旧 updateCommitPrompt
  { type: 'config.prompt_enhancer', kind: 'event' },       // 旧 updatePromptEnhancerConfig
  { type: 'config.commit_ai', kind: 'event' },             // 旧 updateCommitAiConfig
  { type: 'config.project_commit_prompt', kind: 'event' }, // 旧 updateProjectCommitPrompt
  { type: 'config.commit_generation', kind: 'event' },     // 旧 updateCommitGenerationEnabled
  { type: 'config.ai_title_generation', kind: 'event' },   // 旧 updateAiTitleGenerationEnabled
  // ── Phase 5:font 配置接收 ──
  { type: 'font.editor_config_received', kind: 'bootstrap' }, // 旧 onEditorFontConfigReceived
  { type: 'font.ui_config_received', kind: 'bootstrap' },     // 旧 onUiFontConfigReceived
  { type: 'font.code_config_received', kind: 'bootstrap' },   // 旧 onCodeFontConfigReceived
  { type: 'font.apply_code', kind: 'bootstrap' },              // 旧 applyCodeFontConfig
  { type: 'config.status_bar_widget', kind: 'event' },         // 旧 updateStatusBarWidgetEnabled
  { type: 'config.task_completion_notification', kind: 'event' }, // 旧 updateTaskCompletionNotificationEnabled
  // ── Phase 7:剩余旧 callJavaScript 迁移 ──
  // ── Toast/通知(showError/showSuccess/showSwitchSuccess 由 useSettingsWindowCallbacks 订阅) ──
  { type: 'toast.switch_success', kind: 'event' },         // 旧 showSwitchSuccess
  // ── Agent ──
  { type: 'agent.list', kind: 'event' },                   // 旧 updateAgents
  { type: 'agent.operation_result', kind: 'event' },       // 旧 agentOperationResult
  { type: 'agent.selected_changed', kind: 'event' },       // 旧 onSelectedAgentChanged
  { type: 'agent.selected_received', kind: 'event' },      // 旧 onSelectedAgentReceived
  { type: 'agent.import_preview', kind: 'event' },         // 旧 agentImportPreviewResult
  { type: 'agent.import_result', kind: 'event' },          // 旧 agentImportResult
  // ── Provider 补充 ──
  { type: 'provider.claude_config', kind: 'event' },       // 旧 updateCurrentClaudeConfig
  { type: 'provider.codex_config', kind: 'event' },        // 旧 updateCurrentCodexConfig
  { type: 'provider.cli_login_account', kind: 'event' },   // 旧 updateCliLoginAccountInfo
  { type: 'provider.import_preview', kind: 'event' },      // 旧 import_preview_result
  // ── MCP Server ──
  { type: 'mcp.server_list', kind: 'event' },              // 旧 updateMcpServers
  { type: 'mcp.server_status', kind: 'event' },            // 旧 updateMcpServerStatus
  { type: 'mcp.server_tools', kind: 'event' },             // 旧 updateMcpServerTools
  { type: 'mcp.server_added', kind: 'event' },             // 旧 mcpServerAdded
  { type: 'mcp.server_updated', kind: 'event' },           // 旧 mcpServerUpdated
  { type: 'mcp.server_deleted', kind: 'event' },           // 旧 mcpServerDeleted
  { type: 'mcp.server_toggled', kind: 'event' },           // 旧 mcpServerToggled
  { type: 'mcp.server_validated', kind: 'event' },         // 旧 mcpServerValidated
  // ── Codex MCP Server ──
  { type: 'codex.mcp.server_list', kind: 'event' },        // 旧 updateCodexMcpServers
  { type: 'codex.mcp.server_status', kind: 'event' },      // 旧 updateCodexMcpServerStatus
  { type: 'codex.mcp.server_added', kind: 'event' },       // 旧 codexMcpServerAdded
  { type: 'codex.mcp.server_updated', kind: 'event' },     // 旧 codexMcpServerUpdated
  { type: 'codex.mcp.server_deleted', kind: 'event' },     // 旧 codexMcpServerDeleted
  { type: 'codex.mcp.server_toggled', kind: 'event' },     // 旧 codexMcpServerToggled
  { type: 'codex.mcp.server_validated', kind: 'event' },   // 旧 codexMcpServerValidated
  // ── Dependency/SDK ──
  { type: 'dependency.status', kind: 'event' },             // 旧 updateDependencyStatus
  { type: 'dependency.install_result', kind: 'event' },     // 旧 dependencyInstallResult
  { type: 'dependency.uninstall_result', kind: 'event' },   // 旧 dependencyUninstallResult
  { type: 'dependency.update_available', kind: 'event' },   // 旧 dependencyUpdateAvailable
  { type: 'dependency.versions_loaded', kind: 'event' },    // 旧 dependencyVersionsLoaded
  { type: 'dependency.install_progress', kind: 'event' },   // 旧 dependencyInstallProgress
  { type: 'node.env_status', kind: 'event' },               // 旧 nodeEnvironmentStatus
  // ── Input History ──
  { type: 'input_history.loaded', kind: 'event' },          // 旧 onInputHistoryLoaded
  { type: 'input_history.recorded', kind: 'event' },        // 旧 onInputHistoryRecorded
  { type: 'input_history.deleted', kind: 'event' },         // 旧 onInputHistoryDeleted
  { type: 'input_history.cleared', kind: 'event' },         // 旧 onInputHistoryCleared
  // ── Node ──
  { type: 'node.process_list', kind: 'event' },             // 旧 updateNodeProcesses
  { type: 'node.process_kill_result', kind: 'event' },      // 旧 nodeProcessKillResult
  { type: 'node.path', kind: 'event' },                     // 旧 updateNodePath
  { type: 'node.check_env', kind: 'event' },                // 旧 checkNodeEnvironment
  // ── Clipboard ──
  { type: 'clipboard.read', kind: 'event' },                // 旧 onClipboardRead
  // ── Prompt ──
  { type: 'prompt.project_info', kind: 'event' },           // 旧 updateProjectInfo
  { type: 'prompt.global_list', kind: 'event' },            // 旧 updateGlobalPrompts
  { type: 'prompt.project_list', kind: 'event' },           // 旧 updateProjectPrompts
  { type: 'prompt.list', kind: 'event' },                   // 旧 updatePrompts(通用)
  { type: 'prompt.operation_result', kind: 'event' },       // 旧 promptOperationResult
  { type: 'prompt.import_preview', kind: 'event' },         // 旧 promptImportPreviewResult
  { type: 'prompt.import_result', kind: 'event' },          // 旧 promptImportResult
  { type: 'prompt.enhanced', kind: 'event' },               // 旧 updateEnhancedPrompt
  // ── Skill ──
  { type: 'skill.list', kind: 'event' },                    // 旧 updateSkills
  { type: 'skill.import_result', kind: 'event' },           // 旧 skillImportResult
  { type: 'skill.delete_result', kind: 'event' },           // 旧 skillDeleteResult
  { type: 'skill.toggle_result', kind: 'event' },           // 旧 skillToggleResult
  // ── 其他 ──
  { type: 'file.list_result', kind: 'event' },              // 旧 onFileListResult
  { type: 'model.confirmed', kind: 'event' },               // 旧 onModelConfirmed(双参数→单 JSON)
  { type: 'session.title', kind: 'event' },                 // 旧 updateSessionTitle
  { type: 'codex.subscription_quota', kind: 'event' },      // 旧 updateCodexSubscriptionQuota
  { type: 'slash.dollar_commands', kind: 'event' },          // 旧 updateDollarCommands
  { type: 'language.user_language', kind: 'event' },         // 旧 onUserLanguage
];

/**
 * 派生:type 集合,用于校验 dispatch/subscribe 拼写。迁移完成后可作为白名单守卫。
 */
export const BRIDGE_EVENT_TYPES: ReadonlySet<string> = new Set(
  BRIDGE_EVENTS.map((e) => e.type),
);
