// @ts-check
/**
 * Path safety utilities for permission checks.
 * Handles path rewriting (/tmp → project root) and dangerous path detection.
 */
import { basename, resolve, sep } from 'path';
import { getRealHomeDir } from './utils/path-utils.js';

/** @type {readonly string[]} */
const TEMP_PATH_PREFIXES = ['/tmp', '/var/tmp', '/private/tmp'];

/**
 * @returns {string}
 */
export function getProjectRoot() {
  return process.env.IDEA_PROJECT_PATH || process.env.PROJECT_PATH || process.cwd();
}

/**
 * Rewrite tool input paths from /tmp to the project root directory.
 * @param {string} toolName - Tool name (for logging)
 * @param {Record<string, unknown> | null | undefined} input - Tool parameters (mutated in place)
 * @returns {{ changed: boolean }} - Whether any paths were rewritten
 */
export function rewriteToolInputPaths(toolName, input) {
  const projectRoot = getProjectRoot();
  if (!projectRoot || !input || typeof input !== 'object') {
    return { changed: false };
  }

  /** @type {string[]} */
  const prefixes = [...TEMP_PATH_PREFIXES];
  if (process.env.TMPDIR) {
    prefixes.push(process.env.TMPDIR);
  }

  /** @type {Array<{ from: string; to: string }>} */
  const rewrites = [];

  /**
   * @param {unknown} pathValue
   * @returns {unknown}
   */
  const rewritePath = (pathValue) => {
    if (typeof pathValue !== 'string') return pathValue;
    const matchedPrefix = prefixes.find(prefix => prefix && pathValue.startsWith(prefix));
    if (!matchedPrefix) return pathValue;

    let relative = pathValue.slice(matchedPrefix.length).replace(/^\/+/, '');
    if (!relative) {
      relative = basename(pathValue);
    }
    const sanitized = resolve(projectRoot, relative);

    // Verify the resolved path is still within the project root
    const resolvedRoot = resolve(projectRoot);
    if (!sanitized.startsWith(resolvedRoot + sep) && sanitized !== resolvedRoot) {
      console.error(`[PERMISSION][PATH_REWRITE_BLOCKED] Rewritten path escaped project root: ${pathValue} → ${sanitized} (root: ${resolvedRoot})`);
      return pathValue;
    }

    rewrites.push({ from: pathValue, to: sanitized });
    return sanitized;
  };

  /**
   * @param {unknown} value
   * @returns {void}
   */
  const traverse = (value) => {
    if (!value) return;
    if (Array.isArray(value)) {
      value.forEach(traverse);
      return;
    }
    if (typeof value === 'object') {
      const obj = /** @type {Record<string, unknown>} */ (value);
      if (typeof obj.file_path === 'string') {
        obj.file_path = rewritePath(obj.file_path);
      }
      for (const key of Object.keys(obj)) {
        const child = obj[key];
        if (child && typeof child === 'object') {
          traverse(child);
        }
      }
    }
  };

  traverse(input);

  if (rewrites.length > 0) {
    console.error(`[PERMISSION] Rewrote paths for ${toolName}:`, JSON.stringify(rewrites));
  }

  return { changed: rewrites.length > 0 };
}

// (removed 2026-08-03) acceptEdits CWD 校验块(checkPathSafetyForAutoEdit / isAcceptEditsAllowed /
// isPathInWorkingDirectory / DANGEROUS_AUTO_EDIT_FILES|DIRS)—— 全仓 grep 确认零活跃调用方:
// acceptEdits 下 EDIT_TOOLS 直接 YIELD_TO_SDK(见 permission-handler.js canUseTool),不经此 Node
// 侧校验;真正的 acceptEdits 约束在后端 mode 映射。audit P3-SEC 低危。

/**
 * 敏感凭证文件名/片段(SEC-05 兜底):不依赖 home 目录展开,直接匹配命令/路径中的凭证引用。
 * 覆盖 $HOME/${HOME} 未展开、/home/$USER、相对路径等所有变体——只要 Bash 命令触及这些凭证即危险。
 * 仅列高敏感凭证文件名,避免对普通项目文件误伤。
 */
const SENSITIVE_CREDENTIAL_TOKENS = [
  'id_rsa', 'id_ecdsa', 'id_ed25519', 'id_dsa', // SSH 私钥
  '.aws/credentials', '.aws\\credentials',
  '.kube/config', '.kube\\config',
  '.docker/config.json', '.docker\\config.json',
  '.npmrc', '.pypirc', // 包管理器凭证
];

/**
 * Check whether a file path matches any known dangerous pattern.
 * @param {string} filePath - The path to check
 * @returns {boolean} - true if the path is dangerous and should be denied
 */
export function isDangerousPath(filePath) {
  if (!filePath) return false;

  const userHomeDir = getRealHomeDir();
  const isWindows = process.platform === 'win32';

  /** @type {string[]} */
  const dangerousPatterns = [
    // Unix/macOS system paths
    '/etc/',
    '/System/',
    '/usr/',
    '/bin/',
    '/sbin/',
    // User-sensitive directories (credentials, config)
    `${userHomeDir}/.ssh/`,
    `${userHomeDir}/.aws/`,
    `${userHomeDir}/.gnupg/`,
    `${userHomeDir}/.kube/`,
    `${userHomeDir}/.docker/`,
    `${userHomeDir}/.config/`,
    `${userHomeDir}/.local/`,
    `${userHomeDir}/.claude/.credentials.json`,
  ];

  if (isWindows) {
    dangerousPatterns.push(
      'C:\\Windows\\',
      'C:\\Program Files\\',
      'C:\\Program Files (x86)\\',
      `${userHomeDir}\\.ssh\\`,
      `${userHomeDir}\\.aws\\`,
      `${userHomeDir}\\.gnupg\\`,
      `${userHomeDir}\\.kube\\`,
      `${userHomeDir}\\.docker\\`,
      `${userHomeDir}\\AppData\\`,
      `${userHomeDir}\\.config\\`,
      `${userHomeDir}\\.local\\`,
    );
  }

  // Security (K) + SEC-05: expand shell-style home references so dangerous-path checks cover
  // Bash commands like "cat $HOME/.ssh/id_rsa" or "cat ~/$USER/.ssh/key", not only absolute paths.
  // Previously only ~ was expanded, leaving $HOME/${HOME}/$USER references unmatched (the command
  // string contained neither ~ nor the literal home path, so it bypassed the hard-deny check).
  let expandedPath = String(filePath);
  if (userHomeDir) {
    expandedPath = expandedPath.split('~/').join(`${userHomeDir}/`);
    if (isWindows) {
      expandedPath = expandedPath.split('~\\').join(`${userHomeDir}\\`);
    }
    // 展开 $HOME/${HOME}(Unix)/$USERPROFILE/${USERPROFILE}(Windows)为真实 home
    const homeVars = isWindows ? ['USERPROFILE', 'HOME'] : ['HOME', 'USERPROFILE'];
    for (const name of homeVars) {
      expandedPath = expandedPath.split(`$${name}`).join(userHomeDir);
      expandedPath = expandedPath.split(`\${${name}}`).join(userHomeDir);
    }
    if (isWindows) {
      expandedPath = expandedPath.split('%USERPROFILE%').join(userHomeDir);
    }
    // $USER/${USER}:从 home 路径末段反推用户名(Unix /home/alice、macOS /Users/alice、Win C:\Users\alice)
    const homeUser = userHomeDir.split(sep).pop();
    if (homeUser) {
      expandedPath = expandedPath.split('$USER').join(homeUser);
      expandedPath = expandedPath.split('${USER}').join(homeUser);
    }
  }
  const normalizedPath = isWindows ? expandedPath.toLowerCase() : expandedPath;
  for (const pattern of dangerousPatterns) {
    const normalizedPattern = isWindows ? pattern.toLowerCase() : pattern;
    if (normalizedPath.includes(normalizedPattern)) {
      return true;
    }
  }
  // SEC-05 兜底:不依赖 home 展开的凭证文件名/片段直配。覆盖 $USER 未展开或异形引用——只要命令/路径
  // 触及 SSH 私钥、云凭证、包管理器凭证即判危险(fail-safe,宁可误拒不放过)。
  const normalizedTokens = isWindows
    ? SENSITIVE_CREDENTIAL_TOKENS.map((t) => t.toLowerCase())
    : SENSITIVE_CREDENTIAL_TOKENS;
  for (const token of normalizedTokens) {
    if (normalizedPath.includes(token)) {
      return true;
    }
  }

  return false;
}

/**
 * Collect all filesystem path-like strings from a tool input, including Bash command
 * strings, so dangerous-path checks cover more than just file_path/path. (Security K)
 * @param {string} toolName - tool name (reserved for future per-tool extraction)
 * @param {Record<string, unknown> | null | undefined} input - tool input object
 * @returns {string[]} candidate path/command strings to screen with isDangerousPath
 */
export function collectToolInputPaths(toolName, input) {
  /** @type {string[]} */
  const out = [];
  if (!input || typeof input !== 'object') return out;
  /** @param {unknown} v */
  const push = (v) => { if (typeof v === 'string' && v) out.push(v); };
  push(input.file_path);
  push(input.path);
  push(input.notebook_path);
  if (Array.isArray(input.edits)) {
    for (const edit of input.edits) {
      if (edit && typeof edit === 'object') {
        const e = /** @type {Record<string, unknown>} */ (edit);
        push(e.file_path);
        push(e.path);
      }
    }
  }
  // Bash / shell command strings carry their target paths inline.
  push(input.command);
  return out;
}
