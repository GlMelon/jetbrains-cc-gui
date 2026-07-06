import { useEffect, useRef } from 'react';
import {
  isValidPermissionMode,
  strip1MContextSuffix,
} from '../../components/ChatInputBox/types';
import type { CodexFastMode, PermissionMode, ReasoningEffort } from '../../components/ChatInputBox/types';
import { REASONING_EFFORT } from '../../generated/protocol';
import { getModelRegistrySnapshot, resolveClaudeModelId, subscribeModelRegistry } from '../../utils/modelRegistry';

const STORAGE_KEY = 'model-selection-state';
// SSOT(C2):reasoning effort 值域来自后端枚举生成,不再手写(原与 ClaudeRole.java:127 重复)
const REASONING_VALUES: readonly ReasoningEffort[] = Object.values(REASONING_EFFORT);

const isReasoningEffort = (value: unknown): value is ReasoningEffort =>
  typeof value === 'string' && (REASONING_VALUES as readonly string[]).includes(value);

const CODEX_FAST_MODE_VALUES = ['normal', 'fast'] as const;

const isCodexFastMode = (value: unknown): value is CodexFastMode =>
  typeof value === 'string' && (CODEX_FAST_MODE_VALUES as readonly string[]).includes(value);

export interface UseModelStatePersistenceOptions {
  // Cross-slice load setters (run once on mount)
  setCurrentProvider: (value: string) => void;
  setSelectedClaudeModel: (value: string) => void;
  setSelectedCodexModel: (value: string) => void;
  setSelectedOpenCodeModel: (value: string) => void;
  setClaudePermissionMode: (value: PermissionMode) => void;
  setCodexPermissionMode: (value: PermissionMode) => void;
  setPermissionMode: (value: PermissionMode) => void;
  setLongContextEnabled: (value: boolean) => void;
  setReasoningEffort: (value: ReasoningEffort) => void;
  setCodexFastMode: (value: CodexFastMode) => void;
  // Cross-slice save deps (re-saves on any change)
  currentProvider: string;
  selectedClaudeModel: string;
  selectedCodexModel: string;
  selectedOpenCodeModel: string;
  claudePermissionMode: PermissionMode;
  codexPermissionMode: PermissionMode;
  longContextEnabled: boolean;
  reasoningEffort: ReasoningEffort;
  codexFastMode: CodexFastMode;
}

/**
 * Two effects for persisting cross-slice provider/model state to localStorage:
 *  1. On mount: hydrate local UI preferences from localStorage.
 *  2. On change: re-save the snapshot to localStorage.
 *
 * Save uses `JSON.stringify` of the persisted keys; load applies
 * defensive validation (role model allowlist, permission mode allowlist,
 * reasoning effort allowlist) before invoking the slice setters.
 */
export function useModelStatePersistence(options: UseModelStatePersistenceOptions) {
  const {
    setCurrentProvider,
    setSelectedClaudeModel,
    setSelectedCodexModel,
    setSelectedOpenCodeModel,
    setClaudePermissionMode,
    setCodexPermissionMode,
    setPermissionMode,
    setLongContextEnabled,
    setReasoningEffort,
    setCodexFastMode,
    currentProvider,
    selectedClaudeModel,
    selectedCodexModel,
    selectedOpenCodeModel,
    claudePermissionMode,
    codexPermissionMode,
    longContextEnabled,
    reasoningEffort,
    codexFastMode,
  } = options;

  // 持久化的 Claude 模型 id(已去 [1m] 后缀)。mount 时 registry 可能尚未异步加载
  // (DEFAULT 仅含 4 个内置 role),自定义模型会被 resolve 暂时回退成 role;这里记下
  // 原始 id,在 registry 真正加载后重校验一次以恢复自定义模型。revalidatedRef 保证
  // 整个生命周期只成功应用一次,避免后续 registry 变更时的无谓 setState。
  const pendingClaudeModelRef = useRef<string | null>(null);
  const revalidatedRef = useRef(false);

    // Hydrate local UI preferences from localStorage (mount only).
  // Setters are stable; deps left empty to ensure single execution.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      // Per-tab restore (issue #1353): when the Java backend has loaded a saved
      // session for this specific tab, it injects __INITIAL_TAB_PROVIDER__ /
      // __INITIAL_TAB_MODEL__ into the HTML before React boots. Those values
      // win over the global localStorage snapshot, which is shared across every
      // tab in the JCEF process and would otherwise cause every CC tab on
      // restart to be set to whichever provider was last saved by ANY tab.
      const initialTabProvider = typeof window.__INITIAL_TAB_PROVIDER__ === 'string'
        ? window.__INITIAL_TAB_PROVIDER__.trim()
        : '';
      const initialTabModel = typeof window.__INITIAL_TAB_MODEL__ === 'string'
        ? window.__INITIAL_TAB_MODEL__.trim()
        : '';
      const hasBackendProvider = initialTabProvider === 'claude' || initialTabProvider === 'codex';
      const hasBackendModel = initialTabModel.length > 0;

      let restoredProvider = 'claude';
      let restoredClaudePermissionMode: PermissionMode = 'default';
      let restoredCodexPermissionMode: PermissionMode = 'default';

      // Model validation helpers — close over the restored* lets so both
      // branches (saved localStorage / fresh backend-only) share the same logic
      // and each getCustomModels localStorage read happens at most once.
      const applyClaudeModel = (modelId: string) => {
        const normalized = normalizeClaudeModelId(strip1MContextSuffix(modelId));
        const customs = getCustomModels('claude-custom-models');
        if (CLAUDE_MODELS.find(m => m.id === normalized) || customs.find(m => m.id === normalized)) {
          restoredClaudeModel = normalized;
          setSelectedClaudeModel(normalized);
        }
      };
      const applyCodexModel = (modelId: string) => {
        const customs = getCustomModels('codex-custom-models');
        if (CODEX_MODELS.find(m => m.id === modelId) || customs.find(m => m.id === modelId)) {
          restoredCodexModel = modelId;
          setSelectedCodexModel(modelId);
        }
      };

      if (saved) {
        const state = JSON.parse(saved);

        if (['claude', 'codex', 'opencode'].includes(state.provider)) {
          restoredProvider = state.provider;
          setCurrentProvider(state.provider);
        }

        if (isValidPermissionMode(state.claudePermissionMode)) {
          restoredClaudePermissionMode = state.claudePermissionMode;
        }
        if (isValidPermissionMode(state.codexPermissionMode)) {
          restoredCodexPermissionMode = state.codexPermissionMode;
        }

        if (typeof state.longContextEnabled === 'boolean') {
          setLongContextEnabled(state.longContextEnabled);
        }

        if (isReasoningEffort(state.reasoningEffort)) {
          setReasoningEffort(state.reasoningEffort);
        }
        if (isCodexFastMode(state.codexFastMode)) {
          setCodexFastMode(state.codexFastMode);
        }

        const strippedClaudeModel = strip1MContextSuffix(state.claudeModel);
        if (strippedClaudeModel) {
          // resolveClaudeModelId 先查 registry:自定义模型保留原 id,registry 未收录时
          // 回退到 role 归一化。mount 时 registry 多为 DEFAULT,自定义模型会被暂回退;
          // pendingClaudeModelRef 记下原 id,交由下方 registry 重校验 effect 恢复。
          pendingClaudeModelRef.current = strippedClaudeModel;
          setSelectedClaudeModel(resolveClaudeModelId(strippedClaudeModel));
        }

        if (typeof state.codexModel === 'string' && state.codexModel.trim()) {
          setSelectedCodexModel(state.codexModel);
        }

        if (typeof state.opencodeModel === 'string' && state.opencodeModel.trim()) {
          setSelectedOpenCodeModel(state.opencodeModel);
        }
      }

      const initialPermissionMode: PermissionMode = restoredProvider === 'codex'
        ? restoredCodexPermissionMode
        : restoredClaudePermissionMode;
      setClaudePermissionMode(restoredClaudePermissionMode);
      setCodexPermissionMode(restoredCodexPermissionMode);
      setPermissionMode(initialPermissionMode);

    } catch {
      // Failed to load model selection state — fall back to defaults already
      // set by individual slice hooks.
    }
  }, []);

  // Persist snapshot whenever any of the seven keys change.
  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({
        provider: currentProvider,
        claudeModel: selectedClaudeModel,
        codexModel: selectedCodexModel,
        opencodeModel: selectedOpenCodeModel,
        claudePermissionMode,
        codexPermissionMode,
        longContextEnabled,
        reasoningEffort,
        codexFastMode,
      }));
    } catch {
      // Failed to save model selection state — non-fatal.
    }
  }, [
    currentProvider,
    selectedClaudeModel,
    selectedCodexModel,
    selectedOpenCodeModel,
    claudePermissionMode,
    codexPermissionMode,
    longContextEnabled,
    reasoningEffort,
    codexFastMode,
  ]);

  // Registry 异步加载后重校验持久化的 Claude 模型(问题1 根因)。mount effect 在
  // registry 尚为 DEFAULT 时运行,自定义模型(如 mimo-v2.5)被回退成 role;此处待
  // registry 真正收录该模型后恢复原 id。注册时立即检查一次(覆盖 registry 已先于订阅
  // 加载的情况),之后每次 registry 更新再检查;revalidatedRef 保证只成功应用一次。
  useEffect(() => {
    const revalidatePendingClaudeModel = () => {
      const pending = pendingClaudeModelRef.current;
      if (!pending || revalidatedRef.current) {
        return;
      }
      const inRegistry = getModelRegistrySnapshot().items.some(
        (model) => model.provider === 'claude' && model.enabled !== false && model.id === pending,
      );
      if (inRegistry) {
        revalidatedRef.current = true;
        setSelectedClaudeModel(pending);
      }
    };
    revalidatePendingClaudeModel();
    return subscribeModelRegistry(revalidatePendingClaudeModel);
    // Setters 是稳定引用;ref 与模块级 registry 访问器无需进依赖。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [setSelectedClaudeModel]);
}
