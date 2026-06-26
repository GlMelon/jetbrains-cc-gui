import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import type { AgentConfig } from '../types/agent';
import { BaseDialog, DialogHeader, DialogBody, DialogFooter } from './shared/BaseDialog';
import { UserIcon, SaveIcon, XCircleIcon, CloseIcon } from './Icons';

interface AgentDialogProps {
  isOpen: boolean;
  agent?: AgentConfig | null; // null indicates add mode
  onClose: () => void;
  onSave: (data: { name: string; prompt: string }) => void;
}

export default function AgentDialog({
  isOpen,
  agent,
  onClose,
  onSave,
}: AgentDialogProps) {
  const { t } = useTranslation();
  const isAdding = !agent;

  const [name, setName] = useState('');
  const [prompt, setPrompt] = useState('');
  const [nameError, setNameError] = useState('');

  // Initialize form
  useEffect(() => {
    if (isOpen) {
      if (agent) {
        // Edit mode
        setName(agent.name || '');
        setPrompt(agent.prompt || '');
      } else {
        // Add mode
        setName('');
        setPrompt('');
      }
      setNameError('');
    }
  }, [isOpen, agent]);

  const handleNameChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = e.target.value;
    // Limit to 20 characters max
    if (value.length <= 20) {
      setName(value);
      setNameError('');
    }
  };

  const handlePromptChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const value = e.target.value;
    // Limit to 100000 characters max
    if (value.length <= 100000) {
      setPrompt(value);
    }
  };

  const handleSave = () => {
    // Validate name
    if (!name.trim()) {
      setNameError(t('settings.agent.dialog.nameRequired'));
      return;
    }

    onSave({
      name: name.trim(),
      prompt: prompt.trim(),
    });
  };

  return (
    <BaseDialog isOpen={isOpen} onClose={onClose} size="md" ariaLabel={isAdding ? t('settings.agent.dialog.addTitle') : t('settings.agent.dialog.editTitle')}>
      <DialogHeader
        title={isAdding ? t('settings.agent.dialog.addTitle') : t('settings.agent.dialog.editTitle')}
        icon={<UserIcon size={16} style={{ fontSize: '16px', color: 'var(--violet)' }} />}
        onClose={onClose}
      />

      <DialogBody>
        <div className="form-group">
          <label htmlFor="agentName">
            {t('settings.agent.dialog.name')}
            <span className="required">*</span>
          </label>
          <div className="input-with-counter">
            <input
              id="agentName"
              type="text"
              className={`form-input ${nameError ? 'has-error' : ''}`}
              placeholder={t('settings.agent.dialog.namePlaceholder')}
              value={name}
              onChange={handleNameChange}
              maxLength={20}
            />
            <span className="char-counter">{name.length}/20</span>
          </div>
          {nameError && (
            <p className="form-error">
              <XCircleIcon size={16} />
              {nameError}
            </p>
          )}
        </div>

        <div className="form-group">
          <label htmlFor="agentPrompt">
            {t('settings.agent.dialog.prompt')}
          </label>
          <div className="textarea-with-counter">
            <textarea
              id="agentPrompt"
              className="form-textarea"
              placeholder={t('settings.agent.dialog.promptPlaceholder')}
              value={prompt}
              onChange={handlePromptChange}
              maxLength={100000}
              rows={8}
            />
            <span className="char-counter">{prompt.length}/100000</span>
          </div>
          <small className="form-hint">{t('settings.agent.dialog.promptHint')}</small>
        </div>
      </DialogBody>

      <DialogFooter>
        <button className="btn btn-secondary" onClick={onClose}>
          <CloseIcon size={16} />
          {t('common.cancel')}
        </button>
        <button className="btn btn-primary" onClick={handleSave}>
          <SaveIcon size={16} />
          {isAdding ? t('settings.agent.dialog.confirmAdd') : t('settings.agent.dialog.saveChanges')}
        </button>
      </DialogFooter>
    </BaseDialog>
  );
}
