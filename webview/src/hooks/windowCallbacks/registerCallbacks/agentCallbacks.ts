/**
 * agentCallbacks.ts
 *
 * Registers window bridge callbacks for agent management and selection context:
 * addSelectionInfo, addCodeSnippet, clearSelectionInfo,
 * onSelectedAgentReceived, onSelectedAgentChanged.
 */

import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import { bridgeHub, registerLegacyAlias } from '../../../bridge';

export function registerAgentAndSelectionCallbacks(options: UseWindowCallbacksOptions): void {
  const {
    setContextInfo,
    setSelectedAgent,
  } = options;

  window.addSelectionInfo = (selectionInfo) => {
    if (selectionInfo) {
      const match = selectionInfo.match(/^@([^#]+)(?:#L(\d+)(?:-(\d+))?)?$/);
      if (match) {
        const file = match[1];
        const startLine = match[2] ? parseInt(match[2], 10) : undefined;
        const endLine =
          match[3] ? parseInt(match[3], 10) : startLine !== undefined ? startLine : undefined;
        setContextInfo({
          file,
          startLine,
          endLine,
          raw: selectionInfo,
        });
      }
    }
  };

  window.addCodeSnippet = (selectionInfo) => {
    if (selectionInfo && window.insertCodeSnippetAtCursor) {
      window.insertCodeSnippetAtCursor(selectionInfo);
    }
  };

  window.clearSelectionInfo = () => {
    setContextInfo(null);
  };

  // [归一化] onSelectedAgentReceived → agent.selected_received（透明字符串管道）
  registerLegacyAlias('onSelectedAgentReceived', 'agent.selected_received');
  bridgeHub.subscribe('agent.selected_received', (json) => {
    try {
      if (!json || json === 'null' || json === '{}') {
        setSelectedAgent(null);
        return;
      }
      const data = JSON.parse(json as string);
      const agentFromNewShape = data?.agent;
      const agentFromLegacyShape = data;

      const agentData = agentFromNewShape?.id
        ? agentFromNewShape
        : agentFromLegacyShape?.id
          ? agentFromLegacyShape
          : null;
      if (!agentData) {
        setSelectedAgent(null);
        return;
      }

      setSelectedAgent({
        id: agentData.id,
        name: agentData.name || '',
        prompt: agentData.prompt,
      });
    } catch (error) {
      console.error('[Frontend] Failed to parse selected agent:', error);
      setSelectedAgent(null);
    }
  });

  // [归一化] onSelectedAgentChanged → agent.selected_changed
  registerLegacyAlias('onSelectedAgentChanged', 'agent.selected_changed');
  bridgeHub.subscribe('agent.selected_changed', (json) => {
    try {
      if (!json || json === 'null' || json === '{}') {
        setSelectedAgent(null);
        return;
      }

      const data = JSON.parse(json as string);
      if (data?.success === false) {
        return;
      }

      const agentData = data?.agent;
      if (!agentData || !agentData.id) {
        setSelectedAgent(null);
        return;
      }

      setSelectedAgent({
        id: agentData.id,
        name: agentData.name || '',
        prompt: agentData.prompt,
      });
    } catch (error) {
      console.error('[Frontend] Failed to parse selected agent changed:', error);
    }
  });
}
