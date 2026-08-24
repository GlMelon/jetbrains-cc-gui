/**
 * Tool name constants for consistent tool identification across the application.
 * Centralizes tool name definitions to prevent inconsistencies.
 *
 * A7 降级决策(架构债登记簿 §A7):这些集合是「前端展示分类」——用于工具卡片图标/分组/
 * 着色,以及回滚可用性判定(A9)等纯展示语义。后端无对应 SSOT 源:工具名来自各 SDK
 * (Claude/Codex/Agent SDK)透传,后端不做统一工具元数据建模,散落处的 case/list 仅做
 * 字符串匹配。故按总则一「业务语义下沉」评估后**降级保留**为展示分类:
 *   - 集中化:本文件是分类的单一源(新增工具在此登记,杜绝散落硬编码);
 *   - 文档化:明确标注「展示分类」边界,非协议契约字段;
 *   - 不建后端 ToolRegistry:后端纯 SDK 透传,无业务分类语义来源,强推将成无源之水。
 * 后续若引入工具元数据契约(后端可产出「工具→分类」映射),再将本文件下沉为契约消费方。
 */

// Read/file viewing tools
export const READ_TOOL_NAMES = new Set(['read', 'read_file', 'read_multiple_files']);

// Edit/file modification tools
// 含 OpenCode 的 `write`(创建新文件)/`create_file`,对齐 FILE_MODIFY_TOOL_NAMES,
// 否则 OpenCode 写文件操作会落 GenericToolBlock 而非 EditToolBlock(展示样式不一致)。
export const EDIT_TOOL_NAMES = new Set([
  'edit',
  'edit_file',
  'replace_string',
  'write_to_file',
  'apply_patch',
  'write',
  'create_file',
  'multiedit',
  // Grok / Cursor-style names (UI often shows "Search Replace")
  'search_replace',
  'searchreplace',
  'str_replace',
  'strreplace',
]);

// Bash/command execution tools
export const BASH_TOOL_NAMES = new Set(['bash', 'run_terminal_cmd', 'exec_command', 'execute_command', 'shell_command']);

// Search/grep/glob tools
export const SEARCH_TOOL_NAMES = new Set(['grep', 'glob', 'search', 'find', 'search_files']);

// Agent/subagent spawning tools
export const AGENT_TOOL_NAMES = new Set(['task', 'agent', 'spawn_agent']);

// Task management tools (new structured Task API)
export const TASK_MANAGE_TOOL_NAMES = new Set(['taskcreate', 'taskupdate', 'taskget', 'tasklist']);

// File modification tools (for rewind feature - includes write for new file creation)
export const FILE_MODIFY_TOOL_NAMES = new Set([
  'write',
  'write_file',
  'edit',
  'edit_file',
  'replace_string',
  'write_to_file',
  'notebookedit',
  'create_file',
  'multiedit',
  // Grok / Cursor-style names (UI often shows "Search Replace")
  'search_replace',
  'searchreplace',
  'str_replace',
  'strreplace',
  'apply_patch',
]);

/**
 * Normalize tool names for set membership checks.
 * - lowercases
 * - strips MCP prefix mcp__server__tool → tool
 * - spaces / hyphens → underscores ("Search Replace" → "search_replace")
 * Does NOT split camelCase (TaskCreate stays "taskcreate") so existing sets keep working.
 */
export function normalizeToolName(toolName: string): string {
  const lower = toolName.toLowerCase().trim();
  const mcpMatch = /^mcp__[^_]+__(.+)$/.exec(lower);
  const base = mcpMatch ? mcpMatch[1] : lower;
  return base.replace(/[\s-]+/g, '_');
}

export function parseMcpToolName(toolName: string | undefined): { server: string; tool: string } | null {
  if (!toolName) return null;
  const match = /^mcp__(.+?)__(.+)$/.exec(toolName);
  if (!match) return null;
  return {
    server: match[1],
    tool: match[2],
  };
}

/**
 * Check if a tool name matches a set of tool names (case-insensitive)
 */
export function isToolName(toolName: string | undefined, toolSet: Set<string>): boolean {
  return toolName !== undefined && toolSet.has(normalizeToolName(toolName));
}

const TRANSIENT_INTERNAL_TOOL_NAMES = new Set([
  'list_mcp_resources',
  'list_mcp_resource_templates',
  'read_mcp_resource',
  'parallel',
  'multi_tool_use.parallel',
]);

export function isTransientInternalToolName(toolName: string | undefined): boolean {
  if (!toolName) return false;
  const lower = toolName.toLowerCase();
  return TRANSIENT_INTERNAL_TOOL_NAMES.has(lower) || TRANSIENT_INTERNAL_TOOL_NAMES.has(normalizeToolName(lower));
}

/**
 * Whether a content block is a tool_use that renders nothing in the message
 * list (TodoWrite, TaskCreate, update_plan, and transient internal tools once
 * streaming ends). Mirrors the null-return branches in ContentBlockRenderer so
 * callers can filter such blocks before rendering - their arrival otherwise
 * re-renders the message and shifts the streaming thinking block's last-block
 * status, which flickered the thinking block. Pass the message's streaming
 * flag so the transient-internal branch matches the renderer's behavior.
 */
export function isNonRenderedToolUse(
  block: { type?: string; name?: string },
  isStreaming: boolean,
): boolean {
  if (block.type !== 'tool_use') return false;
  const toolName = normalizeToolName(block.name ?? '');
  if (toolName === 'todowrite' || toolName === 'update_plan' || TASK_MANAGE_TOOL_NAMES.has(toolName)) {
    return true;
  }
  if (!isStreaming && isTransientInternalToolName(block.name)) {
    return true;
  }
  return false;
}

