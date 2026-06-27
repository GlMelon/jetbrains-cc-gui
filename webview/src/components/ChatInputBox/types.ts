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
 * Code snippet (from editor selection)
 */
export interface CodeSnippet {
  /** Unique identifier */
  id: string;
  /** File path (relative) */
  filePath: string;
  /** Start line number */
  startLine?: number;
  /** End line number */
  endLine?: number;
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

export type ImageMediaType = (typeof IMAGE_MEDIA_TYPES)[number];

/**
 * Check if attachment is an image
 */
export function isImageAttachment(attachment: Attachment): boolean {
  return IMAGE_MEDIA_TYPES.includes(attachment.mediaType as ImageMediaType);
}

// ============================================================
// Completion System Types
// ============================================================

/**
 * Completion item type
 */
export type CompletionType =
  | 'file'
  | 'directory'
  | 'command'
  | 'agent'
  | 'prompt'
  | 'terminal'
  | 'service'
  | 'info'
  | 'separator'
  | 'section-header';

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
  type: CompletionType;
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
export interface ModeInfo {
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
 * Set of valid permission mode IDs, derived from the SSOT PERMISSION_MODE(5 值,含 autoEdit 别名)。
 *
 * 刻意与展示列表 AVAILABLE_MODES(4 值,无 autoEdit)解耦:autoEdit 是 acceptEdits 的
 * 历史别名,后端 session 可能下发,UI 不单独展示但必须接受为合法值,否则状态静默丢失。
 * Use isValidPermissionMode() for validation instead of inline checks.
 */
export const VALID_PERMISSION_MODE_IDS: ReadonlySet<string> = new Set(
  Object.values(PERMISSION_MODE)
);

/**
 * Check whether a string is a recognized PermissionMode.
 */
export function isValidPermissionMode(mode: string | undefined | null): mode is PermissionMode {
  return typeof mode === 'string' && VALID_PERMISSION_MODE_IDS.has(mode);
}

/**
 * Model information
 */
export interface ModelInfo {
  id: string;
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

export type ClaudeRoleModelId = typeof CLAUDE_ROLE_MODEL_IDS[keyof typeof CLAUDE_ROLE_MODEL_IDS];

// A3(2026-06-23):getClaudeRoleFromModelId / normalizeClaudeModelId 已删除。
// 前端不再做「id→role 离线推导」与「未知 id 归一为 sonnet」的业务归一化——
// role 判定改读后端 registry 的 role 字段(utils/modelRegistry.resolveClaudeRoleForModel),
// id 规整仅剥离 [1m] 后缀(resolveClaudeModelId),业务真相源统一在后端。

// C5 SSOT:context window 默认值由后端 CommonConstants 经生成链产出
// (generated/protocol.ts#DEFAULT_CONTEXT_WINDOW / ONE_MILLION_CONTEXT_WINDOW),
// 此处 re-export 消除手抄(原 200_000 / 1_000_000 与后端逐字重复的第二真相源)。
export { DEFAULT_CONTEXT_WINDOW, ONE_MILLION_CONTEXT_WINDOW } from '../../generated/protocol';

// A1(2026-06-23):CLAUDE_MODELS / CODEX_MODELS / AVAILABLE_MODELS 本地表已删除。
// 模型真相源唯一为后端 MODEL_REGISTRY 下发(ReadOnlyDefaultModels → ModelRegistryService.serialize);
// 前端经 utils/modelRegistry 订阅,空 registry 时显示 loading,不回退本地表。
// 能力(supports1MContext)/归一化(normalizeClaudeModelId 等)随 A2/A3 切片进一步下沉。

/**
 * AI provider information
 */
export interface ProviderInfo {
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
  { id: 'gemini', label: 'Gemini Cli', icon: 'codicon-terminal', enabled: false },
  { id: 'opencode', label: 'OpenCode', icon: 'codicon-terminal', enabled: true },
];

/**
 * Reasoning effort(adaptive thinking)的支持情况按模型的 role 判断,不再使用
 * model-id 白名单:opus/fable/sonnet 支持 effort(其中 opus/fable 额外支持
 * xhigh),haiku 不支持。判断逻辑见 ReasoningSelect +
 * utils/modelRegistry.resolveClaudeRoleForModel(内置 role 与自定义模型的
 * role 字段统一处理)。
 */

/**
 * Reasoning Effort (thinking depth).
 *
 * 类型 SSOT(C2):由后端 {@code protocol.ReasoningEffort} 枚举经构建时生成器产出,此处 re-export。
 * 全集 5 档(= Claude API 全集);实际展示为按 role/provider 子集过滤(Codex 无 max、HAIKU 无
 * xhigh/max),见 {@link REASONING_LEVELS} + {@code ReasoningSelect}。
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
// Usage Types
// ============================================================

/**
 * Usage information
 */
export interface UsageInfo {
  /** Usage percentage (0-100) */
  percentage: number;
  /** Used amount */
  used?: number;
  /** Total amount */
  total?: number;
}

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
  /** Current permission mode */
  permissionMode?: PermissionMode;
  /** Current provider */
  currentProvider?: string;
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
  /** Whether always thinking is enabled */
  alwaysThinkingEnabled?: boolean;
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
  onModelSelect?: (modelId: string, contextWindow?: number) => void;
  /** Switch provider */
  onProviderSelect?: (providerId: string) => void;
  /** Current reasoning effort */
  reasoningEffort?: ReasoningEffort;
  /** Switch reasoning effort callback */
  onReasoningChange?: (effort: ReasoningEffort) => void;
  /** Toggle thinking mode */
  onToggleThinking?: (enabled: boolean) => void;
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

  /** SDK installed status (disable input when not installed) */
  sdkInstalled?: boolean;
  /** SDK status loading state */
  sdkStatusLoading?: boolean;
  /** Go to install SDK callback */
  onInstallSdk?: () => void;
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
  /** Current mode */
  permissionMode?: PermissionMode;
  /** Current provider */
  currentProvider?: string;
  /** Current reasoning effort */
  reasoningEffort?: ReasoningEffort;

  // Event callbacks
  onSubmit?: () => void;
  onStop?: () => void;
  onModeSelect?: (mode: PermissionMode) => void;
  onModelSelect?: (modelId: string, contextWindow?: number) => void;
  onProviderSelect?: (providerId: string) => void;
  /** Switch reasoning effort callback */
  onReasoningChange?: (effort: ReasoningEffort) => void;
  /** Enhance prompt callback */
  onEnhancePrompt?: () => void;
  /** Whether always thinking enabled */
  alwaysThinkingEnabled?: boolean;
  /** Toggle thinking mode */
  onToggleThinking?: (enabled: boolean) => void;
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
