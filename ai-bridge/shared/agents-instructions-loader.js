/**
 * Provider-agnostic AGENTS.md discovery & collection (shared across Claude/Codex/OpenCode).
 *
 * Lifted from services/codex/codex-agents-loader.js so all three providers can reuse the same
 * project-level AGENTS.md collection logic. Codex remains the only consumer of the original
 * codex-agents-loader.js session-file helpers; this module owns instruction collection.
 *
 * Search rules (project-level, consistent with Codex CLI native behavior):
 *   - Every directory from git root down to cwd is scanned for the first matching file.
 *   - File name priority: AGENTS.override.md > AGENTS.md > CLAUDE.md.
 *
 * Per-provider global instructions are resolved via resolveProviderGlobalHome(); callers that
 * need to override the global home (e.g. tests) pass { globalHomeDir } in options.
 */

import { existsSync, readFileSync, statSync } from 'fs';
import { join, dirname } from 'path';
import { getRealHomeDir } from '../utils/path-utils.js';

/** Max read size per collected file (consistent with Codex CLI). */
export const MAX_AGENTS_MD_BYTES = 32 * 1024;

/** File name search order (first match wins). CLAUDE.md kept as fallback for Claude parity. */
export const AGENTS_FILE_NAMES = ['AGENTS.override.md', 'AGENTS.md', 'CLAUDE.md'];

/**
 * Find the Git repository root directory by walking upwards from startDir.
 * @param {string} startDir - Starting directory
 * @returns {string|null} Git root directory or null
 */
export function findGitRoot(startDir) {
  let currentDir = startDir;
  while (currentDir) {
    if (existsSync(join(currentDir, '.git'))) {
      return currentDir;
    }
    const parentDir = dirname(currentDir);
    if (parentDir === currentDir) {
      break;
    }
    currentDir = parentDir;
  }
  return null;
}

/**
 * Search for the first existing AGENTS.md-family file in a single directory.
 * @param {string} dir - Directory to search
 * @returns {string|null} Found file path or null (empty/zero-byte files skipped)
 */
export function findAgentsFileInDir(dir) {
  for (const fileName of AGENTS_FILE_NAMES) {
    const filePath = join(dir, fileName);
    try {
      if (existsSync(filePath)) {
        const stats = statSync(filePath);
        if (stats.isFile() && stats.size > 0) {
          return filePath;
        }
      }
    } catch (_) {
      // Ignore permission errors, etc.
    }
  }
  return null;
}

/**
 * Read an AGENTS.md file, truncating to MAX_AGENTS_MD_BYTES.
 * @param {string} filePath - File path
 * @returns {string} File content (truncated); '' on read failure.
 */
export function readAgentsFile(filePath) {
  try {
    const content = readFileSync(filePath, 'utf8');
    if (content.length > MAX_AGENTS_MD_BYTES) {
      return content.slice(0, MAX_AGENTS_MD_BYTES);
    }
    return content;
  } catch (_) {
    return '';
  }
}

/**
 * Collect project-level AGENTS.md instructions (git root -> cwd, root-to-leaf order).
 * Does NOT read any global/home directory.
 * @param {string} cwd - Current working directory
 * @returns {string} Merged instruction content ('' if none found)
 */
export function collectProjectAgentsInstructions(cwd) {
  if (!cwd || typeof cwd !== 'string') {
    return '';
  }

  const gitRoot = findGitRoot(cwd);
  const searchRoot = gitRoot || cwd;

  // Collect directories from searchRoot to cwd (root-to-leaf order)
  const directories = [];
  let currentDir = cwd;
  while (currentDir) {
    directories.unshift(currentDir);
    if (currentDir === searchRoot) {
      break;
    }
    const parentDir = dirname(currentDir);
    if (parentDir === currentDir) {
      break;
    }
    currentDir = parentDir;
  }

  const instructions = [];
  let totalBytes = 0;
  for (const dir of directories) {
    if (totalBytes >= MAX_AGENTS_MD_BYTES) {
      break;
    }
    const file = findAgentsFileInDir(dir);
    if (file) {
      const content = readAgentsFile(file);
      if (content.trim()) {
        const relativePath = dir === searchRoot ? '(root)' : dir.replace(searchRoot, '.');
        instructions.push(`# Project Instructions ${relativePath}\n\n${content}`);
        totalBytes += content.length;
      }
    }
  }

  return instructions.join('\n\n---\n\n');
}

/**
 * Resolve the per-provider global instructions home directory.
 * @param {string} provider - 'codex' | 'claude' | 'opencode'
 * @returns {string|null} Global home dir or null for unknown provider
 */
export function resolveProviderGlobalHome(provider) {
  switch (provider) {
    case 'codex': {
      const env = process.env.CODEX_HOME && process.env.CODEX_HOME.trim();
      return env ? env : join(getRealHomeDir(), '.codex');
    }
    case 'claude':
      return join(getRealHomeDir(), '.claude');
    case 'opencode': {
      const xdg = process.env.XDG_CONFIG_HOME && process.env.XDG_CONFIG_HOME.trim();
      return xdg ? join(xdg, 'opencode') : join(getRealHomeDir(), '.config', 'opencode');
    }
    default:
      return null;
  }
}

/**
 * Collect global + project AGENTS.md instructions for a provider.
 * Global instructions appear first, then project (git root -> cwd).
 * @param {string} provider - 'codex' | 'claude' | 'opencode'
 * @param {string} cwd - Current working directory
 * @param {object} [options]
 * @param {string} [options.globalHomeDir] - Override global home (tests); omit to use resolveProviderGlobalHome
 * @returns {string} Merged instruction content ('' if none)
 */
export function collectAgentsInstructionsForProvider(provider, cwd, options = {}) {
  if (!cwd || typeof cwd !== 'string') {
    return '';
  }

  const globalHome = options.globalHomeDir !== undefined
    ? options.globalHomeDir
    : resolveProviderGlobalHome(provider);

  const sections = [];

  if (globalHome) {
    const globalFile = findAgentsFileInDir(globalHome);
    if (globalFile) {
      const content = readAgentsFile(globalFile);
      if (content.trim()) {
        sections.push(`# Global Instructions (${globalFile})\n\n${content}`);
      }
    }
  }

  const projectSection = collectProjectAgentsInstructions(cwd);
  if (projectSection) {
    sections.push(projectSection);
  }

  return sections.join('\n\n---\n\n');
}
