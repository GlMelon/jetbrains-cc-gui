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
export const EDIT_TOOL_NAMES = new Set(['edit', 'edit_file', 'replace_string', 'write_to_file', 'apply_patch']);

// Bash/command execution tools
export const BASH_TOOL_NAMES = new Set(['bash', 'run_terminal_cmd', 'exec_command', 'execute_command', 'shell_command']);

// Search/grep/glob tools
export const SEARCH_TOOL_NAMES = new Set(['grep', 'glob', 'search', 'find', 'search_files']);

// Agent/subagent spawning tools
export const AGENT_TOOL_NAMES = new Set(['task', 'agent', 'spawn_agent']);

// Task management tools (new structured Task API)
export const TASK_MANAGE_TOOL_NAMES = new Set(['taskcreate', 'taskupdate', 'taskget', 'tasklist']);

// Internal orchestration tools that may be useful during streaming but should
// not remain as residual tool cards after the final answer is complete.
export const TRANSIENT_INTERNAL_TOOL_NAMES = new Set([
  'list_mcp_resources',
  'list_mcp_resource_templates',
  'read_mcp_resource',
  'parallel',
  'multi_tool_use.parallel',
]);

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
]);

export function normalizeToolName(toolName: string): string {
  const lower = toolName.toLowerCase();
  const mcpMatch = /^mcp__(.+?)__(.+)$/.exec(lower);
  return mcpMatch ? mcpMatch[2] : lower;
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

export function isTransientInternalToolName(toolName: string | undefined): boolean {
  if (!toolName) return false;
  const lower = toolName.toLowerCase();
  return TRANSIENT_INTERNAL_TOOL_NAMES.has(lower) || TRANSIENT_INTERNAL_TOOL_NAMES.has(normalizeToolName(lower));
}
