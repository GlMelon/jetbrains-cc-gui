import type { HistoryCapabilitiesPayloadWire, SessionCapabilitiesPayloadWire } from '../generated/protocol';

export type ToolInput = Record<string, unknown>;

/**
 * 当前激活的主视图标签。
 * D3:从 useModelProviderState / useScrollBehavior / useSessionManagement 三处重复定义收敛至此唯一来源。
 */
export type ViewMode = 'chat' | 'history' | 'settings';

export interface CompactNotificationItem {
  type: 'stdout';
  text: string;
}

/**
 * Metadata for compact summary messages.
 * Contains information about the compaction operation.
 */
export interface CompactSummaryMetadata {
  messagesSummarized?: number;
  direction?: 'up_to' | 'from';
  userContext?: string;
  /** Compaction trigger (e.g. 'manual', 'auto') */
  trigger?: string;
  /** Token count before compaction */
  preTokens?: number;
  /** Token count after compaction */
  postTokens?: number;
  /** Compaction duration in milliseconds */
  durationMs?: number;
}

/**
 * Type guard for CompactSummaryMetadata.
 */
export function isCompactSummaryMetadata(obj: unknown): obj is CompactSummaryMetadata {
  if (!obj || typeof obj !== 'object') return false;
  const m = obj as Record<string, unknown>;
  if (m.messagesSummarized !== undefined && typeof m.messagesSummarized !== 'number') return false;
  if (m.direction !== undefined && m.direction !== 'up_to' && m.direction !== 'from') return false;
  if (m.userContext !== undefined && typeof m.userContext !== 'string') return false;
  return true;
}

export type ClaudeContentBlock =
  | { type: 'text'; text?: string }
  | { type: 'thinking'; thinking?: string; text?: string }
  | { type: 'tool_use'; id?: string; name?: string; input?: ToolInput }
  | {
      type: 'skill_use';
      name: string;
      command?: string;
      args?: string;
      source?: string;
    }
  | {
      type: 'image';
      src?: string;
      mediaType?: string;
      alt?: string;
      previewSrc?: string;
      thumbnailSrc?: string;
      sourceKind?: 'base64' | 'resource_url';
      localPath?: string;
    }
  | { type: 'attachment'; fileName?: string; mediaType?: string }
  | {
      type: 'provider_error';
      provider?: string;
      summary?: string;
      details?: string;
      exitCode?: number | string;
      requestId?: string;
      url?: string;
    }
  | {
      type: 'file_change';
      path?: string;
      operation?: string;
      status?: string;
      title?: string;
      summary?: string;
      details?: string;
    }
  | {
      type: 'mcp_tool_call';
      server?: string;
      tool?: string;
      title?: string;
      summary?: string;
      status?: string;
      input?: unknown;
      result?: unknown;
      details?: string;
    }
  | {
      type: 'web_search';
      query?: string;
      url?: string;
      title?: string;
      summary?: string;
      status?: string;
      details?: string;
    }
  | {
      type: 'todo_list';
      items?: Array<{
        text?: string;
        content?: string;
        status?: string;
        title?: string;
        [key: string]: unknown;
      }>;
      title?: string;
      summary?: string;
      status?: string;
      details?: string;
    }
  | {
      type: 'provider_event';
      provider?: string;
      eventType?: string;
      itemType?: string;
      title?: string;
      summary?: string;
      details?: string;
      raw?: unknown;
    }
  | { type: 'task_notification'; icon: string; summary: string; status: string; detail?: string }
  | { type: 'compact_notification'; headerText: string; items: CompactNotificationItem[] }
  | { type: 'compact_summary'; title: string; content: string; metadata?: CompactSummaryMetadata };

export interface ToolResultBlock {
  type: 'tool_result';
  tool_use_id?: string;
  content?: string | Array<{ type?: string; text?: string }>;
  is_error?: boolean;
  [key: string]: unknown;
}

export type ClaudeContentOrResultBlock = ClaudeContentBlock | ToolResultBlock;

export interface ClaudeRawMessage {
  content?: string | ClaudeContentOrResultBlock[];
  message?: { content?: string | ClaudeContentOrResultBlock[] };
  type?: string;
  /** Backend-derived Claude file checkpoint availability. */
  rewindable?: boolean;
  /** Origin indicates message source - used to filter synthetic messages */
  origin?: { kind: string };
  isMeta?: boolean;
  toolUseResult?: unknown;
  isCompactSummary?: boolean;
  [key: string]: unknown;
}

export interface AssistantResponseStatusPayload {
  phase: string;
  providerLabel: string;
  title: string;
  description?: string;
  /** 前端 i18n 语义 key(常规=phase value;特殊:apiRetry/cancelled);缺省时按 phase 查 */
  descriptionKey?: string;
  /** api_retry 重试次序(1-based);缺省/<=0 显示 "?" */
  attempt?: number;
  /** api_retry 最大重试次数;缺省/<=0 显示 "?" */
  maxRetries?: number;
  elapsedMs?: number;
  active: boolean;
}

/** Represents a single message in the chat conversation. */
export interface ClaudeMessage {
  type:
    'user' | 'assistant' | 'error' | 'task_notification' | 'notification' | 'compact_notification' | 'system';
  content?: string;
  raw?: ClaudeRawMessage | string;
  timestamp?: string;
  isStreaming?: boolean;
  isOptimistic?: boolean;
  durationMs?: number;
  streamEndSource?: 'backend' | 'watchdog';
  streamEndReason?: string;
  /**
   * Runtime-only: numeric turn identifier for streaming assistant isolation.
   * Set by frontend during streaming to distinguish messages from different
   * conversation turns. Messages with different __turnId values should never
   * be merged. Undefined for history messages loaded from JSONL files.
   */
  __turnId?: number;
  /**
   * Runtime-only: groups multiple assistant content groups that belong to the
   * same streamed model response. Used by the frontend to render one response
   * container with lightweight internal separators.
   */
  __responseId?: string;
  /**
   * Runtime-only: suppresses the initial streaming connection hint on assistant
   * placeholders created for later stream segments.
   */
  __suppressStreamingConnectHint?: boolean;
  /** Runtime-only: backend-computed assistant response phase status. */
  __assistantResponseStatus?: AssistantResponseStatusPayload;
  [key: string]: unknown;
}

export interface TodoItem {
  id?: string;
  content: string;
  status: 'pending' | 'in_progress' | 'completed';
  /** IDs of tasks that block this task (numeric string format from TaskCreate/TaskUpdate, e.g., "1", "2") */
  blockedBy?: string[];
}

export interface HistorySessionSummary {
  sessionId: string;
  title: string;
  messageCount: number;
  lastTimestamp?: string;
  isFavorited?: boolean;
  favoritedAt?: number;
  provider?: string; // 'claude' | 'codex' | 'grok' | 'opencode' | …
  /** Model used by this session when known (restored on open). */
  model?: string;
  /** Agent name when known (OpenCode / Claude). */
  agent?: string;
  fileSize?: number;
  entrypoint?: string; // Session entrypoint: 'cli', 'sdk-cli', 'claude-vscode', etc.
  /** Runtime capabilities observed for this historical session, when available. */
  sessionCapabilities?: SessionCapabilitiesPayloadWire;
}

export interface HistoryData {
  success: boolean;
  error?: string;
  sessions?: HistorySessionSummary[];
  total?: number;
  favorites?: Record<string, { favoritedAt: number }>;
  capabilities?: HistoryCapabilitiesPayloadWire;
}

// File changes types
export type { FileChangeStatus, EditOperation, FileChangeSummary } from './fileChanges';

// Subagent types
export type {
  SubagentStatus,
  SubagentInfo,
  SubagentHistoryResponse,
  SubagentStatusSnapshot,
  SubagentStatusesResponse,
  TaskEvent,
  TaskEventMap,
  TaskEventStatus,
} from './subagent';
