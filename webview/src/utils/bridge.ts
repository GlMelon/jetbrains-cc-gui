import { isJavaFqcnCandidate, normalizeFileNavigationTarget, parseFileLinkTarget } from './linkify';
import { bridgeHub } from '../bridge';
import { sendAction } from '../bridge/typed';
import { DOWNSTREAM, UPSTREAM } from '../generated/protocol';

const SAFE_BROWSER_PROTOCOLS = /^(https?|mailto):/i;

/** Regex to detect path traversal: matches ".." as a path segment, not as part of filenames */
const PATH_TRAVERSAL_REGEX = /(^|[\\/])\.\.($|[\\/])/;
const CONTROL_CHAR_REGEX = /[\u0000-\u001F]/;

type ResolveFilePathCallback = (resolvedPath: string | null) => void;

// RPC 超时。桥接消息丢失、项目关闭或后端异常时,回调永远无法触发会导致 Map 无限增长。
const RESOLVE_FILE_PATH_TIMEOUT_MS = 5000;

/**
 * [归一化重构] resolve_file_path RPC 已迁移到 bridgeHub.request/response。
 * 旧实现:前端自建 resolveFilePathCallbacks Map + flushPendingCallbacks + 5s timeout +
 *        installResolveFilePathHandler + window.onFilePathResolved 回调。
 * 新实现:bridgeHub.request('file_path.resolve', {path}, {timeoutMs}) + hub 统一 correlation + timeout。
 * 后端 FileHandler 以 dispatchEvent("file_path.resolved", {path, resolvedPath, __requestId}) 回包,
 * hub 按 requestId 匹配并 resolve Promise。
 *
 * 多 caller 合并:原实现中同一 path 的多个 caller 共享一次后端请求。新实现每个 caller 独立 request()
 * (各自 requestId),后端重复处理——file resolve 是幂等的,性能影响可忽略。
 */
export const resolveFilePathWithCallback = (
  filePath: string,
  callback: ResolveFilePathCallback,
): void => {
  if (!filePath) {
    callback(null);
    return;
  }
  const normalizedPath = normalizeFileNavigationTarget(filePath);
  if (!normalizedPath || !isValidOpenFileTarget(normalizedPath)) {
    callback(null);
    return;
  }

  // 经 hub 的 request/response RPC 通道。后端以 file_path.resolved 回包,hub 按 requestId 匹配。
  bridgeHub.request<{ path?: string; resolvedPath?: string | null }>(
    DOWNSTREAM.FILE_PATH_RESOLVE,
    { path: normalizedPath },
    { timeoutMs: RESOLVE_FILE_PATH_TIMEOUT_MS, responseType: DOWNSTREAM.FILE_PATH_RESOLVED },
  ).then((result) => {
    callback(result?.resolvedPath ?? null);
  }).catch(() => {
    // 超时或 bridge reset。回调 null(与旧实现超时行为一致)。
    callback(null);
  });
};

/**
 * Validate mutating file paths don't contain traversal patterns.
 * Defense-in-depth: backend also validates using canonical paths.
 */
const isValidMutatingPath = (filePath: string): boolean => {
  if (!filePath) return false;
  let decodedPath: string;
  try {
    decodedPath = decodeURIComponent(filePath);
  } catch {
    console.debug('[bridge] isValidMutatingPath: decodeURIComponent failed for:', filePath);
    return false;
  }
  if (CONTROL_CHAR_REGEX.test(filePath) || CONTROL_CHAR_REGEX.test(decodedPath)) {
    return false;
  }
  return !PATH_TRAVERSAL_REGEX.test(filePath) && !PATH_TRAVERSAL_REGEX.test(decodedPath);
};

/**
 * Navigation-only file opening supports relative paths like ../foo.ts and path:line.
 */
const isValidOpenFileTarget = (filePath: string): boolean => {
  if (!filePath) return false;
  let decodedPath: string;
  try {
    decodedPath = decodeURIComponent(filePath);
  } catch {
    console.debug('[bridge] isValidOpenFileTarget: decodeURIComponent failed for:', filePath);
    return false;
  }

  return !CONTROL_CHAR_REGEX.test(filePath) && !CONTROL_CHAR_REGEX.test(decodedPath);
};

const isValidFqcn = (className: string): boolean => {
  const trimmed = className?.trim();
  if (!trimmed || CONTROL_CHAR_REGEX.test(trimmed)) {
    return false;
  }

  return isJavaFqcnCandidate(trimmed);
};

export const resolveFilePath = (filePath?: string) => {
  if (!filePath) {
    return;
  }
  const normalizedPath = normalizeFileNavigationTarget(filePath);
  if (!normalizedPath || !isValidOpenFileTarget(normalizedPath)) {
    return;
  }
  sendAction(UPSTREAM.RESOLVE_FILE_PATH, normalizedPath);
};

export const openFile = (filePath?: string, lineStart?: number, lineEnd?: number) => {
  if (!filePath) {
    return;
  }
  const normalizedPath = normalizeFileNavigationTarget(filePath);
  if (!normalizedPath || !isValidOpenFileTarget(normalizedPath)) {
    return;
  }

  const parsedTarget = parseFileLinkTarget(normalizedPath);
  const pathOnly = parsedTarget?.path ?? normalizedPath;
  const resolvedLineStart = lineStart ?? parsedTarget?.lineStart;
  const resolvedLineEnd = lineEnd ?? parsedTarget?.lineEnd;

  let path = pathOnly;
  if (resolvedLineStart !== undefined && Number.isFinite(resolvedLineStart) && resolvedLineStart > 0) {
    path = (resolvedLineEnd !== undefined && Number.isFinite(resolvedLineEnd) && resolvedLineEnd > 0)
      ? `${pathOnly}:${resolvedLineStart}-${resolvedLineEnd}`
      : `${pathOnly}:${resolvedLineStart}`;
  }
  sendAction(UPSTREAM.OPEN_FILE, path);
};

export const openClass = (className?: string) => {
  const trimmed = className?.trim();
  if (!trimmed || !isValidFqcn(trimmed)) {
    return;
  }

  sendAction(UPSTREAM.OPEN_CLASS, trimmed);
};

export const openBrowser = (url?: string) => {
  if (!url) {
    return;
  }
  // Defense-in-depth: only allow http, https, and mailto protocols.
  // file: and javascript: are explicitly rejected even though markdown
  // sanitization should strip them before they reach this point.
  if (!SAFE_BROWSER_PROTOCOLS.test(url)) {
    return;
  }
  sendAction(UPSTREAM.OPEN_BROWSER, url);
};

export const refreshFile = (filePath: string) => {
  if (!filePath) return;
  sendAction(UPSTREAM.REFRESH_FILE, { filePath });
};

export const showDiff = (filePath: string, oldContent: string, newContent: string, title?: string) => {
  if (!isValidMutatingPath(filePath)) {
    return;
  }
  sendAction(UPSTREAM.SHOW_DIFF, { filePath, oldContent, newContent, title });
};

export const showMultiEditDiff = (
  filePath: string,
  edits: Array<{ oldString: string; newString: string; replaceAll?: boolean }>,
  currentContent?: string
) => {
  if (!isValidMutatingPath(filePath)) {
    return;
  }
  sendAction(UPSTREAM.SHOW_MULTI_EDIT_DIFF, { filePath, edits, currentContent });
};

/**
 * Show editable diff view for a file
 * Opens IDEA's native diff view where user can selectively accept/reject changes
 * @param filePath - Absolute path to the file
 * @param operations - Array of edit operations
 * @param status - File status: 'A' (added) or 'M' (modified)
 */
export const showEditableDiff = (
  filePath: string,
  operations: Array<{ oldString: string; newString: string; replaceAll?: boolean }>,
  status: 'A' | 'M'
) => {
  // Security: Validate file path (defense-in-depth, backend also validates)
  if (!isValidMutatingPath(filePath)) {
    return;
  }
  sendAction(UPSTREAM.SHOW_EDITABLE_DIFF, { filePath, operations, status });
};

/**
 * Show interactive diff view with Apply/Reject buttons
 * Based on the official Claude Code JetBrains plugin implementation
 * @param filePath - Absolute path to the file
 * @param newFileContents - The proposed new content for the file
 * @param tabName - Optional name for the diff tab
 * @param isNewFile - Whether this is a new file (no original content)
 */
export const showInteractiveDiff = (
  filePath: string,
  newFileContents: string,
  tabName?: string,
  isNewFile?: boolean
) => {
  if (!isValidMutatingPath(filePath)) {
    return;
  }
  sendAction(UPSTREAM.SHOW_INTERACTIVE_DIFF, { filePath, newFileContents, tabName, isNewFile: isNewFile ?? false });
};

/**
 * Rewind files to a specific user message state
 * @param sessionId - Session ID
 * @param userMessageId - User message UUID to rewind to
 */
export const rewindFiles = (sessionId: string, userMessageId: string) => {
  sendAction(UPSTREAM.REWIND_FILES, { sessionId, userMessageId });
};

/**
 * Undo changes for a single file
 * @param filePath - Absolute path to the file
 * @param status - File status: 'A' (added) or 'M' (modified)
 * @param operations - Array of edit operations to reverse
 */
export const undoFileChanges = (
  filePath: string,
  status: 'A' | 'M',
  operations: Array<{ oldString: string; newString: string; replaceAll?: boolean }>
) => {
  // Security: Validate file path (defense-in-depth, backend also validates)
  if (!isValidMutatingPath(filePath)) {
    return;
  }
  sendAction(UPSTREAM.UNDO_FILE_CHANGES, { filePath, status, operations });
};
