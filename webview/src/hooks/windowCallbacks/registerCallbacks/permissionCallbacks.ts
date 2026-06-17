/**
 * permissionCallbacks.ts
 *
 * Registers window bridge callbacks for permission dialogs:
 * showPermissionDialog, showAskUserQuestionDialog, showPlanApprovalDialog.
 * Also drains any pending dialog requests queued before React mounted.
 *
 * [归一化重构] 对话框回调经 compat 别名转发到 bridgeHub。pending 数组 drain 保留
 * (累加型:挂载前多次推送全部保留,按序消费)。drain 通过 window.showPermissionDialog?(payload)
 * 调用,触发 compat 别名 → dispatch → 订阅者。
 */

import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import { bridgeHub, registerLegacyAlias } from '../../../bridge';

export function registerPermissionCallbacks(options: UseWindowCallbacksOptions): void {
  const {
    openPermissionDialog,
    openAskUserQuestionDialog,
    openPlanApprovalDialog,
  } = options;

  // [归一化] showPermissionDialog → dialog.permission
  registerLegacyAlias('showPermissionDialog', 'dialog.permission');
  bridgeHub.subscribe('dialog.permission', (json) => {
    try {
      const request = JSON.parse(json as string);
      openPermissionDialog(request);
    } catch (error) {
      console.error('[Frontend] Failed to parse permission request:', error);
    }
  });

  // Drain pending (累加型:挂载前多次推送全部保留)
  if (
    Array.isArray(window.__pendingPermissionDialogRequests) &&
    window.__pendingPermissionDialogRequests.length > 0
  ) {
    const pending = window.__pendingPermissionDialogRequests.slice();
    window.__pendingPermissionDialogRequests = [];
    for (const payload of pending) {
      window.showPermissionDialog?.(payload);
    }
  }

  // [归一化] showAskUserQuestionDialog → dialog.ask_user_question
  registerLegacyAlias('showAskUserQuestionDialog', 'dialog.ask_user_question');
  bridgeHub.subscribe('dialog.ask_user_question', (json) => {
    try {
      const request = JSON.parse(json as string);
      openAskUserQuestionDialog(request);
    } catch (error) {
      console.error('[Frontend] Failed to parse ask user question request:', error);
    }
  });

  // Drain pending (累加型)
  if (
    Array.isArray(window.__pendingAskUserQuestionDialogRequests) &&
    window.__pendingAskUserQuestionDialogRequests.length > 0
  ) {
    const pending = window.__pendingAskUserQuestionDialogRequests.slice();
    window.__pendingAskUserQuestionDialogRequests = [];
    for (const payload of pending) {
      window.showAskUserQuestionDialog?.(payload);
    }
  }

  // [归一化] showPlanApprovalDialog → dialog.plan_approval
  registerLegacyAlias('showPlanApprovalDialog', 'dialog.plan_approval');
  bridgeHub.subscribe('dialog.plan_approval', (json) => {
    try {
      const request = JSON.parse(json as string);
      openPlanApprovalDialog(request);
    } catch (error) {
      console.error('[Frontend] Failed to parse plan approval request:', error);
    }
  });

  // Drain pending (累加型)
  if (
    Array.isArray(window.__pendingPlanApprovalDialogRequests) &&
    window.__pendingPlanApprovalDialogRequests.length > 0
  ) {
    const pending = window.__pendingPlanApprovalDialogRequests.slice();
    window.__pendingPlanApprovalDialogRequests = [];
    for (const payload of pending) {
      window.showPlanApprovalDialog?.(payload);
    }
  }
}
