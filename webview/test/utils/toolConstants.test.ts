import { describe, it, expect } from 'vitest';
import {
  EDIT_TOOL_NAMES,
  BASH_TOOL_NAMES,
  FILE_MODIFY_TOOL_NAMES,
  isToolName,
} from '../../src/utils/toolConstants';

describe('EDIT_TOOL_NAMES', () => {
  // OpenCode 的内置工具用 `write` 创建新文件、`edit` 修改文件。历史上 EDIT_TOOL_NAMES
  // 只登记了 `write_to_file`(Claude 风格)而漏了 OpenCode 的 `write`/`create_file`,
  // 导致 OpenCode 创建文件操作落 GenericToolBlock 而非 EditToolBlock。此处对齐
  // FILE_MODIFY_TOOL_NAMES,确保 OpenCode 文件写入操作显示为编辑卡。
  it('classifies OpenCode write/create_file as edit tools (case-insensitive)', () => {
    expect(isToolName('write', EDIT_TOOL_NAMES)).toBe(true);
    expect(isToolName('create_file', EDIT_TOOL_NAMES)).toBe(true);
    expect(isToolName('Write', EDIT_TOOL_NAMES)).toBe(true);
    expect(isToolName('CREATE_FILE', EDIT_TOOL_NAMES)).toBe(true);
  });

  it('still classifies Claude-flavored edit tool names', () => {
    expect(isToolName('edit', EDIT_TOOL_NAMES)).toBe(true);
    expect(isToolName('edit_file', EDIT_TOOL_NAMES)).toBe(true);
    expect(isToolName('write_to_file', EDIT_TOOL_NAMES)).toBe(true);
    expect(isToolName('apply_patch', EDIT_TOOL_NAMES)).toBe(true);
  });

  it('does not classify non-edit tools as edit', () => {
    expect(isToolName('bash', EDIT_TOOL_NAMES)).toBe(false);
    expect(isToolName('read', EDIT_TOOL_NAMES)).toBe(false);
    expect(isToolName('grep', EDIT_TOOL_NAMES)).toBe(false);
  });
});

describe('BASH_TOOL_NAMES (command 区)', () => {
  it('classifies command-execution tool names', () => {
    expect(isToolName('bash', BASH_TOOL_NAMES)).toBe(true);
    expect(isToolName('run_terminal_cmd', BASH_TOOL_NAMES)).toBe(true);
    expect(isToolName('exec_command', BASH_TOOL_NAMES)).toBe(true);
  });
});

describe('FILE_MODIFY_TOOL_NAMES (rewind 可用性判定)', () => {
  it('includes all edit tools for rewind eligibility', () => {
    expect(isToolName('write', FILE_MODIFY_TOOL_NAMES)).toBe(true);
    expect(isToolName('create_file', FILE_MODIFY_TOOL_NAMES)).toBe(true);
    expect(isToolName('edit', FILE_MODIFY_TOOL_NAMES)).toBe(true);
  });
});
