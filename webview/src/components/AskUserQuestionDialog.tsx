import { useEffect, useState, useRef, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { formatCountdown } from '../utils/helpers';
import { useDialogCountdownTimeout } from '../hooks/useDialogCountdownTimeout';
import { DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS } from '../utils/permissionDialogTimeout';
import { isEditableEventTarget } from '../utils/isEditableEventTarget';
import './AskUserQuestionDialog.css';
import MarkdownBlock from './MarkdownBlock';
import { CheckIcon, ChevronDownIcon, ChevronUpIcon, CircleFilledIcon, CircleIcon, AlertIcon } from './Icons';
import { ClickSpark, FadeContent, GradientText, ProgressRing } from './react-bits';

// Special marker to identify the "Other" option
const OTHER_OPTION_MARKER = '__OTHER__';

// Maximum length limit for custom input
const MAX_CUSTOM_INPUT_LENGTH = 2000;

interface QuestionOption {
  label: string;
  description: string;
}

interface Question {
  question: string;
  header: string;
  options: QuestionOption[];
  multiSelect: boolean;
  /**
   * Supporting detail rendered with the question but kept out of the option
   * labels — DSH carries the plan under review here, so dropping it left the
   * user approving a plan they could not read.
   */
  detail?: string;
  intent?: QuestionIntent;
}


export interface AskUserQuestionRequest {
  requestId: string;
  toolName: string;
  questions: Question[];
  provider?: 'claude' | 'codex' | 'dsh';
}

/** Presentation intent a capable UI may recognise (DSH `plan-review` today). */
export interface QuestionIntent {
  kind: string;
  /** Option label that approves, for `plan-review`. */
  approve?: string;
}

interface AskUserQuestionDialogProps {
  isOpen: boolean;
  request: AskUserQuestionRequest | null;
  onSubmit: (requestId: string, answers: Record<string, string | string[]>) => void;
  onCancel: (requestId: string) => void;
  timeoutSeconds?: number;
}

/** Carry a caller-declared presentation intent through to the dialog. */
function normalizeIntent(raw: any): QuestionIntent | undefined {
  if (!raw || typeof raw !== 'object' || typeof raw.kind !== 'string' || !raw.kind) {
    return undefined;
  }
  return {
    kind: raw.kind,
    ...(typeof raw.approve === 'string' && raw.approve ? { approve: raw.approve } : {}),
  };
}

function normalizeQuestion(raw: any): Question | null {
  if (!raw || typeof raw !== 'object') return null;
  const questionText = typeof raw.question === 'string' ? raw.question : (typeof raw.text === 'string' ? raw.text : '');
  const header = typeof raw.header === 'string' ? raw.header : '';
  const multiSelect = typeof raw.multiSelect === 'boolean' ? raw.multiSelect : false;
  const detail = typeof raw.detail === 'string' ? raw.detail : '';
  const intent = normalizeIntent(raw.intent);
  const rawOptions = Array.isArray(raw.options) ? raw.options : (Array.isArray(raw.choices) ? raw.choices : []);
  const options: QuestionOption[] = rawOptions.flatMap((opt: any): QuestionOption[] => {
    if (typeof opt === 'string') return [{ label: opt, description: '' }];
    if (!opt || typeof opt !== 'object') return [];
    const label = typeof opt.label === 'string' ? opt.label : (typeof opt.value === 'string' ? opt.value : '');
    const description = typeof opt.description === 'string' ? opt.description : '';
    if (!label) return [];
    return [{ label, description }];
  });
  if (!questionText) return null;
  // `detail` (DSH's plan under review) and `intent` are part of the question,
  // not decoration: dropping them made a plan-review unanswerable in cc-gui.
  return {
    question: questionText,
    header,
    options,
    multiSelect,
    ...(detail ? { detail } : {}),
    ...(intent ? { intent } : {}),
  };
}

const AskUserQuestionDialog = ({
  isOpen,
  request,
  onSubmit,
  onCancel,
  timeoutSeconds = DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS,
}: AskUserQuestionDialogProps) => {
  const { t } = useTranslation();
  const [answers, setAnswers] = useState<Record<string, Set<string>>>({});
  const [customInputs, setCustomInputs] = useState<Record<string, string>>({});
  const [currentQuestionIndex, setCurrentQuestionIndex] = useState(0);
  const [isCollapsed, setIsCollapsed] = useState(false);

  const customInputRef = useRef<HTMLTextAreaElement>(null);
  const handleTimeout = useCallback(() => {
    if (request) {
      onCancel(request.requestId);
    }
  }, [request, onCancel]);

  const { remainingSeconds, isTimeWarning, markSubmitted } = useDialogCountdownTimeout({
    isOpen,
    requestKey: request?.requestId,
    timeoutSeconds,
    onTimeout: handleTimeout,
  });
  const normalizedQuestions = (Array.isArray(request?.questions) ? request!.questions : [])
    .flatMap((raw) => {
      const question = normalizeQuestion(raw);
      return question === null ? [] : [question];
    });
  const isCodexRequest = request?.provider === 'codex' || request?.toolName === 'request_user_input';
  const isDshRequest = request?.provider === 'dsh';
  const dialogTitle = isDshRequest
    ? t('askUserQuestion.dshTitle', 'DeepSeek Harness 有一些问题想问你')
    : isCodexRequest
      ? t('askUserQuestion.codexTitle', 'Codex 有一些问题想问你')
      : t('askUserQuestion.title', 'Claude 有一些问题想问你');

  const ringProgress = timeoutSeconds > 0 ? Math.max(0, remainingSeconds / timeoutSeconds) : 0;
  const ringColor = isTimeWarning
    ? 'var(--text-warning, #e0b341)'
    : 'var(--accent-primary, #6d8cff)';

  const handleCancel = useCallback(() => {
    if (request && markSubmitted()) {
      onCancel(request.requestId);
    }
  }, [request, markSubmitted, onCancel]);

  useEffect(() => {
    if (isOpen && request) {
      const questions = (Array.isArray(request.questions) ? request.questions : [])
        .flatMap((raw) => {
          const question = normalizeQuestion(raw);
          return question === null ? [] : [question];
        });

      const initialAnswers: Record<string, Set<string>> = {};
      const initialCustomInputs: Record<string, string> = {};
      questions.forEach((q) => {
        initialAnswers[q.question] = new Set<string>();
        initialCustomInputs[q.question] = '';
      });
      setAnswers(initialAnswers);
      setCustomInputs(initialCustomInputs);
      setCurrentQuestionIndex(0);
      setIsCollapsed(false);
    }
  }, [isOpen, request?.requestId]);

  // Keyboard event handling - separate effect to avoid frequent listener registration/removal
  useEffect(() => {
    if (!isOpen || !request) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (isEditableEventTarget(e.target)) {
        return;
      }

      if (e.key === 'Escape') {
        handleCancel();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, request, handleCancel]);

  // A question without options answers through the always-visible custom
  // input alone — focus it on arrival so free-text questions are usable
  // without discovering the (hidden) "Other" row first.
  const normalizedRef = useRef(normalizedQuestions);
  normalizedRef.current = normalizedQuestions;
  useEffect(() => {
    const questions = normalizedRef.current;
    const question = questions[Math.max(0, Math.min(currentQuestionIndex, questions.length - 1))];
    if (isOpen && question && question.options.length === 0) {
      customInputRef.current?.focus();
    }
  }, [isOpen, request?.requestId, currentQuestionIndex]);

  if (!isOpen || !request) {
    return null;
  }

  if (normalizedQuestions.length === 0) {
    return (
      <div className="permission-dialog-overlay">
        <div className="ask-user-question-dialog">
          <h3 className="ask-user-question-dialog-title">
            {dialogTitle}
          </h3>
          <p className="question-text">
            {t('askUserQuestion.invalidFormat', '问题数据格式不支持，请取消后重试。')}
          </p>
          <div className="ask-user-question-dialog-actions">
            <button className="action-button secondary" onClick={handleCancel}>
              {t('askUserQuestion.cancel', '取消')}
            </button>
          </div>
        </div>
      </div>
    );
  }

  // FIX: Ensure currentQuestionIndex does not go out of bounds
  // When request changes cause normalizedQuestions length to decrease,
  // currentQuestionIndex may still hold the old value (since useEffect state updates are async)
  const safeQuestionIndex = Math.max(0, Math.min(currentQuestionIndex, normalizedQuestions.length - 1));
  const currentQuestion = normalizedQuestions[safeQuestionIndex];

  // FIX: Additional defensive check to prevent currentQuestion being undefined in edge cases
  // This can happen during React concurrent rendering or state update timing issues
  if (!currentQuestion) {
    return (
      <div className="permission-dialog-overlay">
        <div className="ask-user-question-dialog">
          <h3 className="ask-user-question-dialog-title">
            {dialogTitle}
          </h3>
          <p className="question-text">
            {t('askUserQuestion.loading', '正在加载问题...')}
          </p>
          <div className="ask-user-question-dialog-actions">
            <button className="action-button secondary" onClick={handleCancel}>
              {t('askUserQuestion.cancel', '取消')}
            </button>
          </div>
        </div>
      </div>
    );
  }

  const isLastQuestion = safeQuestionIndex === normalizedQuestions.length - 1;
  const currentAnswerSet = answers[currentQuestion.question] || new Set<string>();
  const currentCustomInput = customInputs[currentQuestion.question] || '';
  const isOtherSelected = currentAnswerSet.has(OTHER_OPTION_MARKER);

  const handleOptionToggle = (label: string) => {
    setAnswers((prev) => {
      const newAnswers = { ...prev };
      const currentSet = new Set(newAnswers[currentQuestion.question] || []);

      if (currentQuestion.multiSelect) {
        // Multi-select mode: toggle option
        if (currentSet.has(label)) {
          currentSet.delete(label);
        } else {
          currentSet.add(label);
        }
      } else {
        // Single-select mode: clear and set new option
        currentSet.clear();
        currentSet.add(label);
      }

      newAnswers[currentQuestion.question] = currentSet;
      return newAnswers;
    });

    // Picking a predefined option on a single-select question clears the box:
    // that question can only carry one value, and leftover text would be
    // ghost content that looks present but is not submitted.
    if (!currentQuestion.multiSelect && label !== OTHER_OPTION_MARKER) {
      setCustomInputs((prev) => ({ ...prev, [currentQuestion.question]: '' }));
    }

    // Auto-focus the input field when "Other" option is selected
    if (label === OTHER_OPTION_MARKER) {
      setTimeout(() => {
        customInputRef.current?.focus();
      }, 0);
    }
  };

  const handleCustomInputChange = (value: string) => {
    // Limit input length to prevent excessively long input
    const sanitizedValue = value.slice(0, MAX_CUSTOM_INPUT_LENGTH);
    setCustomInputs((prev) => ({
      ...prev,
      [currentQuestion.question]: sanitizedValue,
    }));

    // Typing selects the "Other" marker automatically (syncOtherSelection):
    // on a single-select question it replaces the picked option, the only
    // value that question can carry; on multi-select it joins the set.
    // Emptying the box releases the marker again in both modes — an "Other"
    // answer without text answers nothing, and a checked row over an empty
    // box would block Submit.
    setAnswers((prev) => {
      const currentSet = new Set(prev[currentQuestion.question] || []);
      const markerSelected = currentSet.has(OTHER_OPTION_MARKER);
      if (sanitizedValue.trim() && !markerSelected) {
        if (currentQuestion.multiSelect) {
          currentSet.add(OTHER_OPTION_MARKER);
        } else {
          currentSet.clear();
          currentSet.add(OTHER_OPTION_MARKER);
        }
        return { ...prev, [currentQuestion.question]: currentSet };
      }
      if (!sanitizedValue.trim() && markerSelected) {
        if (currentQuestion.multiSelect) {
          currentSet.delete(OTHER_OPTION_MARKER);
        } else {
          currentSet.clear();
        }
        return { ...prev, [currentQuestion.question]: currentSet };
      }
      return prev;
    });
  };

  const handleNext = () => {
    if (isLastQuestion) {
      handleSubmitFinal();
    } else {
      setCurrentQuestionIndex((prev) => prev + 1);
    }
  };

  const handleBack = () => {
    if (safeQuestionIndex > 0) {
      setCurrentQuestionIndex((prev) => Math.max(0, prev - 1));
    }
  };

  const handleSubmitFinal = () => {
    if (!markSubmitted()) return;

    const formattedAnswers: Record<string, string | string[]> = {};
    normalizedQuestions.forEach((q) => {
      const selectedSet = answers[q.question] || new Set<string>();
      const customText = (customInputs[q.question] || '').trim();

      // Filter out the "Other" marker, get actually selected options
      const selectedLabels = Array.from(selectedSet).filter(label => label !== OTHER_OPTION_MARKER);

      // The typed answer always travels with the submission:
      // - single-select: the custom answer wins over a picked label (the
      //   precedence the DSH encoding expects, where `custom` overrides),
      // - multi-select: labels plus the note.
      if (customText && !q.multiSelect) {
        formattedAnswers[q.question] = customText;
        return;
      }
      if (customText) {
        selectedLabels.push(customText);
      }

      if (selectedLabels.length > 0) {
        formattedAnswers[q.question] = q.multiSelect ? selectedLabels : selectedLabels[0]!;
      }
    });

    onSubmit(request.requestId, formattedAnswers);
  };

  // Check if we can proceed:
  // 1. A regular option (not "Other") is selected
  // 2. Or the always-visible custom input carries a typed answer on its own
  const hasRegularSelection = Array.from(currentAnswerSet).some(label => label !== OTHER_OPTION_MARKER);
  const hasValidCustomInput = currentCustomInput.trim().length > 0;
  const canProceed = hasRegularSelection || hasValidCustomInput;

  return (
    <div className={`permission-dialog-overlay ${isCollapsed ? 'collapsed-mode' : ''}`}>
      <div className={`ask-user-question-dialog ${isCollapsed ? 'collapsed' : 'expanded'} ${isTimeWarning ? 'time-warning' : ''}`}>
        <div className="u-aurora-strip" />
        {/* Header area - with collapse/expand button */}
        <div className="ask-user-question-dialog-header">
          <h3 className="ask-user-question-dialog-title">
            <GradientText colors={['#6d8cff', '#b06cff']} angle={90}>{dialogTitle}</GradientText>
            <span className="u-badge u-badge--info" style={{ marginLeft: 8, verticalAlign: 'middle' }}>
              {t('askUserQuestion.badge', '提问')}
            </span>
          </h3>
          <button
            className="collapse-toggle-button"
            onClick={() => setIsCollapsed(!isCollapsed)}
            title={isCollapsed ? t('askUserQuestion.expand', '展开') : t('askUserQuestion.collapse', '收起')}
            aria-label={isCollapsed ? t('askUserQuestion.expand', '展开') : t('askUserQuestion.collapse', '收起')}
            aria-expanded={!isCollapsed}
          >
            {isCollapsed ? <ChevronUpIcon size={16} /> : <ChevronDownIcon size={16} />}
          </button>
        </div>

        {/* Timeout warning notice */}
        {isTimeWarning && !isCollapsed && (
          <FadeContent duration={180} offset={8}>
            <div className="timeout-warning-banner">
              <AlertIcon size={16} />
              <span>{t('askUserQuestion.timeoutWarning', '请尽快回答，对话框将在 {{seconds}} 秒后自动关闭', { seconds: remainingSeconds })}</span>
            </div>
          </FadeContent>
        )}

        {/* Brief hint in collapsed state */}
        {isCollapsed ? (
          <div className="collapsed-hint">
            <span className="collapsed-progress">
              {t('askUserQuestion.progress', '问题 {{current}} / {{total}}', {
                current: safeQuestionIndex + 1,
                total: normalizedQuestions.length,
              })}
            </span>
            {isTimeWarning && (
              <span className="collapsed-timer warning">
                <AlertIcon size={16} />
                {formatCountdown(remainingSeconds)}
              </span>
            )}
            <ClickSpark>
              <button
                className="action-button primary expand-button"
                onClick={() => setIsCollapsed(false)}
              >
                {t('askUserQuestion.clickToAnswer', '点击回答')}
              </button>
            </ClickSpark>
          </div>
        ) : (
          <>
            <div className="ask-user-question-dialog-progress-row">
              <span className="ask-user-question-dialog-progress">
                {t('askUserQuestion.progress', '问题 {{current}} / {{total}}', {
                  current: safeQuestionIndex + 1,
                  total: normalizedQuestions.length,
                })}
              </span>
              {/* Countdown ring (react-bits ProgressRing + overlay number) */}
              <div className="u-ring" style={{ width: 34, height: 34 }} title={formatCountdown(remainingSeconds)}>
                <ProgressRing size={34} strokeWidth={3} progress={ringProgress} color={ringColor} decorative />
                <span className="u-ring-num">{formatCountdown(remainingSeconds)}</span>
              </div>
            </div>

            {/* Question area */}
            <div className="ask-user-question-dialog-question">
              <div className="question-header">
                <span className="question-tag">{currentQuestion.header}</span>
              </div>
              <p className="question-text">{currentQuestion.question}</p>

              {/* Supporting detail (DSH plan-review) rendered above the options */}
              {currentQuestion.detail && (
                <div className="question-detail">
                  {currentQuestion.intent?.kind === 'plan-review' && (
                    <div className="question-detail-label">
                      {t('askUserQuestion.planDetail', '计划内容')}
                    </div>
                  )}
                  <MarkdownBlock content={currentQuestion.detail} />
                </div>
              )}

              {/* Options list */}
              <div className="question-options">
                {currentQuestion.options.map((option: QuestionOption) => {
                  const isSelected = currentAnswerSet.has(option.label);
                  return (
                    <button
                      key={option.label}
                      className={`question-option ${isSelected ? 'selected' : ''}`}
                      onClick={() => handleOptionToggle(option.label)}
                    >
                      <div className="option-checkbox">
                        {currentQuestion.multiSelect ? (
                          isSelected ? <CheckIcon size={16} /> : null
                        ) : (
                          isSelected ? <CircleFilledIcon size={16} /> : <CircleIcon size={16} />
                        )}
                      </div>
                      <div className="option-content">
                        <div className="option-label">{option.label}</div>
                        <div className="option-description">{option.description}</div>
                      </div>
                    </button>
                  );
                })}

                {/* "Other" option — only meaningful when predefined options exist;
                    a question without options answers through the input below. */}
                {currentQuestion.options.length > 0 && (
                  <button
                    className={`question-option other-option ${isOtherSelected ? 'selected' : ''}`}
                    onClick={() => handleOptionToggle(OTHER_OPTION_MARKER)}
                  >
                    <div className="option-checkbox">
                      {currentQuestion.multiSelect ? (
                        isOtherSelected ? <CheckIcon size={16} /> : null
                      ) : (
                        isOtherSelected ? <CircleFilledIcon size={16} /> : <CircleIcon size={16} />
                      )}
                    </div>
                    <div className="option-content">
                      <div className="option-label">{t('askUserQuestion.otherOption', '其他')}</div>
                      <div className="option-description">{t('askUserQuestion.otherOptionDesc', '输入自定义答案')}</div>
                    </div>
                  </button>
                )}
              </div>

              {/* Custom input field — always available, so an option plus a
                  note, or a free-text answer on its own, can be submitted */}
              <div className="custom-input-container">
                <textarea
                  ref={customInputRef}
                  className="custom-input"
                  value={currentCustomInput}
                  onChange={(e) => handleCustomInputChange(e.target.value)}
                  placeholder={t('askUserQuestion.customInputPlaceholder', '请输入您的答案...')}
                  aria-label={t('askUserQuestion.customInputPlaceholder', '请输入您的答案...')}
                  rows={3}
                  maxLength={MAX_CUSTOM_INPUT_LENGTH}
                />
              </div>

              {/* Hint text */}
              {currentQuestion.multiSelect && (
                <p className="question-hint">
                  {t('askUserQuestion.multiSelectHint', '可以选择多个选项')}
                </p>
              )}
            </div>

            {/* Action buttons */}
            <div className="ask-user-question-dialog-actions">
              <button
                className="action-button secondary"
                onClick={handleCancel}
              >
                {t('askUserQuestion.cancel', '取消')}
              </button>

              <div className="action-buttons-right">
                {safeQuestionIndex > 0 && (
                  <button
                    className="action-button secondary"
                    onClick={handleBack}
                  >
                    {t('askUserQuestion.back', '上一步')}
                  </button>
                )}

                <ClickSpark>
                  <button
                    className={`action-button primary ${!canProceed ? 'disabled' : ''}`}
                    onClick={handleNext}
                    disabled={!canProceed}
                  >
                    {isLastQuestion
                      ? t('askUserQuestion.submit', '提交')
                      : t('askUserQuestion.next', '下一步')}
                  </button>
                </ClickSpark>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
};

export default AskUserQuestionDialog;
