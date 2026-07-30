import { isJavaFqcnCandidate, normalizeFileNavigationTarget, parseFileLinkTarget } from './linkify';
import { bridgeHub } from '../bridge';
import { sendAction } from '../bridge/typed';
import { DOWNSTREAM, UPSTREAM } from '../generated/protocol';

const SAFE_BROWSER_PROTOCOLS = /^(https?|mailto):/i;

/** Regex to detect path traversal: matches ".." as a path segment, not as part of filenames */
const PATH_TRAVERSAL_REGEX = /(^|[\\/])\.\.($|[\\/])/;
// eslint-disable-next-line no-control-regex -- reject control characters in navigation targets
const CONTROL_CHAR_REGEX = /[\u0000-\u001F]/;

type ResolveFilePathCallback = (resolvedPath: string | null) => void;

// RPC 超时。桥接消息丢失、项目关闭或后端异常时,回调永远无法触发会导致 Map 无限增长。
const RESOLVE_FILE_PATH_TIMEOUT_MS = 5000;

/**
 * Legacy bridge RPC wrapper. Sends an event to Java with an optional payload.
 * Kept for backward compatibility where the action is not yet migrated to the
 * UPSTREAM protocol enum. Prefer sendAction(UPSTREAM.xxx, payload) for new code.
 */
export const sendBridgeEvent = (event: string, content = '') => {
  if (typeof window === 'undefined' || !window.sendToJava) {
    return;
  }
  window.sendToJava(JSON.stringify({ type: event, content }));
};

/**
 * Legacy two-arg send-to-Java wrapper.
 * Kept for backward compatibility. Prefer sendAction(UPSTREAM.xxx, payload) for new code.
 */
export const sendToJava = (message: string, payload: unknown = {}) => {
  const payloadStr = typeof payload === 'string' ? payload : JSON.stringify(payload);
  sendBridgeEvent(message, payloadStr);
};

/**
 * [归一化重构] resolve_file_path RPC 已迁移到 bridgeHub.request/response。
 * 旧实现:前端自建 resolveFilePathCallbacks Map + flushPendingCallbacks + 5s timeout +
 *        installResolveFilePathHandler + window.onFilePathResolved 回调。
 * 新实现:bridgeHub.request('resolve_file_path', {path}, {timeoutMs}, {responseType:'file_path.resolved'}) + hub 统一 correlation + timeout。
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

  // 经 hub 的 request/response RPC 通道。
  //   type         = 上行 action 'resolve_file_path'(后端 ResolveFilePathActionHandler 命中此名);
  //   responseType = 下行事件 'file_path.resolved'(后端回包,hub 按此 + requestId 匹配)。
  // 两者不可混用 —— 早先误把下行事件名 'file_path.resolve' 当请求 type,后端 dispatcher miss、
  // 回包永不到达、前端 5s 超时回 null,文件链接悬停解析静默失效。
  bridgeHub
    .request<{ path?: string; resolvedPath?: string | null }>(
      UPSTREAM.RESOLVE_FILE_PATH,
      { path: normalizedPath },
      { timeoutMs: RESOLVE_FILE_PATH_TIMEOUT_MS, responseType: DOWNSTREAM.FILE_PATH_RESOLVED },
    )
    .then((result) => {
      callback(result?.resolvedPath ?? null);
    })
    .catch(() => {
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
  if (
    resolvedLineStart !== undefined &&
    Number.isFinite(resolvedLineStart) &&
    resolvedLineStart > 0
  ) {
    path =
      resolvedLineEnd !== undefined && Number.isFinite(resolvedLineEnd) && resolvedLineEnd > 0
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

export const showDiff = (
  filePath: string,
  oldContent: string,
  newContent: string,
  title?: string,
) => {
  if (!isValidMutatingPath(filePath)) {
    return;
  }
  sendAction(UPSTREAM.SHOW_DIFF, { filePath, oldContent, newContent, title });
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
  status: 'A' | 'M',
) => {
  // Security: Validate file path (defense-in-depth, backend also validates)
  if (!isValidMutatingPath(filePath)) {
    return;
  }
  sendAction(UPSTREAM.SHOW_EDITABLE_DIFF, { filePath, operations, status });
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
  operations: Array<{ oldString: string; newString: string; replaceAll?: boolean }>,
) => {
  // Security: Validate file path (defense-in-depth, backend also validates)
  if (!isValidMutatingPath(filePath)) {
    return;
  }
  sendAction(UPSTREAM.UNDO_FILE_CHANGES, { filePath, status, operations });
};

// RPC 超时:HTTP 拉取 + 多候选回退(最多 3 候选 × 各自网络往返),比本地 file resolve 宽。
const FETCH_PROVIDER_MODELS_TIMEOUT_MS = 30000;



/**
 * 拉取第三方/代理 OpenAI 兼容 models 列表(RPC,业务逻辑下沉后端)。
 *
 * <p>前端只做入口:传 baseUrl/apiKey,后端 {@code ModelFetchService} 构造候选 URL +
 * HTTP GET + {data:[{id}]} 解析。成功返回 {@code {models:[...]}},失败返回
 * {@code {error:'...'}}(引导对话框展示后允许用户改用手动输入模型名)。
 *
 * <p>对称 {@link resolveFilePathWithCallback} 的 request/response RPC 通道:
 * 上行 {@code fetch_provider_models}(后端 FetchProviderModelsActionHandler 命中),
 * 下行 {@code provider.models_fetched}(携带 {@code __requestId} 供 hub 路由 Promise)。
 */
export const fetchProviderModels = (
  params: {
    baseUrl: string;
    apiKey?: string;
    isFullUrl?: boolean;
    modelsUrlOverride?: string;
  },
): Promise<{ models?: string[]; error?: string }> => {
  return bridgeHub
    .request<{ models?: string[]; error?: string }>(
      UPSTREAM.FETCH_PROVIDER_MODELS,
      {
        baseUrl: params.baseUrl,
        apiKey: params.apiKey,
        isFullUrl: params.isFullUrl ?? false,
        modelsUrlOverride: params.modelsUrlOverride,
      },
      {
        timeoutMs: FETCH_PROVIDER_MODELS_TIMEOUT_MS,
        responseType: DOWNSTREAM.PROVIDER_MODELS_FETCHED,
      },
    )
    .then((result) => result ?? {})
    .catch(() => ({ error: 'timeout' }));
};

// ── MCP 市场 (Smithery Registry) RPC ──
// search/detail 走 RPC Promise(__requestId 路由);Smithery Key 配置走广播
// (GET/SET_SMITHERY_API_KEY → CONFIG_SMITHERY_API_KEY,无 __requestId,组件内 subscribeEvent)。
const MCP_MARKET_TIMEOUT_MS = 60000;

export interface SmitheryServerSummary {
  id?: string;
  qualifiedName?: string;
  namespace?: string;
  slug?: string;
  displayName?: string;
  description?: string;
  iconUrl?: string;
  homepage?: string;
  verified?: boolean;
  remote?: boolean;
  isDeployed?: boolean;
  useCount?: number;
}





export interface McpMarketDetailResult {
  namespace?: string;
  slug?: string;
  qualifiedName?: string;
  displayName?: string;
  description?: string;
  iconUrl?: string;
  homepage?: string;
  readme?: string;
  verified?: boolean;
  remote?: boolean;
  useCount?: number;
  connection?: {
    mcpUrl?: string;
    url?: string;
    deploymentUrl?: string;
    command?: string;
    args?: string[] | string;
    env?: Record<string, string>;
  };
  error?: string;
  errorCode?: string;
}

/**
 * 搜索 Smithery Registry MCP 服务器(RPC)。
 * 后端 SmitheryMarketService.searchServers → MCP_MARKET_LIST(带 __requestId)。
 * 失败返回 {error, errorCode}(MISSING_API_KEY/INVALID_API_KEY/NETWORK_ERROR/HTTP_xxx)。
 */
export const searchMcpMarket = (
  query: string,
  page = 1,
  pageSize = 20,
): Promise<{
  servers?: SmitheryServerSummary[];
  pagination?: { page?: number; pageSize?: number; total?: number; totalPages?: number; nextCursor?: string };
  error?: string;
  errorCode?: string;
}> => {
  return bridgeHub
    .request<{
      servers?: SmitheryServerSummary[];
      pagination?: { page?: number; pageSize?: number; total?: number; totalPages?: number; nextCursor?: string };
      error?: string;
      errorCode?: string;
    }>(
      UPSTREAM.SEARCH_MCP_MARKET,
      { query, page, pageSize },
      { timeoutMs: MCP_MARKET_TIMEOUT_MS, responseType: DOWNSTREAM.MCP_MARKET_LIST },
    )
    .then((result) => result ?? {})
    .catch(() => ({ error: 'timeout', errorCode: 'TIMEOUT' }));
};

/**
 * 获取单个 Smithery server 详情 + 防御性连接配置(RPC)。
 * 后端 SmitheryMarketService.getServerDetail → MCP_MARKET_DETAIL(带 __requestId)。
 * connection 可能为空(详情端点未含连接字段)→ 前端引导手动配置。
 */
export const getMcpMarketDetail = (
  namespace: string,
  slug: string,
): Promise<McpMarketDetailResult> => {
  return bridgeHub
    .request<McpMarketDetailResult>(
      UPSTREAM.GET_MCP_MARKET_DETAIL,
      { namespace, slug },
      { timeoutMs: MCP_MARKET_TIMEOUT_MS, responseType: DOWNSTREAM.MCP_MARKET_DETAIL },
    )
    .then((result) => result ?? {})
    .catch(() => ({ error: 'timeout', errorCode: 'TIMEOUT' }));
};

// ── Skills 市场 (GitHub 仓库 tarball) RPC ──
// list/install 走 RPC Promise(__requestId 路由)。
// provider 由后端从 HandlerContext 读(不前端传,防伪造);scope 按后端归一(Codex=user/repo,其余 global/local)。
// 安装含 tarball 下载+解压,超时给 60s(MCP 市场纯 API 搜索 30s 不够)。
const SKILL_MARKET_TIMEOUT_MS = 60000;

export interface SkillMarketSourceInfo {
  id: string;
  label: string;
  owner: string;
  repo: string;
}

export interface SkillMarketItem {
  name: string;
  path: string;
}

export interface ListSkillMarketResult {
  sources?: SkillMarketSourceInfo[];
  source?: string;
  sourceLabel?: string;
  skills?: SkillMarketItem[];
  error?: string;
  errorCode?: string;
}



/**
 * 列出某 Skills 市场源的 skills(RPC)。
 * 后端 SkillMarketService.listMarketSkills → SKILL_MARKET_LIST(带 __requestId)。
 * source 默认 anthropics;失败返回 {error, errorCode}。
 */
export const listSkillMarket = (source = 'anthropics'): Promise<ListSkillMarketResult> => {
  return bridgeHub
    .request<ListSkillMarketResult>(
      UPSTREAM.LIST_SKILL_MARKET,
      { source },
      { timeoutMs: SKILL_MARKET_TIMEOUT_MS, responseType: DOWNSTREAM.SKILL_MARKET_LIST },
    )
    .then((result) => result ?? {})
    .catch(() => ({ error: 'timeout', errorCode: 'TIMEOUT' }));
};

/**
 * 从 Skills 市场安装 skill(RPC)。
 * 后端 SkillMarketService.installSkill → SKILL_MARKET_INSTALL_RESULT(带 __requestId)。
 * 失败返回 {success:false, error, errorCode}
 * (UNKNOWN_SOURCE/INVALID_SKILL_NAME/HASH_MISMATCH/HTTP_404/HTTP_403/NETWORK_ERROR/PARSE_ERROR/INSTALL_FAILED)。
 */
export const installSkillFromMarket = (
  source: string,
  skillPath: string,
  scope: string,
): Promise<{
  success?: boolean;
  skillName?: string;
  source?: string;
  hash?: string;
  importResult?: Record<string, unknown>;
  error?: string;
  errorCode?: string;
}> => {
  return bridgeHub
    .request<{
      success?: boolean;
      skillName?: string;
      source?: string;
      hash?: string;
      importResult?: Record<string, unknown>;
      error?: string;
      errorCode?: string;
    }>(
      UPSTREAM.INSTALL_SKILL_FROM_MARKET,
      { source, skillPath, scope },
      { timeoutMs: SKILL_MARKET_TIMEOUT_MS, responseType: DOWNSTREAM.SKILL_MARKET_INSTALL_RESULT },
    )
    .then((result) => result ?? {})
    .catch(() => ({ error: 'timeout', errorCode: 'TIMEOUT' }));
};

export interface SkillMarketDetailResult {
  name?: string;
  description?: string;
  license?: string;
  compatibility?: string;
  allowedTools?: string;
  userInvocable?: boolean;
  paths?: string[];
  path?: string;
  source?: string;
  sourceLabel?: string;
  error?: string;
  errorCode?: string;
}

/**
 * 获取单个 skill 详情(RPC)。
 * 后端 SkillMarketService.getSkillMarketDetail → raw 下载单个 SKILL.md → frontmatter 解析 →
 * SKILL_MARKET_DETAIL(带 __requestId)。列表不展示简介(走 Contents API 快速路径只返 name/path),
 * 详情按需拉取(用户点击,单文件请求不撞 GitHub 60 req/h 限流)。
 * 失败返回 {error, errorCode}(UNKNOWN_SOURCE/INVALID_SKILL_NAME/HTTP_404/HTTP_403/NETWORK_ERROR/TIMEOUT/PARSE_ERROR)。
 */
export const getSkillMarketDetail = (
  source: string,
  skillPath: string,
): Promise<SkillMarketDetailResult> => {
  return bridgeHub
    .request<SkillMarketDetailResult>(
      UPSTREAM.GET_SKILL_MARKET_DETAIL,
      { source, skillPath },
      { timeoutMs: SKILL_MARKET_TIMEOUT_MS, responseType: DOWNSTREAM.SKILL_MARKET_DETAIL },
    )
    .then((result) => result ?? {})
    .catch(() => ({ error: 'timeout', errorCode: 'TIMEOUT' }));
};
