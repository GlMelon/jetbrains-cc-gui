import { useCallback, useState } from 'react';
import type { TFunction } from 'i18next';
import type { PermissionRequest } from '../components/PermissionDialog';
import type { AskUserQuestionRequest } from '../components/AskUserQuestionDialog';
import type { PlanApprovalRequest } from '../components/PlanApprovalDialog';
import type { RewindRequest } from '../components/RewindDialog';
import type { ContextUsageData } from '../components/ContextUsageDialog';
import { sendBridgeEvent } from '../utils/bridge';
import { useRequestQueueDialog } from './useRequestQueueDialog';

interface UseDialogManagementOptions {
  t: TFunction;
}

interface UseDialogManagementReturn {
  // Permission dialog
  permissionDialogOpen: boolean;
  currentPermissionRequest: PermissionRequest | null;
  openPermissionDialog: (request: PermissionRequest) => void;
  handlePermissionApprove: (channelId: string) => void;
  handlePermissionApproveAlways: (channelId: string) => void;
  handlePermissionSkip: (channelId: string) => void;

  // AskUserQuestion dialog
  askUserQuestionDialogOpen: boolean;
  currentAskUserQuestionRequest: AskUserQuestionRequest | null;
  openAskUserQuestionDialog: (request: AskUserQuestionRequest) => void;
  handleAskUserQuestionSubmit: (requestId: string, answers: Record<string, string | string[]>) => void;
  handleAskUserQuestionCancel: (requestId: string) => void;

  // PlanApproval dialog
  planApprovalDialogOpen: boolean;
  currentPlanApprovalRequest: PlanApprovalRequest | null;
  openPlanApprovalDialog: (request: PlanApprovalRequest) => void;
  handlePlanApprovalApprove: (requestId: string, targetMode: string) => void;
  handlePlanApprovalReject: (requestId: string) => void;

  // Rewind dialog
  rewindDialogOpen: boolean;
  setRewindDialogOpen: (open: boolean) => void;
  currentRewindRequest: RewindRequest | null;
  setCurrentRewindRequest: (request: RewindRequest | null) => void;
  isRewinding: boolean;
  setIsRewinding: (loading: boolean) => void;

  // Rewind select dialog
  rewindSelectDialogOpen: boolean;
  setRewindSelectDialogOpen: (open: boolean) => void;

  // Context usage dialog
  contextUsageDialogOpen: boolean;
  contextUsageIsLoading: boolean;
  contextUsageData: ContextUsageData | null;
  openContextUsageDialog: (requestId?: string | null, loading?: boolean) => void;
  updateContextUsageData: (requestId: string | null | undefined, data: ContextUsageData) => boolean;
  closeContextUsageDialog: (requestId?: string | null) => boolean;
}

/**
 * Hook for managing dialog states (permission, ask user question, rewind)
 */
export function useDialogManagement({ t }: UseDialogManagementOptions): UseDialogManagementReturn {
  // Permission dialog - 使用泛型 hook
  const permissionDialog = useRequestQueueDialog<PermissionRequest>({
    getId: (req) => req.channelId,
  });

  // AskUserQuestion dialog - 使用泛型 hook
  const askUserQuestionDialog = useRequestQueueDialog<AskUserQuestionRequest>({
    getId: (req) => req.requestId,
  });

  // PlanApproval dialog - 使用泛型 hook
  const planApprovalDialog = useRequestQueueDialog<PlanApprovalRequest>({
    getId: (req) => req.requestId,
  });

  // Rewind dialog state（无队列逻辑）
  const [rewindDialogOpen, setRewindDialogOpen] = useState(false);
  const [currentRewindRequest, setCurrentRewindRequest] = useState<RewindRequest | null>(null);
  const [isRewinding, setIsRewinding] = useState(false);

  // Rewind select dialog state
  const [rewindSelectDialogOpen, setRewindSelectDialogOpen] = useState(false);

  // Context usage dialog state（无队列逻辑）
  const [contextUsageDialogOpen, setContextUsageDialogOpen] = useState(false);
  const [contextUsageIsLoading, setContextUsageIsLoading] = useState(false);
  const [contextUsageData, setContextUsageData] = useState<ContextUsageData | null>(null);
  const contextUsageRequestIdRef = { current: null as string | null };

  // Permission handlers
  const handlePermissionApprove = useCallback((channelId: string) => {
    const payload = JSON.stringify({
      channelId,
      allow: true,
      remember: false,
      rejectMessage: null,
    });
    sendBridgeEvent('permission_decision', payload);
    permissionDialog.close();
  }, [permissionDialog.close]);

  const handlePermissionApproveAlways = useCallback((channelId: string) => {
    const payload = JSON.stringify({
      channelId,
      allow: true,
      remember: true,
      rejectMessage: null,
    });
    sendBridgeEvent('permission_decision', payload);
    permissionDialog.close();
  }, [permissionDialog.close]);

  const handlePermissionSkip = useCallback((channelId: string) => {
    const payload = JSON.stringify({
      channelId,
      allow: false,
      remember: false,
      rejectMessage: t('permission.userDenied'),
    });
    sendBridgeEvent('permission_decision', payload);
    permissionDialog.close();
  }, [permissionDialog.close, t]);

  // AskUserQuestion handlers
  const handleAskUserQuestionSubmit = useCallback((requestId: string, answers: Record<string, string | string[]>) => {
    const payload = JSON.stringify({
      requestId,
      answers,
    });
    sendBridgeEvent('ask_user_question_response', payload);
    askUserQuestionDialog.close();
  }, [askUserQuestionDialog.close]);

  const handleAskUserQuestionCancel = useCallback((requestId: string) => {
    const payload = JSON.stringify({
      requestId,
      answers: {},
    });
    sendBridgeEvent('ask_user_question_response', payload);
    askUserQuestionDialog.close();
  }, [askUserQuestionDialog.close]);

  // PlanApproval handlers
  const handlePlanApprovalApprove = useCallback((requestId: string, targetMode: string) => {
    const payload = JSON.stringify({
      requestId,
      approved: true,
      targetMode,
    });
    sendBridgeEvent('plan_approval_response', payload);
    planApprovalDialog.close();
  }, [planApprovalDialog.close]);

  const handlePlanApprovalReject = useCallback((requestId: string) => {
    const payload = JSON.stringify({
      requestId,
      approved: false,
    });
    sendBridgeEvent('plan_approval_response', payload);
    planApprovalDialog.close();
  }, [planApprovalDialog.close]);

  // Context usage dialog handlers
  const openContextUsageDialog = useCallback((requestId?: string | null, loading = false) => {
    contextUsageRequestIdRef.current = requestId ?? null;
    setContextUsageIsLoading(loading);
    setContextUsageDialogOpen(true);
  }, []);

  const updateContextUsageData = useCallback((requestId: string | null | undefined, data: ContextUsageData): boolean => {
    if (requestId !== contextUsageRequestIdRef.current) return false;
    setContextUsageData(data);
    setContextUsageIsLoading(false);
    return true;
  }, []);

  const closeContextUsageDialog = useCallback((requestId?: string | null): boolean => {
    if (requestId !== undefined && requestId !== contextUsageRequestIdRef.current) return false;
    setContextUsageDialogOpen(false);
    setContextUsageIsLoading(false);
    setContextUsageData(null);
    contextUsageRequestIdRef.current = null;
    return true;
  }, []);

  return {
    // Permission dialog
    permissionDialogOpen: permissionDialog.isOpen,
    currentPermissionRequest: permissionDialog.currentRequest,
    openPermissionDialog: permissionDialog.open,
    handlePermissionApprove,
    handlePermissionApproveAlways,
    handlePermissionSkip,

    // AskUserQuestion dialog
    askUserQuestionDialogOpen: askUserQuestionDialog.isOpen,
    currentAskUserQuestionRequest: askUserQuestionDialog.currentRequest,
    openAskUserQuestionDialog: askUserQuestionDialog.open,
    handleAskUserQuestionSubmit,
    handleAskUserQuestionCancel,

    // PlanApproval dialog
    planApprovalDialogOpen: planApprovalDialog.isOpen,
    currentPlanApprovalRequest: planApprovalDialog.currentRequest,
    openPlanApprovalDialog: planApprovalDialog.open,
    handlePlanApprovalApprove,
    handlePlanApprovalReject,

    // Rewind dialog
    rewindDialogOpen,
    setRewindDialogOpen,
    currentRewindRequest,
    setCurrentRewindRequest,
    isRewinding,
    setIsRewinding,

    // Rewind select dialog
    rewindSelectDialogOpen,
    setRewindSelectDialogOpen,

    // Context usage dialog
    contextUsageDialogOpen,
    contextUsageIsLoading,
    contextUsageData,
    openContextUsageDialog,
    updateContextUsageData,
    closeContextUsageDialog,
  };
}
