import { sendAction } from '../../bridge/typed';
import { UPSTREAM } from '../../generated/protocol';
import { useCallback, useEffect, useState } from 'react';
import type { TFunction } from 'i18next';
import { writeClaudeModelMapping } from '../../utils/claudeModelMapping';
import type { ProviderConfig } from '../../types/provider';
import type { SelectedAgent } from '../../components/ChatInputBox/types';

/**
 * Cross-cutting provider settings: streaming, show-thinking (display toggle),
 * send shortcut, auto-open file, selected agent, and the active provider config.
 * Each setting handler pushes the change to the backend via bridge event and
 * (where applicable) toasts the user-visible state change. Loads the
 * previously-selected agent on mount, retrying until the JCEF bridge is ready.
 *
 * streamingEnabled 与 showThinkingEnabled 均跨所有 provider/调用模式(SDK/CLI)统一生效,
 * 由后端 SessionCallbackAdapter 的统一推送层(TurnPushGate)实现,不依赖各 provider 原生能力:
 * 流式 off → content delta 缓冲到 turn 边界一次性 flush;
 * 思考区 off → 不推送 thinking delta/thinking-status(模型照常思考,纯显示控制)。
 * 思考预算仍由 reasoning effort 控制,与思考区显示开关解耦。
 */
export function useProviderSettings({ addToast, t }: {
  addToast: (message: string, type?: 'info' | 'success' | 'warning' | 'error') => void;
  t: TFunction;
}) {
  const [streamingEnabledSetting, setStreamingEnabledSetting] = useState(true);
  const [showThinkingEnabledSetting, setShowThinkingEnabledSetting] = useState(true);
  const [sendShortcut, setSendShortcut] = useState<'enter' | 'cmdEnter'>('enter');
  const [autoOpenFileEnabled, setAutoOpenFileEnabled] = useState(false);
  const [selectedAgent, setSelectedAgent] = useState<SelectedAgent | null>(null);
  const [activeProviderConfig, setActiveProviderConfig] = useState<ProviderConfig | null>(null);
  const [, setProviderConfigVersion] = useState(0);

  const syncActiveProviderModelMapping = useCallback((provider?: ProviderConfig | null) => {
    if (!provider || !provider.settingsConfig || !provider.settingsConfig.env) {
      writeClaudeModelMapping({});
      return;
    }
    const env = provider.settingsConfig.env as Record<string, unknown>;
    const get = (key: string): string => (typeof env[key] === 'string' ? (env[key] as string) : '');
    const mapping = {
      main: get('ANTHROPIC_MODEL'),
      fable: get('ANTHROPIC_DEFAULT_FABLE_MODEL'),
      haiku: get('ANTHROPIC_DEFAULT_HAIKU_MODEL'),
      sonnet: get('ANTHROPIC_DEFAULT_SONNET_MODEL'),
      opus: get('ANTHROPIC_DEFAULT_OPUS_MODEL'),
    };
    writeClaudeModelMapping(mapping);
  }, []);

  // Load previously-selected agent on mount, retrying until JCEF bridge is ready.
  useEffect(() => {
    let retryCount = 0;
    const MAX_RETRIES = 10;
    let timeoutId: number | undefined;

    const loadSelectedAgent = () => {
      if (window.sendToJava) {
        sendAction(UPSTREAM.GET_SELECTED_AGENT);
      } else {
        retryCount++;
        if (retryCount < MAX_RETRIES) {
          timeoutId = window.setTimeout(loadSelectedAgent, 100);
        }
      }
    };

    timeoutId = window.setTimeout(loadSelectedAgent, 200);
    return () => {
      if (timeoutId !== undefined) clearTimeout(timeoutId);
    };
  }, []);

  const handleAgentSelect = useCallback((agent: SelectedAgent | null) => {
    setSelectedAgent(agent);
    if (agent) {
      sendAction(UPSTREAM.SET_SELECTED_AGENT, JSON.stringify({
        id: agent.id,
        name: agent.name,
        prompt: agent.prompt,
      }));
    } else {
      sendAction(UPSTREAM.SET_SELECTED_AGENT, '');
    }
  }, []);

  const handleStreamingEnabledChange = useCallback((enabled: boolean) => {
    setStreamingEnabledSetting(enabled);
    sendAction(UPSTREAM.SET_STREAMING_ENABLED, JSON.stringify({ streamingEnabled: enabled }));
    addToast(
      enabled ? t('settings.basic.streaming.enabled') : t('settings.basic.streaming.disabled'),
      'success',
    );
  }, [t, addToast]);

  const handleShowThinkingEnabledChange = useCallback((enabled: boolean) => {
    setShowThinkingEnabledSetting(enabled);
    sendAction(UPSTREAM.SET_SHOW_THINKING_ENABLED, JSON.stringify({ showThinkingEnabled: enabled }));
    addToast(
      enabled ? t('settings.basic.showThinking.enabled') : t('settings.basic.showThinking.disabled'),
      'success',
    );
  }, [t, addToast]);

  const handleSendShortcutChange = useCallback((shortcut: 'enter' | 'cmdEnter') => {
    setSendShortcut(shortcut);
    sendAction(UPSTREAM.SET_SEND_SHORTCUT, JSON.stringify({ sendShortcut: shortcut }));
  }, []);

  const handleAutoOpenFileEnabledChange = useCallback((enabled: boolean) => {
    setAutoOpenFileEnabled(enabled);
    sendAction(UPSTREAM.SET_AUTO_OPEN_FILE_ENABLED, JSON.stringify({ autoOpenFileEnabled: enabled }));
    addToast(
      enabled ? t('settings.basic.autoOpenFile.enabled') : t('settings.basic.autoOpenFile.disabled'),
      'success',
    );
  }, [t, addToast]);

  return {
    streamingEnabledSetting,
    setStreamingEnabledSetting,
    showThinkingEnabledSetting,
    setShowThinkingEnabledSetting,
    sendShortcut,
    setSendShortcut,
    autoOpenFileEnabled,
    setAutoOpenFileEnabled,
    selectedAgent,
    setSelectedAgent,
    activeProviderConfig,
    setActiveProviderConfig,
    setProviderConfigVersion,
    syncActiveProviderModelMapping,
    handleAgentSelect,
    handleStreamingEnabledChange,
    handleShowThinkingEnabledChange,
    handleSendShortcutChange,
    handleAutoOpenFileEnabledChange,
  };
}

