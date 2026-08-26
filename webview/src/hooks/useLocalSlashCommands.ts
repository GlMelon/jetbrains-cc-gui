import { sendAction } from '../bridge/typed';
import { UPSTREAM } from '../generated/protocol';
import { useCallback, type RefObject } from 'react';
import type { TFunction } from 'i18next';
import type { ClaudeMessage } from '../types';
import { strip1MContextSuffix } from '../components/ChatInputBox/types';
import type { ChatInputBoxHandle, PermissionMode } from '../components/ChatInputBox/types';
import type { ViewMode } from './useModelProviderState';
import { getModelsForProvider } from '../utils/modelRegistry';
import {
  resolveLocalSlashCommand,
  listVisibleSlashCommands,
} from '../components/ChatInputBox/providers/slashCommandProvider';

/**
 * Local slash action values dispatched by this hook.
 * Mirror of the backend `LocalSlashAction` enum (SSOT: skill/LocalSlashAction.java) —
 * the backend annotates each slash command with one of these values in the command
 * payload, and this hook only maps the annotated action to the matching UI handler.
 * Do not invent new values here; add them to LocalSlashAction first.
 */
const LOCAL_ACTION_NEW_SESSION = 'new_session';
const LOCAL_ACTION_OPEN_HISTORY = 'open_history';
const LOCAL_ACTION_PLAN_MODE = 'plan_mode';
const LOCAL_ACTION_CONTEXT_USAGE = 'context_usage';
const LOCAL_ACTION_MODEL_PICKER = 'model_picker';
const LOCAL_ACTION_HELP = 'help';

function createContextUsageRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `context-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * Single entry point for plugin-local slash commands (/clear, /plan, /context,
 * /model, /help, ...).
 *
 * Which commands are local — and for which provider — is decided by the backend
 * (SlashCommandRegistry annotates the command payload with `localAction`). This
 * hook only resolves that metadata and executes the matching UI action; commands
 * without a local action return false and are forwarded to the CLI as plain text.
 */
export function useLocalSlashCommands({
  t,
  addToast,
  selectedModel,
  chatInputRef,
  setMessages,
  setCurrentView,
  forceCreateNewSession,
  handleModeSelect,
  longContextEnabled,
  openContextUsageDialog,
  closeContextUsageDialog,
}: {
  t: TFunction;
  addToast: (message: string, type?: 'info' | 'success' | 'warning' | 'error') => void;
  selectedModel: string;
  chatInputRef: RefObject<ChatInputBoxHandle | null>;
  setMessages: React.Dispatch<React.SetStateAction<ClaudeMessage[]>>;
  setCurrentView: React.Dispatch<React.SetStateAction<ViewMode>>;
  forceCreateNewSession: () => void;
  handleModeSelect?: (mode: PermissionMode) => void;
  longContextEnabled?: boolean;
  openContextUsageDialog: (requestId?: string | null, loading?: boolean) => void;
  closeContextUsageDialog: (requestId?: string | null) => boolean;
}) {
  /**
   * /context — open the context usage dialog (Claude only; the backend annotates
   * localAction only for Claude, on other providers /context forwards to the CLI).
   */
  const executeContextUsage = useCallback(() => {
    const requestId = createContextUsageRequestId();

    // Open dialog with loading state immediately
    openContextUsageDialog(requestId, true);

    // D5:不再前端构造 [1m];上送 stripped model + longContextEnabled 意图(已与 supports1M 取并集),
    // 后端 GetContextUsageActionHandler 据此权威追加 [1m] 后缀(与 set_session_model 范式一致)。
    const strippedModel = strip1MContextSuffix(selectedModel);
    // A2:supports1M 读 registry item.supports1MContext(后端权威),取代前端 modelSupports1MContext 字符串推断。
    const supports1M = getModelsForProvider('claude').find((model) => model.id === strippedModel)?.supports1MContext ?? false;
    const sent = sendAction(UPSTREAM.GET_CONTEXT_USAGE, JSON.stringify({
      model: strippedModel,
      longContextEnabled: (longContextEnabled ?? false) && supports1M,
      requestId,
    }));

    if (!sent) {
      closeContextUsageDialog(requestId);
      addToast(t('chat.bridgeUnavailable', {
        defaultValue: 'Bridge is not available right now',
      }), 'error');
    }
  }, [selectedModel, longContextEnabled, addToast, t, openContextUsageDialog, closeContextUsageDialog]);

  /**
   * /help — render the available commands (backend-delivered list) as an assistant message.
   */
  const executeHelp = useCallback(async (text: string) => {
    const commands = await listVisibleSlashCommands();
    const lines = commands.map(cmd =>
      `- \`${cmd.label}\`${cmd.description ? ` — ${cmd.description}` : ''}`
    );
    const title = t('chat.helpCommandTitle', { defaultValue: 'Available commands' });
    const content = lines.length > 0
      ? `**${title}**\n\n${lines.join('\n')}`
      : `**${title}**\n\n${t('chat.helpCommandEmpty', { defaultValue: 'No commands available yet.' })}`;

    const userMessage: ClaudeMessage = {
      type: 'user',
      content: text,
      timestamp: new Date().toISOString(),
    };
    const assistantMessage: ClaudeMessage = {
      type: 'assistant',
      content,
      timestamp: new Date().toISOString(),
    };
    setMessages((prev) => [...prev, userMessage, assistantMessage]);
  }, [t, setMessages]);

  /**
   * Try to execute the text as a plugin-local slash command.
   * Returns true when the command was handled locally (nothing should be sent).
   */
  const tryExecuteLocalCommand = useCallback(async (text: string): Promise<boolean> => {
    const command = await resolveLocalSlashCommand(text);
    if (!command || !command.localAction) return false;

    switch (command.localAction) {
      case LOCAL_ACTION_NEW_SESSION:
        forceCreateNewSession();
        return true;

      case LOCAL_ACTION_OPEN_HISTORY:
        setCurrentView('history');
        return true;

      case LOCAL_ACTION_PLAN_MODE:
        if (handleModeSelect) {
          handleModeSelect('plan');
          addToast(t('chat.planModeEnabled', { defaultValue: 'Plan mode enabled' }), 'info');
        }
        return true;

      case LOCAL_ACTION_CONTEXT_USAGE:
        executeContextUsage();
        return true;

      case LOCAL_ACTION_MODEL_PICKER:
        chatInputRef.current?.openModelSelect();
        return true;

      case LOCAL_ACTION_HELP:
        await executeHelp(text);
        return true;

      default:
        return false;
    }
  }, [
    forceCreateNewSession,
    setCurrentView,
    handleModeSelect,
    addToast,
    t,
    executeContextUsage,
    chatInputRef,
    executeHelp,
  ]);

  return {
    tryExecuteLocalCommand,
  };
}
