import { useState, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import type { CodexProviderConfig } from '../../../types/provider';
import { sendAction } from '../../../bridge/typed';
import { UPSTREAM } from '../../../generated/protocol';
import { createCodexCatalogModels, getModelRegistrySnapshot } from '../../../utils/modelRegistry';

const syncCodexProviderCatalogToRegistry = (provider: CodexProviderConfig | null | undefined) => {
  const catalogModels = createCodexCatalogModels(provider);
  const registry = getModelRegistrySnapshot();
  const nonCodexItems = registry.items.filter((item) => item.provider !== 'codex');
  // 走 typed sendAction,UPSTREAM.SET_MODEL_REGISTRY 来自 generated/protocol(SSOT);对象 payload 自动 stringify
  sendAction(UPSTREAM.SET_MODEL_REGISTRY, {
    items: [...nonCodexItems, ...catalogModels],
  });
};

export interface CodexProviderDialogState {
  isOpen: boolean;
  provider: CodexProviderConfig | null;
}

export interface DeleteCodexConfirmState {
  isOpen: boolean;
  provider: CodexProviderConfig | null;
}

export function useCodexProviderManagement(options: { onError?: (message: string) => void; onSuccess?: (message: string) => void } = {}) {
  const { t } = useTranslation();
  const { onSuccess } = options;

  // Codex provider list state
  const [codexProviders, setCodexProviders] = useState<CodexProviderConfig[]>([]);
  const [codexLoading, setCodexLoading] = useState(false);

  // Codex configuration (reserved for future display)
  const [_codexConfig, setCodexConfig] = useState<any>(null);
  const [_codexConfigLoading, setCodexConfigLoading] = useState(false);

  // Codex provider dialog state
  const [codexProviderDialog, setCodexProviderDialog] = useState<CodexProviderDialogState>({
    isOpen: false,
    provider: null,
  });

  // Codex provider delete confirmation state
  const [deleteCodexConfirm, setDeleteCodexConfirm] = useState<DeleteCodexConfirmState>({
    isOpen: false,
    provider: null,
  });

  // Load Codex provider list
  const loadCodexProviders = useCallback(() => {
    setCodexLoading(true);
    sendAction(UPSTREAM.GET_CODEX_PROVIDERS);
  }, []);

  // Update Codex provider list (used by window callback)
  const updateCodexProviders = useCallback((providersList: CodexProviderConfig[]) => {
    setCodexProviders(providersList);
    setCodexLoading(false);
  }, []);

  // Update active Codex provider (used by window callback)
  const updateActiveCodexProvider = useCallback((activeProvider: CodexProviderConfig) => {
    if (activeProvider) {
      setCodexProviders((prev) =>
        prev.map((p) => ({ ...p, isActive: p.id === activeProvider.id }))
      );
      syncCodexProviderCatalogToRegistry(activeProvider);
    }
  }, []);

  // Update Codex configuration (used by window callback)
  const updateCurrentCodexConfig = useCallback((config: any) => {
    setCodexConfig(config);
    setCodexConfigLoading(false);
  }, []);

  // Open add Codex provider dialog
  const handleAddCodexProvider = useCallback(() => {
    setCodexProviderDialog({ isOpen: true, provider: null });
  }, []);

  // Open edit Codex provider dialog
  const handleEditCodexProvider = useCallback((provider: CodexProviderConfig) => {
    setCodexProviderDialog({ isOpen: true, provider });
  }, []);

  // Close Codex provider dialog
  const handleCloseCodexProviderDialog = useCallback(() => {
    setCodexProviderDialog({ isOpen: false, provider: null });
  }, []);

  // Save Codex provider
  const handleSaveCodexProvider = useCallback(
    (providerData: CodexProviderConfig) => {
      const isAdding = !codexProviderDialog.provider;

      if (isAdding) {
        sendAction(UPSTREAM.ADD_CODEX_PROVIDER, JSON.stringify(providerData));
        onSuccess?.(t('toast.providerAdded'));
      } else {
        const updateData = {
          id: providerData.id,
          updates: {
            name: providerData.name,
            remark: providerData.remark,
            configToml: providerData.configToml,
            authJson: providerData.authJson,
            customModels: providerData.customModels,
            modelCatalog: providerData.modelCatalog,
            messageEnvVars: providerData.messageEnvVars || [],
            mcpEnvVars: providerData.mcpEnvVars || [],
          },
        };
        sendAction(UPSTREAM.UPDATE_CODEX_PROVIDER, JSON.stringify(updateData));
        onSuccess?.(t('toast.providerUpdated'));
      }

      syncCodexProviderCatalogToRegistry(providerData);

      setCodexProviderDialog({ isOpen: false, provider: null });
      setCodexLoading(true);
    },
    [codexProviderDialog.provider, codexProviders, onSuccess]
  );

  // Switch Codex provider
  const handleSwitchCodexProvider = useCallback((id: string) => {
    const data = { id };
    sendAction(UPSTREAM.SWITCH_CODEX_PROVIDER, JSON.stringify(data));
    setCodexLoading(true);
  }, []);

  const handleRevokeCodexLocalConfigAuthorization = useCallback((fallbackProviderId?: string) => {
    const data = {
      fallbackProviderId: fallbackProviderId ?? '',
    };
    sendAction(UPSTREAM.REVOKE_CODEX_LOCAL_CONFIG_AUTHORIZATION, JSON.stringify(data));
    setCodexLoading(true);
    setCodexConfigLoading(true);
  }, []);

  // Delete Codex provider
  const handleDeleteCodexProvider = useCallback((provider: CodexProviderConfig) => {
    setDeleteCodexConfirm({ isOpen: true, provider });
  }, []);

  // Confirm Codex provider deletion
  const confirmDeleteCodexProvider = useCallback(() => {
    const provider = deleteCodexConfirm.provider;
    if (!provider) return;

    const data = { id: provider.id };
    sendAction(UPSTREAM.DELETE_CODEX_PROVIDER, JSON.stringify(data));
    onSuccess?.(t('toast.providerDeleted'));
    setCodexLoading(true);
    setDeleteCodexConfirm({ isOpen: false, provider: null });
  }, [deleteCodexConfirm.provider, onSuccess]);

  // Cancel Codex provider deletion
  const cancelDeleteCodexProvider = useCallback(() => {
    setDeleteCodexConfirm({ isOpen: false, provider: null });
  }, []);

  return {
    // State
    codexProviders,
    codexLoading,
    codexProviderDialog,
    deleteCodexConfirm,
    // Methods
    loadCodexProviders,
    updateCodexProviders,
    updateActiveCodexProvider,
    updateCurrentCodexConfig,
    handleAddCodexProvider,
    handleEditCodexProvider,
    handleCloseCodexProviderDialog,
    handleSaveCodexProvider,
    handleSwitchCodexProvider,
    handleRevokeCodexLocalConfigAuthorization,
    handleDeleteCodexProvider,
    confirmDeleteCodexProvider,
    cancelDeleteCodexProvider,
    // Setter
    setCodexLoading,
    setCodexConfigLoading,
  };
}
