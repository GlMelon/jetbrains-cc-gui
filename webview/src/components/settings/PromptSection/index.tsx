import { sendAction, subscribeEvent } from '../../../bridge/typed';
import { UPSTREAM, DOWNSTREAM } from '../../../generated/protocol';
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import type { PromptScope } from '../../../types/prompt';
import { usePromptManagement } from '../hooks/usePromptManagement';
import { updateGlobalPromptsCache, updateProjectPromptsCache } from '../../ChatInputBox/providers';
import PromptScopeSection from './PromptScopeSection';
import PromptDialog from '../../PromptDialog';
import ConfirmDialog from '../../ConfirmDialog';
import PromptExportDialog from './PromptExportDialog';
import PromptImportConfirmDialog from './PromptImportConfirmDialog';
import styles from './style.module.less';
import { registerLegacyAlias } from '../../../bridge';

interface PromptSectionProps {
  onSuccess?: (message: string) => void;
}

export default function PromptSection({
  onSuccess,
}: PromptSectionProps) {
  const { t } = useTranslation();

  // Use prompt management hook
  const {
    globalPrompts,
    projectPrompts,
    projectInfo,
    promptsLoading,
    promptDialog,
    deletePromptConfirm,
    importPreviewDialog,
    exportDialog,
    loadAllPrompts,
    updateGlobalPrompts,
    updateProjectPrompts,
    updateProjectInfo,
    handleAddPrompt,
    handleEditPrompt,
    handleClosePromptDialog,
    handleDeletePrompt,
    handleSavePrompt,
    confirmDeletePrompt,
    cancelDeletePrompt,
    handlePromptOperationResult,
    handleExportPrompts,
    handleCloseExportDialog,
    handleConfirmExport,
    handleImportPromptsFile,
    handlePromptImportPreviewResult,
    handleCloseImportPreview,
    handleSaveImportedPrompts,
    handlePromptImportResult,
    cleanupPromptsTimeout,
  } = usePromptManagement({ onSuccess });

  // Load project info and prompts on mount
  useEffect(() => {
    // Load project info first
    sendAction(UPSTREAM.GET_PROJECT_INFO, '{}');
    // Then load prompts
    loadAllPrompts();
    return () => cleanupPromptsTimeout();
  }, [loadAllPrompts, cleanupPromptsTimeout]);

  // Setup window callbacks（[归一化] 经 bridgeHub 订阅,替代旧 window.xxx 覆盖 + 链式转发）
  useEffect(() => {
    registerLegacyAlias('updateGlobalPrompts', DOWNSTREAM.PROMPT_GLOBAL_LIST);
    registerLegacyAlias('updateProjectPrompts', DOWNSTREAM.PROMPT_PROJECT_LIST);
    registerLegacyAlias('updateProjectInfo', DOWNSTREAM.PROMPT_PROJECT_INFO);
    registerLegacyAlias('promptOperationResult', DOWNSTREAM.PROMPT_OPERATION_RESULT);
    registerLegacyAlias('promptImportPreviewResult', DOWNSTREAM.PROMPT_IMPORT_PREVIEW);
    registerLegacyAlias('promptImportResult', DOWNSTREAM.PROMPT_IMPORT_RESULT);

    const unsubs: Array<() => void> = [];

    unsubs.push(subscribeEvent(DOWNSTREAM.PROMPT_GLOBAL_LIST, (json) => {
      try {
        const promptsList = JSON.parse(json as string);
        updateGlobalPrompts(promptsList);

        // ✅ Sync update promptProvider cache
        const promptItems = promptsList.map((prompt: any) => ({
          id: prompt.id,
          name: prompt.name,
          content: prompt.content,
          scope: 'global' as PromptScope,
        }));
        updateGlobalPromptsCache(promptItems);
      } catch (error) {
        console.error('[PromptSection] Failed to parse global prompts:', error);
      }
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.PROMPT_PROJECT_LIST, (json) => {
      try {
        const promptsList = JSON.parse(json as string);
        updateProjectPrompts(promptsList);

        // ✅ Sync update promptProvider cache
        const promptItems = promptsList.map((prompt: any) => ({
          id: prompt.id,
          name: prompt.name,
          content: prompt.content,
          scope: 'project' as PromptScope,
        }));
        updateProjectPromptsCache(promptItems);
      } catch (error) {
        console.error('[PromptSection] Failed to parse project prompts:', error);
      }
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.PROMPT_PROJECT_INFO, (json) => {
      try {
        const info = JSON.parse(json as string);
        updateProjectInfo(info);
      } catch (error) {
        console.error('[PromptSection] Failed to parse project info:', error);
      }
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.PROMPT_OPERATION_RESULT, (json) => {
      try {
        const result = JSON.parse(json as string);
        handlePromptOperationResult(result);
      } catch (error) {
        console.error('[PromptSection] Failed to parse prompt operation result:', error);
      }
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.PROMPT_IMPORT_PREVIEW, (json) => {
      try {
        const previewData = JSON.parse(json as string);
        handlePromptImportPreviewResult(previewData);
      } catch (error) {
        console.error('[PromptSection] Failed to parse prompt import preview result:', error);
      }
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.PROMPT_IMPORT_RESULT, (json) => {
      try {
        const result = JSON.parse(json as string);
        handlePromptImportResult(result);
      } catch (error) {
        console.error('[PromptSection] Failed to parse prompt import result:', error);
      }
    }));

    return () => {
      unsubs.forEach((u) => u());
    };
  }, [
    updateGlobalPrompts,
    updateProjectPrompts,
    updateProjectInfo,
    handlePromptOperationResult,
    handlePromptImportPreviewResult,
    handlePromptImportResult,
  ]);

  // Get all prompts for export dialog (combining global and project based on export scope)
  const getPromptsForExport = (scope: PromptScope) => {
    return scope === 'global' ? globalPrompts : projectPrompts;
  };

  return (
    <div className={styles.promptLibrary}>
      <h3>{t('settings.prompt.title')}</h3>
      <p className={styles.description}>{t('settings.prompt.description')}</p>

      {/* Global Prompts Section */}
      <PromptScopeSection
        title={t('settings.prompt.global')}
        scope="global"
        prompts={globalPrompts}
        loading={promptsLoading}
        onAdd={() => handleAddPrompt('global')}
        onEdit={(prompt) => handleEditPrompt(prompt, 'global')}
        onDelete={(prompt) => handleDeletePrompt(prompt, 'global')}
        onExport={() => handleExportPrompts('global')}
        onImport={() => handleImportPromptsFile('global')}
      />

      {/* Project Prompts Section */}
      {projectInfo?.available ? (
        <PromptScopeSection
          title={t('settings.prompt.projectScope', { projectName: projectInfo.name })}
          scope="project"
          prompts={projectPrompts}
          loading={promptsLoading}
          onAdd={() => handleAddPrompt('project')}
          onEdit={(prompt) => handleEditPrompt(prompt, 'project')}
          onDelete={(prompt) => handleDeletePrompt(prompt, 'project')}
          onExport={() => handleExportPrompts('project')}
          onImport={() => handleImportPromptsFile('project')}
        />
      ) : (
        <div className={styles.noProject}>
          <p>{t('settings.prompt.noProject')}</p>
        </div>
      )}

      {/* Prompt add/edit dialog */}
      <PromptDialog
        isOpen={promptDialog.isOpen}
        prompt={promptDialog.prompt}
        onClose={handleClosePromptDialog}
        onSave={handleSavePrompt}
      />

      {/* Prompt delete confirmation dialog */}
      <ConfirmDialog
        isOpen={deletePromptConfirm.isOpen}
        title={t('settings.prompt.deleteConfirmTitle')}
        message={t('settings.prompt.deleteConfirmMessage', { name: deletePromptConfirm.prompt?.name || '' })}
        confirmText={t('common.delete')}
        cancelText={t('common.cancel')}
        onConfirm={confirmDeletePrompt}
        onCancel={cancelDeletePrompt}
      />

      {/* Prompt export dialog */}
      {exportDialog.isOpen && (
        <PromptExportDialog
          prompts={getPromptsForExport(exportDialog.scope)}
          onConfirm={handleConfirmExport}
          onCancel={handleCloseExportDialog}
        />
      )}

      {/* Prompt import preview dialog */}
      {importPreviewDialog.isOpen && importPreviewDialog.previewData && (
        <PromptImportConfirmDialog
          previewData={importPreviewDialog.previewData}
          onConfirm={(selectedIds, strategy) => handleSaveImportedPrompts(selectedIds, strategy, importPreviewDialog.scope)}
          onCancel={handleCloseImportPreview}
        />
      )}
    </div>
  );
}
