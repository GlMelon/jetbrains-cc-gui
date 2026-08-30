/**
 * Input box component type definitions
 * Feature: 004-refactor-input-box
 */

import { PERMISSION_MODE } from '../../generated/protocol';
import type { PermissionMode, ReasoningEffort } from '../../generated/protocol';

// ============================================================
// Core Entity Types
// ============================================================

/**
 * File tag information for backend context injection (Codex mode)
 */
export interface FileTagInfo {
  /** Display path (as shown in tag) */
  displayPath: string;
  /** Absolute path (for file reading) */
  absolutePath: string;
}

/**
 * File attachment
 */
export interface Attachment {
  /** Unique identifier */
  id: string;
  /** Original filename */
  fileName: string;
  /** MIME type */
  mediaType: string;
  /** Base64 encoded content */
  data: string;
}

/**
 * Image media type constants
 */
export const IMAGE_MEDIA_TYPES = [
  'image/jpeg',
  'image/png',
  'image/gif',
  'image/webp',
  'image/svg+xml',
] as const;

/**
 * Check if attachment is an image
 */
export function isImageAttachment(attachment: Attachment): boolean {
  return IMAGE_MEDIA_TYPES.includes(attachment.mediaType as (typeof IMAGE_MEDIA_TYPES)[number]);
}

// ============================================================
// Completion System Types
// ============================================================

/**
 * Dropdown menu item data
 */
export interface DropdownItemData {
  /** Unique identifier */
  id: string;
  /** Display text */
  label: string;
  /** Description text */
  description?: string;
  /** Icon class name */
  icon?: string;
  /** Item type */
  type: 'file' | 'directory' | 'command' | 'agent' | 'prompt' | 'terminal' | 'service' | 'info' | 'separator' | 'section-header';
  /** Whether selected (for selectors) */
  checked?: boolean;
  /** Associated data */
  data?: Record<string, unknown>;
}

/**
 * File item (returned from Java)
 */
export interface FileItem {
  /** Filename */
  name: string;
  /** Relative path */
  path: string;
  /** Absolute path (optional) */
  absolutePath?: string;
  /** Type */
  type: 'file' | 'directory' | 'terminal' | 'service';
  /** Extension */
  extension?: string;
}

/**
 * Command item (returned from Java)
 */
export interface CommandItem {
  /** Command identifier */
  id: string;
  /** Display name */
  label: string;
  /** Description */
  description?: string;
  /** Category */
  category?: string;
  /**
   * Backend-annotated local action (LocalSlashAction value from SlashCommandRegistry).
   * Present = the plugin handles this command locally; absent = forward to the CLI.
   */
  localAction?: string;
}

/**
 * Dropdown menu position
 */
export interface DropdownPosition {
  /** Top coordinate (px) */
  top: number;
  /** Left coordinate (px) */
  left: number;
  /** Width (px) */
  width: number;
  /** Height (px) */
  height: number;
}

/**
 * Trigger query information
 */
export interface TriggerQuery {
  /** Trigger symbol ('@' or '/' or '#' or '!') */
  trigger: string;
  /** Search keyword */
  query: string;
  /** Character offset position of trigger symbol */
  start: number;
  /** Character offset position of query end */
  end: number;
}

/**
 * Selected agent information
 */
export interface SelectedAgent {
  id: string;
  name: string;
  prompt?: string;
}

// ============================================================
// Mode and Model Types
// ============================================================

/**
 * Permission mode for conversations.
 *
 * 类型 SSOT(C2):由后端 {@code protocol.PermissionMode} 枚举经构建时生成器
 * {@code generate-protocol-types.mjs} 产出,此处 re-export。值域含 autoEdit
 * (acceptEdits 历史别名,后端 session 可下发;UI 展示列表 AVAILABLE_MODES 不含,
 * 但类型与校验必须覆盖,见 VALID_PERMISSION_MODE_IDS)。
 */
export type { PermissionMode };

/**
 * Codex fast mode type
 */
export type CodexFastMode = 'normal' | 'fast';

/**
 * Mode information
 */
interface ModeInfo {
  id: PermissionMode;
  label: string;
  icon: string;
  disabled?: boolean;
  tooltip?: string;
  description?: string;
}

/**
 * Available permission modes
 */
export const AVAILABLE_MODES: ModeInfo[] = [
  {
    id: 'default',
    label: 'Default Mode',
    icon: 'codicon-comment-discussion',
    tooltip: 'Standard permission behavior',
    description: 'Requires manual confirmation for each operation',
  },
  {
    id: 'plan',
    label: 'Plan Mode',
    icon: 'codicon-tasklist',
    tooltip: 'Plan mode - read-only analysis',
    description: 'Read-only tools only, generates plan for user approval',
  },
  {
    id: 'acceptEdits',
    label: 'Agent Mode',
    icon: 'codicon-robot',
    tooltip: 'Auto-accept file edits',
    description: 'Auto-accept file creation/editing, fewer confirmations',
  },
  {
    id: 'bypassPermissions',
    label: 'Auto Mode',
    icon: 'codicon-zap',
    tooltip: 'Bypass all permission checks',
    description: 'Fully automated, bypasses all permission checks [use with caution]',
  },
];

/**
 * All valid PermissionMode IDs (SSOT-derived).
 *
 * 从 SSOT {@link PERMISSION_MODE}(5 值含 autoEdit 别名)派生,而非展示列表
 * {@link AVAILABLE_MODES}(4 值,不含 autoEdit)。用于校验,避免后端下发 autoEdit
 * 时被当作非法值拒绝而静默丢失状态(原 C2 bug:从 AVAILABLE_MODES 派生漏 autoEdit)。
 */
export const VALID_PERMISSION_MODE_IDS: ReadonlySet<PermissionMode> = new Set(
  Object.values(PERMISSION_MODE) as PermissionMode[],
);

/**
 * Check whether a string is a recognized PermissionMode.
 */
export function isValidPermissionMode(mode: string | undefined | null): mode is PermissionMode {
  return typeof mode === 'string' && VALID_PERMISSION_MODE_IDS.has(mode as PermissionMode);
}

/**
 * Model information
 */
export interface ModelInfo {
  id: string;
  /** Backend-issued opaque identifier used only for exact UI selection. */
  identifier: string;
  /** Registry role of a built-in model (claude provider); absent = custom model. */
  role?: 'sonnet' | 'opus' | 'fable' | 'haiku';
  label: string;
  description?: string;
    /** Base context window size in tokens; undefined = use backend default (200K) */
    contextWindow?: number;
    /** Whether the model can use the 1M context toggle even when base contextWindow is lower. */
    supports1MContext?: boolean;
}

// A4(2026-06-23):modelSupports1MContext 已删除。
// 前端不再按「claude- 非 haiku」「contextWindow >= 1M」等字符串/数值规则推断 1M 支持——
// 所有调用点(useModelProviderState / useMessageSender / LongContextToggle)改为读 registry item.supports1MContext,
// 该字段由后端 ModelRegistryService.serialize 权威下发(基于 role 配置 supports1MContext)。

// D5(2026-06-23):has1MContextSuffix / apply1MContextSuffix 已删除。
// 前端不再构造 [1m] 后缀——get_context_usage 改上送 {model: stripped, longContextEnabled} 意图,
// 后端 GetContextUsageActionHandler 据此权威追加 [1m](与 set_session_model 范式一致);
// 剥离 [1m] 仅用于展示/存储,见下方 strip1MContextSuffix。

/**
 * Remove [1m] suffix from model ID for display/storage purposes.
 */
export function strip1MContextSuffix(modelId: string | undefined | null): string {
  if (!modelId) {
    return '';
  }
  return modelId.replace(/\[1m\]$/i, '');
}

export const CLAUDE_ROLE_MODEL_IDS = {
  sonnet: 'claude-role-sonnet',
  opus: 'claude-role-opus',
  fable: 'claude-role-fable',
  haiku: 'claude-role-haiku',
} as const;

/** 默认 Claude 模型:本地走 role 模型体系(后端由 role 解析实际请求模型)。 */
export const DEFAULT_CLAUDE_MODEL_ID = CLAUDE_ROLE_MODEL_IDS.sonnet;

// A3(2026-06-23):getClaudeRoleFromModelId / normalizeClaudeModelId 已删除。
// 前端不再做「id→role 离线推导」与「未知 id 归一为 sonnet」的业务归一化——
// role 判定改读后端 registry 的 role 字段(直接查 currentRegistry.items,见 getModelSupportedReasoningLevels),
// id 规整仅剥离 [1m] 后缀(resolveClaudeModelId),业务真相源统一在后端。

// C5 SSOT:context window 默认值由后端 CommonConstants 经生成链产出
// (generated/protocol.ts#DEFAULT_CONTEXT_WINDOW / ONE_MILLION_CONTEXT_WINDOW),
// 此处 re-export 消除手抄(原 200_000 / 1_000_000 与后端逐字重复的第二真相源)。
import { DEFAULT_CONTEXT_WINDOW, ONE_MILLION_CONTEXT_WINDOW } from '../../generated/protocol';
export { DEFAULT_CONTEXT_WINDOW, ONE_MILLION_CONTEXT_WINDOW };

// Provider 级别默认上下文窗口（与后端 CommonConstants 保持一致）
const CLAUDE_DEFAULT_CONTEXT_WINDOW = 200_000;
const CODEX_DEFAULT_CONTEXT_WINDOW = 200_000;
const OPENCODE_DEFAULT_CONTEXT_WINDOW = 200_000;
const GROK_DEFAULT_CONTEXT_WINDOW = 256_000;
const KIMI_DEFAULT_CONTEXT_WINDOW = 256_000;
const PI_DEFAULT_CONTEXT_WINDOW = 200_000;

/**
 * 获取指定 provider 的默认上下文窗口大小。
 * 与后端 CommonConstants.getDefaultContextWindowForProvider 保持一致。
 */
export function getDefaultContextWindowForProvider(provider: string): number {
  switch (provider) {
    case 'claude': return CLAUDE_DEFAULT_CONTEXT_WINDOW;
    case 'codex': return CODEX_DEFAULT_CONTEXT_WINDOW;
    case 'opencode': return OPENCODE_DEFAULT_CONTEXT_WINDOW;
    case 'grok': return GROK_DEFAULT_CONTEXT_WINDOW;
    case 'kimi': return KIMI_DEFAULT_CONTEXT_WINDOW;
    case 'pi': return PI_DEFAULT_CONTEXT_WINDOW;
    default: return DEFAULT_CONTEXT_WINDOW;
  }
}

export const GROK_DEFAULT_MODEL_ID = 'grok';
export const KIMI_DEFAULT_MODEL_ID = 'auto';
export const OPENCODE_DEFAULT_MODEL_ID = 'opencode-default';
export const PI_DEFAULT_MODEL_ID = 'auto';

// A1(2026-06-23):CLAUDE_MODELS / CODEX_MODELS / AVAILABLE_MODELS 本地表已删除。
// 下述空表仅为 upstream useCliModels 等 fallback 层提供占位;registry 为权威来源,
// fallback 在 registry 未加载时返回空,与 A1「不回退本地表」语义一致。
export const CODEX_MODELS: ModelInfo[] = [];
export const DSH_MODELS: ModelInfo[] = [];
export const GROK_MODELS: ModelInfo[] = [];
export const KIMI_MODELS: ModelInfo[] = [];
export const OMP_MODELS: ModelInfo[] = [];
export const OMP_ROLE_MODELS: ModelInfo[] = [];
export const OPENCODE_MODELS: ModelInfo[] = [];
export const PI_MODELS: ModelInfo[] = [];
// 模型真相源唯一为后端 MODEL_REGISTRY 下发(ReadOnlyDefaultModels → ModelRegistryService.serialize);
// 前端经 utils/modelRegistry 订阅,空 registry 时显示 loading,不回退本地表。
// 能力(supports1MContext)/归一化(normalizeClaudeModelId 等)随 A2/A3 切片进一步下沉。

/**
 * AI provider information
 */
interface ProviderInfo {
  id: string;
  label: string;
  icon: string;
  enabled: boolean;
}

/**
 * Available AI providers
 */
export const AVAILABLE_PROVIDERS: ProviderInfo[] = [
  { id: 'claude', label: 'Claude Code', icon: 'codicon-terminal', enabled: true },
  { id: 'codex', label: 'Codex', icon: 'codicon-terminal', enabled: true },
  { id: 'opencode', label: 'OpenCode', icon: 'codicon-terminal', enabled: true },
  { id: 'grok', label: 'Grok', icon: 'codicon-terminal', enabled: true },
  { id: 'kimi', label: 'Kimi', icon: 'codicon-terminal', enabled: true },
  { id: 'pi', label: 'Pi', icon: 'codicon-terminal', enabled: true },
  // omp(pi fork,marker CLI via ai-bridge)/dsh(host RPC via ai-bridge):上游 v0.5.4 新增,
  // 后端经 ChannelCliSession spawn channel-manager.js,前端 beta 标记(promptEnhancer/commitAi 的 provider 白名单另见 aiFeatureConfig)
  { id: 'omp', label: 'OMP', icon: 'codicon-terminal', enabled: true },
  { id: 'dsh', label: 'DeepSeek Harness', icon: 'codicon-terminal', enabled: true },
];

/**
 * Built-in DSH (DeepSeek Harness) agent preset ids. User-installed presets
 * discovered from the DSH home are merged at runtime via
 * {@link getUserDshPresetOptions} (reading {@link window.__INITIAL_DSH_PRESETS__}).
 */
export const DSH_PRESETS = ['standard', 'code', 'minimal', 'cordis', 'router-standard'] as const;

/**
 * Build the full DSH preset option list by merging built-in presets with
 * user-installed presets injected via {@link window.__INITIAL_DSH_PRESETS__}.
 * Deduplicates by id. Returns `{id, label}` pairs; the actual display text
 * is resolved via i18n `t('dshPresets.{id}.label')` in the selector component,
 * with `label` (the raw id) as the defaultValue fallback.
 */
export function getUserDshPresetOptions(): { id: string; label: string }[] {
  const seen = new Set<string>();
  const options: { id: string; label: string }[] = [];

  const add = (id: string) => {
    if (!id || seen.has(id)) return;
    seen.add(id);
    options.push({ id, label: id });
  };

  for (const preset of DSH_PRESETS) {
    add(preset);
  }

  const userPresets = typeof window !== 'undefined' ? window.__INITIAL_DSH_PRESETS__ : undefined;
  if (Array.isArray(userPresets)) {
    for (const preset of userPresets) {
      if (typeof preset === 'string') {
        add(preset);
      }
    }
  }

  return options;
}

/**
 * Reasoning effort(adaptive thinking)的支持情况按模型的 role 判断,不再使用
 * model-id 白名单:sonnet/opus/fable 支持全集 5 档(含 xhigh/max),haiku 仅
 * low/medium/high(3 档)。判断逻辑见 ReasoningSelect +
 * utils/modelRegistry.getModelSupportedReasoningLevels(读 registry 的 role 字段,
 * 内置 role 与自定义模型统一处理)。Codex/OpenCode 不按 role 过滤,展示全集 5 档。
 */

/**
 * Reasoning Effort (thinking depth).
 *
 * 类型 SSOT(C2):由后端 {@code protocol.ReasoningEffort} 枚举经构建时生成器产出,此处 re-export。
 * 全集 5 档(= Claude Code CLI 全集);实际展示为按 role 子集过滤(Claude HAIKU 仅 3 档
 * low/medium/high),Codex/OpenCode 展示全集,见 {@link REASONING_LEVELS} + {@code ReasoningSelect}。
 */
export type { ReasoningEffort };

/**
 * Reasoning level information
 */
export interface ReasoningInfo {
  id: ReasoningEffort;
  label: string;
  icon: string;
  description?: string;
}

/**
 * Available reasoning levels
 */
export const REASONING_LEVELS: ReasoningInfo[] = [
  {
    id: 'low',
    label: 'Low',
    icon: 'codicon-circle-small',
    description: 'Quick responses with basic reasoning',
  },
  {
    id: 'medium',
    label: 'Medium',
    icon: 'codicon-circle-filled',
    description: 'Balanced thinking with moderate token savings',
  },
  {
    id: 'high',
    label: 'High',
    icon: 'codicon-circle-large-filled',
    description: 'Deep reasoning for complex tasks (default)',
  },
  {
    id: 'xhigh',
    label: 'XHigh',
    icon: 'codicon-flame',
    description: 'Extra deep reasoning for demanding tasks',
  },
  {
    id: 'max',
    label: 'Max',
    icon: 'codicon-rocket',
    description: 'Maximum reasoning depth',
  },
];

// ============================================================
// Component Ref Handle Types
// ============================================================

/**
 * ChatInputBox imperative API
 * Used for performance optimization - uncontrolled mode with imperative access
 */
export interface ChatInputBoxHandle {
  /** Get current input text content */
  getValue: () => string;
  /** Set input text content */
  setValue: (value: string) => void;
  /** Focus the input element */
  focus: () => void;
  /** Clear input content */
  clear: () => void;
  /** Check if input has content */
  hasContent: () => boolean;
  /** Get file tags from input (for Codex context injection) */
  getFileTags: () => FileTagInfo[];
  /** Open the model selector dropdown (used by the /model slash command) */
  openModelSelect: () => void;
}

// ============================================================
// Component Props Types
// ============================================================

/**
 * ChatInputBox component props
 */
export interface ChatInputBoxProps {
  /** Whether loading */
  isLoading?: boolean;
  /** Current model */
  selectedModel?: string;
  /** Opaque identifier of the selected model registry entry. */
  selectedModelIdentifier?: string;
  /** Current permission mode */
  permissionMode?: PermissionMode;
  /** Current provider */
  currentProvider?: string;
  /** Current DSH preset (dsh provider only) */
  dshPreset?: string;
  /** DSH preset change callback */
  onDshPresetChange?: (preset: string) => void;
  /** Usage percentage */
  usagePercentage?: number;
  /** Used context tokens */
  usageUsedTokens?: number;
  /** Maximum context tokens */
  usageMaxTokens?: number;
  /** Detailed token breakdown */
  tokenDetail?: TokenDetail;
  /** Whether to show usage */
  showUsage?: boolean;
  /** Whether to show the thinking panel (display toggle, all providers/modes) */
  showThinkingEnabled?: boolean;
  /** Attachment list */
  attachments?: Attachment[];
  /** Placeholder text */
  placeholder?: string;
  /** Whether disabled */
  disabled?: boolean;
  /** Controlled mode: input content */
  value?: string;

  /** Current active file */
  activeFile?: string;
  /** Selected lines info (e.g., "L10-20") */
  selectedLines?: string;

  /** Clear context callback */
  onClearContext?: () => void;
  /** Remove code snippet callback */
  onRemoveCodeSnippet?: (id: string) => void;

  // Event callbacks
  /** Submit message */
  onSubmit?: (content: string, attachments?: Attachment[]) => void;
  /** Stop generation */
  onStop?: () => void;
  /** Input change */
  onInput?: (content: string) => void;
  /** Add attachment */
  onAddAttachment?: (files: FileList) => void;
  /** Remove attachment */
  onRemoveAttachment?: (id: string) => void;
  /** Switch mode */
  onModeSelect?: (mode: PermissionMode) => void;
  /** Switch model */
  onModelSelect?: (model: ModelInfo) => void;
  /** Switch provider */
  onProviderSelect?: (providerId: string) => void;
  /** Current reasoning effort */
  reasoningEffort?: ReasoningEffort;
  /** Actual thinking capability negotiated by the current backend session. */
  sessionThinkingAvailable?: boolean;
  /** Switch reasoning effort callback */
  onReasoningChange?: (effort: ReasoningEffort) => void;
  /** Toggle show-thinking (display toggle, all providers/modes) */
  onShowThinkingEnabledChange?: (enabled: boolean) => void;
  /** Codex fast mode */
  codexFastMode?: CodexFastMode;
  /** Switch Codex fast mode callback */
  onCodexFastModeChange?: (mode: CodexFastMode) => void;
  /** Whether streaming is enabled */
  streamingEnabled?: boolean;
  /** Toggle streaming */
  onStreamingEnabledChange?: (enabled: boolean) => void;

  /** Send shortcut setting: 'enter' = Enter sends | 'cmdEnter' = Cmd/Ctrl+Enter sends */
  sendShortcut?: 'enter' | 'cmdEnter';

  /** Currently selected agent */
  selectedAgent?: SelectedAgent | null;
  /** Select agent callback */
  onAgentSelect?: (agent: SelectedAgent | null) => void;
  /** Clear agent callback */
  onClearAgent?: () => void;
  /** Open agent settings callback */
  onOpenAgentSettings?: () => void;
  /** Open prompt settings callback */
  onOpenPromptSettings?: () => void;
  /** Open model settings (navigate to provider management to add models) */
  onOpenModelSettings?: () => void;

  /** Whether has messages (for rewind button display) */
  hasMessages?: boolean;
  /** Rewind file callback */
  onRewind?: () => void;

  /** Whether StatusPanel is expanded */
  statusPanelExpanded?: boolean;
  /** Toggle StatusPanel expand/collapse */
  onToggleStatusPanel?: () => void;

  /** Show toast message */
  addToast?: (message: string, type: 'info' | 'success' | 'warning' | 'error') => void;

  /** Message queue items */
  messageQueue?: QueuedMessage[];
  /** Remove message from queue callback */
  onRemoveFromQueue?: (id: string) => void;

  /** Whether auto open file is enabled */
  autoOpenFileEnabled?: boolean;
  /** Toggle auto open file enabled */
  onAutoOpenFileEnabledChange?: (enabled: boolean) => void;
}

/**
 * ButtonArea component props
 */
export interface ButtonAreaProps {
  /** Whether submit disabled */
  disabled?: boolean;
  /** Whether has input content */
  hasInputContent?: boolean;
  /** Whether in conversation */
  isLoading?: boolean;
  /** Whether enhancing prompt */
  isEnhancing?: boolean;
  /** Current model */
  selectedModel?: string;
  /** Opaque identifier of the selected model registry entry. */
  selectedModelIdentifier?: string;
  /** Imperative open signal for the model selector dropdown (increment to open; /model command) */
  modelSelectOpenSignal?: number;
  /** Current mode */
  permissionMode?: PermissionMode;
  /** Current provider */
  currentProvider?: string;
  /** Current reasoning effort */
  reasoningEffort?: ReasoningEffort;
  /** Actual thinking capability negotiated by the current backend session. */
  sessionThinkingAvailable?: boolean;
  /** Current DSH preset (dsh provider only) */
  dshPreset?: string;
  /** DSH preset change callback */
  onDshPresetChange?: (preset: string) => void;
  /** Codex speed mode */
  codexFastMode?: CodexFastMode;
  /** Switch Codex speed mode callback */
  onCodexFastModeChange?: (mode: CodexFastMode) => void;
  /** Whether always thinking enabled */
  alwaysThinkingEnabled?: boolean;
  /** Toggle thinking mode */
  onToggleThinking?: (enabled: boolean) => void;
  /** Navigate to model management to add models */
  onAddModel?: () => void;
  /** Whether long context (1M) is enabled */
  longContextEnabled?: boolean;
  /** Toggle long context callback */
  onLongContextChange?: (enabled: boolean) => void;

  // Event callbacks
  onSubmit?: () => void;
  onStop?: () => void;
  onModeSelect?: (mode: PermissionMode) => void;
  onModelSelect?: (model: ModelInfo) => void;
  onProviderSelect?: (providerId: string) => void;
  /** Switch reasoning effort callback */
  onReasoningChange?: (effort: ReasoningEffort) => void;
  /** Enhance prompt callback */
  onEnhancePrompt?: () => void;
  /** Whether to show the thinking panel (display toggle, all providers/modes) */
  showThinkingEnabled?: boolean;
  /** Toggle show-thinking (display toggle, all providers/modes) */
  onShowThinkingEnabledChange?: (enabled: boolean) => void;
  /** Whether streaming enabled */
  streamingEnabled?: boolean;
  /** Toggle streaming */
  onStreamingEnabledChange?: (enabled: boolean) => void;
  /** Currently selected agent */
  selectedAgent?: SelectedAgent | null;
  /** Agent selection callback */
  onAgentSelect?: (agent: SelectedAgent) => void;
  /** Clear agent callback */
  onClearAgent?: () => void;
  /** Open agent settings callback */
  onOpenAgentSettings?: () => void;
}

/**
 * Dropdown component props
 */
export interface DropdownProps {
  /** Whether visible */
  isVisible: boolean;
  /** Position information */
  position: DropdownPosition | null;
  /** Width */
  width?: number;
  /** Y offset */
  offsetY?: number;
  /** X offset */
  offsetX?: number;
  /** Selected index */
  selectedIndex?: number;
  /** Close callback */
  onClose?: () => void;
  /** Children */
  children: React.ReactNode;
}

/**
 * Detailed token information breakdown
 */
export interface TokenDetail {
  /** Input tokens */
  inputTokens: number;
  /** Output tokens */
  outputTokens: number;
  /** Cache creation tokens */
  cacheCreationTokens: number;
  /** Cache read tokens */
  cacheReadTokens: number;
  /** Total tokens used */
  totalTokens: number;
  /** Maximum token limit */
  maxTokens: number;
  /** Usage percentage (0-100) */
  percentage: number;
  /** Cache hit rate (cache_read / total_input * 100) */
  cacheHitRate: number;
}

/**
 * TokenIndicator component props
 */
export interface TokenIndicatorProps {
  /** Percentage (0-100) */
  percentage: number;
  /** Size (deprecated, kept for API compat) */
  size?: number;
  /** Used context tokens */
  usedTokens?: number;
  /** Maximum context tokens */
  maxTokens?: number;
  /** Detailed token breakdown */
  tokenDetail?: TokenDetail;
  /** Model name for display in tooltip header */
  modelName?: string;
}

/**
 * AttachmentList component props
 */
export interface AttachmentListProps {
  /** Attachment list */
  attachments: Attachment[];
  /** Remove attachment callback */
  onRemove?: (id: string) => void;
  /** Preview image callback */
  onPreview?: (attachment: Attachment) => void;
}

/**
 * DropdownItem component props
 */
export interface DropdownItemProps {
  /** Item data */
  item: DropdownItemData;
  /** Whether highlighted */
  isActive?: boolean;
  /** Click callback */
  onClick?: () => void;
  /** Mouse enter callback */
  onMouseEnter?: () => void;
}

// ============================================================
// Message Queue Types
// ============================================================

/**
 * Queued message item
 * When AI is processing (loading), new messages are queued here
 */
export interface QueuedMessage {
  /** Unique identifier */
  id: string;
  /** Message content */
  content: string;
  /** Attachments (optional) */
  attachments?: Attachment[];
  /** Timestamp when queued */
  queuedAt: number;
}
