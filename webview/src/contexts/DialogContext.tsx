import { createContext, useContext, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useDialogManagement } from '../hooks/useDialogManagement';

type DialogManagementValue = ReturnType<typeof useDialogManagement>;

const DialogContext = createContext<DialogManagementValue | null>(null);

/**
 * Hosts useDialogManagement so all dialog-orchestration state (permission /
 * ask-user / plan approval / rewind dialogs) lives in a single provider.
 *
 * Stage 4 of TASK-P1-01.
 */
export function DialogProvider({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  // useDialogManagement memoizes its return value internally, so this only
  // produces a new context reference when dialog state actually changes.
  const value = useDialogManagement({ t });

  return <DialogContext.Provider value={value}>{children}</DialogContext.Provider>;
}

export function useDialogs(): DialogManagementValue {
  const ctx = useContext(DialogContext);
  if (ctx === null) {
    throw new Error('useDialogs must be used within a DialogProvider');
  }
  return ctx;
}

export { DialogContext };
