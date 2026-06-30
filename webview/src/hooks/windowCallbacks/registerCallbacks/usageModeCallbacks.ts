/**
 * usageModeCallbacks.ts
 *
 * Registers window bridge callbacks for usage statistics, permission modes, and
 * model/provider updates: onUsageUpdate, onModeReceived,
 * onModelConfirmed, updateActiveProvider, updateThinkingEnabled,
 * updateStreamingEnabled, updateSendShortcut, updateAutoOpenFileEnabled.
 *
 * [归一化重构] usage/settings 类纯事件回调已迁移到 bridgeHub 订阅(透明字符串管道,
 * 订阅者收到原始 json 字符串,逐字节等价于旧 window.xxx(json))。后端仍调用旧 window.xxx
 * 名,经 compat 兼容别名转发到 dispatch(type) → 订阅者。迁移期新旧路径并存、行为一致。
 * pendingSlots 占位仍负责捕获 React 挂载前的早期推送(registerUsageModeCallbacks 运行于
 * 挂载时,会覆盖占位为 compat 别名;挂载前的早到值由 pending drain 消费)。详见 plan。
 */

import { subscribeEvent } from '../../../bridge/typed';
import { DOWNSTREAM } from '../../../generated/protocol';
import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import type { PermissionMode } from '../../../components/ChatInputBox/types';
import { isValidPermissionMode } from '../../../components/ChatInputBox/types';
import { drainPendingSettings, startInitialSettingsRequest } from '../settingsBootstrap';
import { clampPermissionDialogTimeoutSeconds } from '../../../utils/permissionDialogTimeout';
import { normalizeProvider, resolveClaudeModelId } from '../../../utils/modelRegistry';
import { registerLegacyAlias } from '../../../bridge';

export function registerUsageModeCallbacks(options: UseWindowCallbacksOptions): void {
  const {
    setUsagePercentage,
    setUsageUsedTokens,
    setUsageMaxTokens,
    setTokenDetail,
      setCurrentProvider,
    setPermissionMode,
    setClaudePermissionMode,
    setCodexPermissionMode,
    setSelectedClaudeModel,
    setSelectedCodexModel,
    setSelectedOpenCodeModel,
    setProviderConfigVersion,
    setActiveProviderConfig,
    setClaudeSettingsAlwaysThinkingEnabled,
    setStreamingEnabledSetting,
    setSendShortcut,
    setAutoOpenFileEnabled,
    setPermissionDialogTimeoutSeconds,
    currentProviderRef,
    syncActiveProviderModelMapping,
  } = options;

  // [归一化] onUsageUpdate → usage.update。兼容别名让后端旧 window.onUsageUpdate 调用转发到总线。
  registerLegacyAlias('onUsageUpdate', DOWNSTREAM.USAGE_UPDATE);
  subscribeEvent(DOWNSTREAM.USAGE_UPDATE, (json) => {
    try {
      const data = JSON.parse(json as string);
      if (typeof data.percentage === 'number') {
        const used =
          typeof data.usedTokens === 'number'
            ? data.usedTokens
            : typeof data.totalTokens === 'number'
              ? data.totalTokens
              : undefined;
        const max =
          typeof data.maxTokens === 'number'
            ? data.maxTokens
            : typeof data.limit === 'number'
              ? data.limit
              : undefined;

        if (used !== undefined && max !== undefined && used > max * 2) {
          console.warn(
            '[Frontend] Usage data may be incorrect: used=' + used + ', max=' + max,
          );
        }

        const safePercentage = Math.max(0, Math.min(100, data.percentage));
        setUsagePercentage(safePercentage);
        setUsageUsedTokens(used);
        setUsageMaxTokens(max);

        // Parse detailed token information if available
        if (typeof data.inputTokens === 'number' ||
            typeof data.outputTokens === 'number' ||
            typeof data.cacheCreationTokens === 'number' ||
            typeof data.cacheReadTokens === 'number') {
          const inputTokens = data.inputTokens || 0;
          const outputTokens = data.outputTokens || 0;
          const cacheCreationTokens = data.cacheCreationTokens || 0;
          const cacheReadTokens = data.cacheReadTokens || 0;
          const totalInput = inputTokens + cacheCreationTokens + cacheReadTokens;
          const cacheHitRate = totalInput > 0 ? (cacheReadTokens / totalInput) * 100 : 0;

          setTokenDetail({
            inputTokens,
            outputTokens,
            cacheCreationTokens,
            cacheReadTokens,
            totalTokens: used || 0,
            maxTokens: max || 0,
            percentage: safePercentage,
            cacheHitRate,
          });
        }
      }
    } catch (error) {
      console.error('[Frontend] Failed to parse usage update:', error);
    }
  });

  const updateMode = (mode?: PermissionMode, providerOverride?: string) => {
    const activeProvider = providerOverride || currentProviderRef.current;
    if (isValidPermissionMode(mode)) {
      const nextMode: PermissionMode =
        activeProvider === 'codex' && mode === 'plan' ? 'default' : mode;
      setPermissionMode((prev) => (prev === nextMode ? prev : nextMode));
      if (activeProvider === 'codex') {
        setCodexPermissionMode((prev) => (prev === nextMode ? prev : nextMode));
      } else {
        setClaudePermissionMode((prev) => (prev === nextMode ? prev : nextMode));
      }
    }
  };

  // [归一化] onModeReceived → mode.received(裸字符串 payload)。
  // 透明管道原样传递,updateMode 收到的 mode 字符串与旧 window.onModeReceived(mode) 一致。
  // NOTE: MODE_CHANGED (mode.changed) 已移除——后端从未 dispatch 此事件。
  registerLegacyAlias('onModeReceived', DOWNSTREAM.MODE_RECEIVED);
  subscribeEvent(DOWNSTREAM.MODE_RECEIVED, (mode) => updateMode(mode as PermissionMode));

  // [归一化] onModelConfirmed(modelId, provider) 原为两参数回调,后端已归一化为单 JSON 参数
  // {modelId, provider}。兼容别名转发到 model.confirmed,订阅者解析 JSON 取两字段。
  // resolveClaudeModelId 同上,保留 registry 中已收录的自定义模型 id。
  registerLegacyAlias('onModelConfirmed', DOWNSTREAM.MODEL_CONFIRMED);
  subscribeEvent(DOWNSTREAM.MODEL_CONFIRMED, (json) => {
    try {
      const data = JSON.parse(json as string);
      const modelId: string = data.modelId;
      const provider = normalizeProvider(data.provider);
      if (provider === 'codex') {
        setSelectedCodexModel(modelId);
      } else if (provider === 'opencode') {
        setSelectedOpenCodeModel(modelId);
      } else {
        setSelectedClaudeModel(resolveClaudeModelId(modelId));
      }
    } catch (error) {
      console.error('[Frontend] Failed to parse model confirmed:', error);
    }
  });

  // [归一化] updateActiveProvider → provider.active(合并双写竞争)。
  // 这个 handler 是 provider.active 的订阅者之一,负责 usageMode 逻辑:
  //   syncActiveProviderModelMapping + setProviderConfigVersion + setActiveProviderConfig。
  // RuntimeProviderSelect / Settings 等消费者经 subscribeActiveProvider → subscribeEvent(DOWNSTREAM.PROVIDER_ACTIVE)
  // 订阅同一 type,由 dispatch 自动广播(无需手动 emit)。从而消除原「挂载后 usageMode 覆盖导致 channel
  // 订阅者收不到」的双写竞争。
  registerLegacyAlias('updateActiveProvider', DOWNSTREAM.PROVIDER_ACTIVE);
  subscribeEvent(DOWNSTREAM.PROVIDER_ACTIVE, (jsonStr) => {
    try {
      const provider = JSON.parse(jsonStr as string);
      syncActiveProviderModelMapping(provider);
      setProviderConfigVersion((prev) => prev + 1);
      setActiveProviderConfig(provider);
    } catch (error) {
      console.error('[Frontend] Failed to parse active provider in App:', error);
    }
  });

    // [归一化] updateSessionRuntimeState → session.runtime_state(JSON)
    registerLegacyAlias('updateSessionRuntimeState', DOWNSTREAM.SESSION_RUNTIME_STATE);
    subscribeEvent(DOWNSTREAM.SESSION_RUNTIME_STATE, (jsonStr) => {
        try {
            const data = JSON.parse(jsonStr as string);
            const provider = normalizeProvider(data.provider);
            setCurrentProvider(provider);
            currentProviderRef.current = provider;

            updateMode(data.permissionMode as PermissionMode | undefined, provider);

            if (typeof data.model === 'string' && data.model.trim()) {
                if (provider === 'codex') {
                    setSelectedCodexModel(data.model);
                } else if (provider === 'opencode') {
                    setSelectedOpenCodeModel(data.model);
                } else {
                    setSelectedClaudeModel(resolveClaudeModelId(data.model));
                }
            }

        } catch (error) {
            console.error('[Frontend] Failed to parse session runtime state:', error);
        }
    });

  // [归一化] updateThinkingEnabled → setting.thinking_enabled
  registerLegacyAlias('updateThinkingEnabled', DOWNSTREAM.SETTING_THINKING_ENABLED);
  subscribeEvent(DOWNSTREAM.SETTING_THINKING_ENABLED, (jsonStr) => {
    const trimmed = ((jsonStr as string) || '').trim();
    try {
      const data = JSON.parse(trimmed);
      if (typeof data === 'boolean') {
        setClaudeSettingsAlwaysThinkingEnabled(data);
        return;
      }
      if (data && typeof data.enabled === 'boolean') {
        setClaudeSettingsAlwaysThinkingEnabled(data.enabled);
        return;
      }
    } catch {
      if (trimmed === 'true' || trimmed === 'false') {
        setClaudeSettingsAlwaysThinkingEnabled(trimmed === 'true');
      }
    }
  });

  // [归一化] updateStreamingEnabled → setting.streaming_enabled
  registerLegacyAlias('updateStreamingEnabled', DOWNSTREAM.SETTING_STREAMING_ENABLED);
  subscribeEvent(DOWNSTREAM.SETTING_STREAMING_ENABLED, (jsonStr) => {
    try {
      const data = JSON.parse(jsonStr as string);
      setStreamingEnabledSetting(data.streamingEnabled ?? true);
    } catch (error) {
      console.error('[Frontend] Failed to parse streaming enabled:', error);
    }
  });

  // [归一化] updateSendShortcut → setting.send_shortcut
  registerLegacyAlias('updateSendShortcut', DOWNSTREAM.SETTING_SEND_SHORTCUT);
  subscribeEvent(DOWNSTREAM.SETTING_SEND_SHORTCUT, (jsonStr) => {
    try {
      const data = JSON.parse(jsonStr as string);
      if (data.sendShortcut === 'enter' || data.sendShortcut === 'cmdEnter') {
        setSendShortcut(data.sendShortcut);
      }
    } catch (error) {
      console.error('[Frontend] Failed to parse send shortcut:', error);
    }
  });

  // [归一化] updateAutoOpenFileEnabled → setting.auto_open_file
  registerLegacyAlias('updateAutoOpenFileEnabled', DOWNSTREAM.SETTING_AUTO_OPEN_FILE);
  subscribeEvent(DOWNSTREAM.SETTING_AUTO_OPEN_FILE, (jsonStr) => {
    try {
      const data = JSON.parse(jsonStr as string);
      setAutoOpenFileEnabled(data.autoOpenFileEnabled ?? false);
    } catch (error) {
      console.error('[Frontend] Failed to parse auto open file enabled:', error);
    }
  });

  // [归一化] updatePermissionDialogTimeout → setting.permission_dialog_timeout
  registerLegacyAlias('updatePermissionDialogTimeout', DOWNSTREAM.SETTING_PERMISSION_DIALOG_TIMEOUT);
  subscribeEvent(DOWNSTREAM.SETTING_PERMISSION_DIALOG_TIMEOUT, (jsonStr) => {
    try {
      const data = JSON.parse(jsonStr as string);
      setPermissionDialogTimeoutSeconds(clampPermissionDialogTimeoutSeconds(data.permissionDialogTimeoutSeconds));
    } catch (error) {
      const errorName = error instanceof Error ? error.name : 'UnknownError';
      console.error(`[Frontend] Failed to parse permission dialog timeout payload: ${errorName}`);
    }
  });

  // Drain any pending settings that arrived before callback registration
  drainPendingSettings();
  // Kick off initial settings requests
  startInitialSettingsRequest();
}
