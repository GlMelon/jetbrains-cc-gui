import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { SKILL_FIELD_CONTROL } from '../../types/skill';
import type {
  SkillDocumentField,
  SkillDocumentResult,
  SkillDocumentSavePayload,
  SkillFieldValue,
} from '../../types/skill';
import { BaseDialog, DialogBody, DialogFooter, DialogHeader } from '../shared/BaseDialog';
import { ClickSpark } from '../react-bits';
import { EditIcon, FileCodeIcon, RefreshIcon, SaveIcon } from '../Icons';
import { UnifiedLoader } from '../UnifiedLoader';

interface SkillEditorDialogProps {
  isOpen: boolean;
  skillName: string;
  loading: boolean;
  saving: boolean;
  document: SkillDocumentResult | null;
  onClose: () => void;
  onReload: () => void;
  onOpenInIde: () => void;
  onSave: (payload: SkillDocumentSavePayload) => void;
}

type DraftValues = Record<string, SkillFieldValue>;

function initialDraft(fields: SkillDocumentField[]): DraftValues {
  return Object.fromEntries(fields.map((field) => [field.key, field.value]));
}

function valuesEqual(left: SkillFieldValue, right: SkillFieldValue): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}

export function SkillEditorDialog({
  isOpen,
  skillName,
  loading,
  saving,
  document,
  onClose,
  onReload,
  onOpenInIde,
  onSave,
}: SkillEditorDialogProps) {
  const { t } = useTranslation();
  const fields = useMemo(() => document?.fields ?? [], [document?.fields]);
  const [draft, setDraft] = useState<DraftValues>({});
  const [body, setBody] = useState('');

  useEffect(() => {
    setDraft(initialDraft(fields));
    setBody(document?.body ?? '');
  }, [document?.revision, document?.body, fields]);

  const updateField = (key: string, value: SkillFieldValue) => {
    setDraft((current) => ({ ...current, [key]: value }));
  };

  const handleSave = () => {
    const changes: Record<string, SkillFieldValue> = {};
    for (const field of fields) {
      const nextValue = draft[field.key] ?? null;
      if (!valuesEqual(field.value, nextValue)) {
        changes[field.key] = nextValue;
      }
    }
    onSave({ changes, body });
  };

  const renderField = (field: SkillDocumentField) => {
    const value = draft[field.key] ?? field.value;
    const label = t(field.labelKey);

    switch (field.control) {
      case SKILL_FIELD_CONTROL.BOOLEAN:
        return (
          <label className="skill-editor-checkbox">
            <input
              type="checkbox"
              checked={value === true}
              onChange={(event) => updateField(field.key, event.target.checked)}
              disabled={saving}
            />
            <span>{label}</span>
          </label>
        );
      case SKILL_FIELD_CONTROL.STRING_LIST:
        return (
          <textarea
            className="form-textarea skill-editor-list"
            value={Array.isArray(value) ? value.join('\n') : ''}
            onChange={(event) => updateField(field.key, event.target.value.split('\n'))}
            rows={4}
            disabled={saving}
            aria-label={label}
          />
        );
      case SKILL_FIELD_CONTROL.TEXTAREA:
        return (
          <textarea
            className="form-textarea"
            value={typeof value === 'string' ? value : ''}
            onChange={(event) => updateField(field.key, event.target.value)}
            maxLength={field.maxLength}
            rows={4}
            disabled={saving}
            aria-label={label}
          />
        );
      case SKILL_FIELD_CONTROL.TEXT:
      default:
        return (
          <input
            className="form-input"
            type="text"
            value={typeof value === 'string' ? value : ''}
            onChange={(event) => updateField(field.key, event.target.value)}
            maxLength={field.maxLength}
            disabled={saving}
            aria-label={label}
          />
        );
    }
  };

  const saveDisabled =
    loading ||
    saving ||
    !document?.success ||
    !document.editable ||
    Boolean(document.parseError) ||
    Boolean(document.conflict);

  return (
    <BaseDialog
      isOpen={isOpen}
      onClose={onClose}
      size="lg"
      className="skill-editor-dialog"
      ariaLabel={t('skills.editor.title', { name: skillName })}
      animation="pop"
    >
      <DialogHeader
        title={t('skills.editor.title', { name: skillName })}
        icon={<EditIcon size={16} />}
        onClose={onClose}
      />
      <DialogBody className="skill-editor-body">
        {loading ? (
          <div className="loading-state">
            <UnifiedLoader type="pulse" size={20} />
            <p>{t('skills.editor.loading')}</p>
          </div>
        ) : (
          <>
            {document?.error && (
              <div className={`skill-editor-alert ${document.conflict ? 'warning' : 'error'}`}>
                <strong>
                  {document.conflict
                    ? t('skills.editor.conflictTitle')
                    : t('skills.editor.readErrorTitle')}
                </strong>
                <span>{document.error}</span>
              </div>
            )}

            {document?.success && (
              <>
                <div className="skill-editor-fields">
                  {fields.map((field) => (
                    <div
                      className={`form-group ${
                        field.control === SKILL_FIELD_CONTROL.TEXTAREA ||
                        field.control === SKILL_FIELD_CONTROL.STRING_LIST
                          ? 'skill-editor-wide-field'
                          : ''
                      }`}
                      key={field.key}
                    >
                      {field.control !== SKILL_FIELD_CONTROL.BOOLEAN && (
                        <label>
                          {t(field.labelKey)}
                          {field.required && <span className="required">*</span>}
                        </label>
                      )}
                      {renderField(field)}
                      {field.control === SKILL_FIELD_CONTROL.STRING_LIST && (
                        <small className="form-hint">{t('skills.editor.onePerLine')}</small>
                      )}
                    </div>
                  ))}
                </div>

                <div className="form-group skill-editor-markdown">
                  <label>{t('skills.editor.markdownBody')}</label>
                  <textarea
                    className="form-textarea"
                    value={body}
                    onChange={(event) => setBody(event.target.value)}
                    rows={14}
                    disabled={saving}
                    spellCheck={false}
                    aria-label={t('skills.editor.markdownBody')}
                  />
                </div>
              </>
            )}
          </>
        )}
      </DialogBody>
      <DialogFooter>
        <button className="btn btn-secondary" type="button" onClick={onOpenInIde}>
          <FileCodeIcon size={16} />
          {t('skills.editor.openInIde')}
        </button>
        <button
          className="btn btn-secondary"
          type="button"
          onClick={onReload}
          disabled={loading || saving}
        >
          <RefreshIcon size={16} />
          {t('skills.editor.reload')}
        </button>
        <ClickSpark>
          <button
            className="btn btn-primary"
            type="button"
            onClick={handleSave}
            disabled={saveDisabled}
          >
            <SaveIcon size={16} />
            {saving ? t('skills.editor.saving') : t('common.save')}
          </button>
        </ClickSpark>
      </DialogFooter>
    </BaseDialog>
  );
}
