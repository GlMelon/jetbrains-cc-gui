import { useState, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import type { OpenCodeProviderConfig } from '../../../types/provider';
import { sendAction } from '../../../bridge/typed';
import { UPSTREAM } from '../../../generated/protocol';

/**
 * OpenCode provider 管理 hook —— 对称 {@link useCodexProviderManagement}(Principle 6 多 provider 对称)。
 *
 * <p>与 codex 关键差异:opencode 的模型经「外科手术式合并写入 opencode.json →
 * {@code OpenCodeConfigReader.readModels()} 自动读回」流动,无需前端把 catalog 推回 registry,
 * 故无 {@code syncXxxProviderCatalogToRegistry} 等价物(provider 变更后后端下发 MODEL_REGISTRY)。
 */
export function useOpenCodeProviderManagement(options: { onSuccess?: (message: string) => void } = {}) {
  const { t } = useTranslation();
  const { onSuccess } = options;

  // OpenCode provider list state
  const [openCodeProviders, setOpenCodeProviders] = useState<OpenCodeProviderConfig[]>([]);
  const [openCodeLoading, setOpenCodeLoading] = useState(false);

  // OpenCode native config (reserved for local-config display)
  const [_openCodeConfig, setOpenCodeConfig] = useState<any>(null);
  const [_openCodeConfigLoading, setOpenCodeConfigLoading] = useState(false);

  // OpenCode provider dialog state
  const [openCodeProviderDialog, setOpenCodeProviderDialog] = useState<{
    isOpen: boolean;
    provider: OpenCodeProviderConfig | null;
  }>({ isOpen: false, provider: null });

  // OpenCode provider delete confirmation state
  const [deleteOpenCodeConfirm, setDeleteOpenCodeConfirm] = useState<{
    isOpen: boolean;
    provider: OpenCodeProviderConfig | null;
  }>({ isOpen: false, provider: null });

  // Load OpenCode provider list
  const loadOpenCodeProviders = useCallback(() => {
    setOpenCodeLoading(true);
    sendAction(UPSTREAM.GET_OPENCODE_PROVIDERS);
  }, []);

  // Update OpenCode provider list (used by window callback)
  const updateOpenCodeProviders = useCallback((providersList: OpenCodeProviderConfig[]) => {
    setOpenCodeProviders(providersList);
    setOpenCodeLoading(false);
  }, []);

  // Update active OpenCode provider (used by window callback)
  const updateActiveOpenCodeProvider = useCallback((activeProvider: OpenCodeProviderConfig) => {
    if (activeProvider) {
      setOpenCodeProviders((prev) =>
        prev.map((p) => ({ ...p, isActive: p.id === activeProvider.id }))
      );
    }
  }, []);

  // Update OpenCode native config (used by window callback)
  const updateCurrentOpenCodeConfig = useCallback((config: any) => {
    setOpenCodeConfig(config);
    setOpenCodeConfigLoading(false);
  }, []);

  // Open add OpenCode provider dialog
  const handleAddOpenCodeProvider = useCallback(() => {
    setOpenCodeProviderDialog({ isOpen: true, provider: null });
  }, []);

  // Open edit OpenCode provider dialog
  const handleEditOpenCodeProvider = useCallback((provider: OpenCodeProviderConfig) => {
    setOpenCodeProviderDialog({ isOpen: true, provider });
  }, []);

  // Close OpenCode provider dialog
  const handleCloseOpenCodeProviderDialog = useCallback(() => {
    setOpenCodeProviderDialog({ isOpen: false, provider: null });
  }, []);

  // Save OpenCode provider
  const handleSaveOpenCodeProvider = useCallback(
    (providerData: OpenCodeProviderConfig) => {
      const isAdding = !openCodeProviderDialog.provider;

      if (isAdding) {
        sendAction(UPSTREAM.ADD_OPENCODE_PROVIDER, JSON.stringify(providerData));
        onSuccess?.(t('toast.providerAdded'));
      } else {
        const updateData = {
          id: providerData.id,
          updates: {
            name: providerData.name,
            apiKey: providerData.apiKey,
            baseURL: providerData.baseURL,
            apiBase: providerData.apiBase,
            models: providerData.models,
          },
        };
        sendAction(UPSTREAM.UPDATE_OPENCODE_PROVIDER, JSON.stringify(updateData));
        onSuccess?.(t('toast.providerUpdated'));
      }

      setOpenCodeProviderDialog({ isOpen: false, provider: null });
      setOpenCodeLoading(true);
    },
    [openCodeProviderDialog.provider, onSuccess]
  );

  // Switch OpenCode provider
  const handleSwitchOpenCodeProvider = useCallback((id: string) => {
    sendAction(UPSTREAM.SWITCH_OPENCODE_PROVIDER, JSON.stringify({ id }));
    setOpenCodeLoading(true);
  }, []);

  const handleRevokeOpenCodeLocalConfigAuthorization = useCallback((fallbackProviderId?: string) => {
    sendAction(UPSTREAM.REVOKE_OPENCODE_LOCAL_CONFIG_AUTHORIZATION, JSON.stringify({
      fallbackProviderId: fallbackProviderId ?? '',
    }));
    setOpenCodeLoading(true);
    setOpenCodeConfigLoading(true);
  }, []);

  // Delete OpenCode provider
  const handleDeleteOpenCodeProvider = useCallback((provider: OpenCodeProviderConfig) => {
    setDeleteOpenCodeConfirm({ isOpen: true, provider });
  }, []);

  // Confirm OpenCode provider deletion
  const confirmDeleteOpenCodeProvider = useCallback(() => {
    const provider = deleteOpenCodeConfirm.provider;
    if (!provider) return;

    sendAction(UPSTREAM.DELETE_OPENCODE_PROVIDER, JSON.stringify({ id: provider.id }));
    onSuccess?.(t('toast.providerDeleted'));
    setOpenCodeLoading(true);
    setDeleteOpenCodeConfirm({ isOpen: false, provider: null });
  }, [deleteOpenCodeConfirm.provider, onSuccess]);

  // Cancel OpenCode provider deletion
  const cancelDeleteOpenCodeProvider = useCallback(() => {
    setDeleteOpenCodeConfirm({ isOpen: false, provider: null });
  }, []);

  return {
    // State
    openCodeProviders,
    openCodeLoading,
    openCodeProviderDialog,
    deleteOpenCodeConfirm,
    // Methods
    loadOpenCodeProviders,
    updateOpenCodeProviders,
    updateActiveOpenCodeProvider,
    updateCurrentOpenCodeConfig,
    handleAddOpenCodeProvider,
    handleEditOpenCodeProvider,
    handleCloseOpenCodeProviderDialog,
    handleSaveOpenCodeProvider,
    handleSwitchOpenCodeProvider,
    handleRevokeOpenCodeLocalConfigAuthorization,
    handleDeleteOpenCodeProvider,
    confirmDeleteOpenCodeProvider,
    cancelDeleteOpenCodeProvider,
    // Setters
    setOpenCodeLoading,
    setOpenCodeConfigLoading,
  };
}

export interface OpenCodeProviderDialogState {
  isOpen: boolean;
  provider: OpenCodeProviderConfig | null;
}

export interface DeleteOpenCodeConfirmState {
  isOpen: boolean;
  provider: OpenCodeProviderConfig | null;
}

export type UseOpenCodeProviderManagementReturn = ReturnType<typeof useOpenCodeProviderManagement>;
