/**
 * Skills type definitions
 *
 * Skills are custom command and feature extensions stored in specific directories:
 * - Global: ~/.claude/skills (enabled) / ~/.codemoss/skills/global (disabled)
 * - Local: {workspace}/.claude/skills (enabled) / ~/.codemoss/skills/{project-hash} (disabled)
 *
 * Each skill can be a file (.md) or a directory (containing skill.md)
 */

/**
 * Skill type: file or directory
 */
type SkillType = 'file' | 'directory';

/**
 * Skill scope:
 * - Claude: global (user-level) or local (project-level)
 * - Codex: user (~/.agents/skills/) or repo ({cwd}/.agents/skills/)
 */
export type SkillScope = 'global' | 'local' | 'user' | 'repo';

/**
 * Skill configuration
 */
export interface Skill {
  /** Unique identifier (format: {scope}-{name} or {scope}-{name}-disabled) */
  id: string;
  /** Display name */
  name: string;
  /** Type: file or directory */
  type: SkillType;
  /** Scope: global or local */
  scope: SkillScope;
  /** Full path */
  path: string;
  /** Whether enabled (true: in active directory, false: in managed directory) */
  enabled: boolean;
  /** Description (extracted from skill.md frontmatter) */
  description?: string;
  /** Skill path for Codex config.toml operations */
  skillPath?: string;
  /** Creation time */
  createdAt?: string;
  /** Modification time */
  modifiedAt?: string;
}

/**
 * Skills configuration structure
 * Claude uses global/local, Codex uses user/repo
 */
export interface SkillsConfig {
  /** Global skills (Claude only) */
  global: Record<string, Skill>;
  /** Local skills (Claude only) */
  local: Record<string, Skill>;
  /** User-level skills (Codex only) */
  user?: Record<string, Skill>;
  /** Repository-level skills (Codex only) */
  repo?: Record<string, Skill>;
}

/**
 * Skills filter
 */
export type SkillFilter = 'all' | 'global' | 'local' | 'user' | 'repo';

/**
 * Skills enabled status filter
 */
export type SkillEnabledFilter = 'all' | 'enabled' | 'disabled';

/** Backend-declared generic editor control. */
export type SkillFieldControl = 'text' | 'textarea' | 'boolean' | 'string-list';

export type SkillFieldValue = string | boolean | string[] | null;

/** One backend-declared editable frontmatter field. */
export interface SkillDocumentField {
  key: string;
  labelKey: string;
  control: SkillFieldControl;
  required: boolean;
  maxLength?: number;
  present: boolean;
  value: SkillFieldValue;
}

/** Result returned by the backend SKILL.md document authority. */
export interface SkillDocumentResult {
  requestId?: string;
  success: boolean;
  editable: boolean;
  parseError?: boolean;
  conflict?: boolean;
  rolledBack?: boolean;
  changed?: boolean;
  error?: string;
  provider?: string;
  revision?: string;
  fileName?: string;
  body?: string;
  fields?: SkillDocumentField[];
}

export interface SkillDocumentSavePayload {
  changes: Record<string, SkillFieldValue>;
  body: string;
}
