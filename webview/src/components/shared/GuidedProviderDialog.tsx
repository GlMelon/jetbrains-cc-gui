import { type CSSProperties, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { CheckIcon, ChevronLeftIcon, ChevronRightIcon } from '../Icons';
import { BaseDialog, DialogBody, DialogFooter, DialogHeader, type DialogSize } from './BaseDialog';
import { ClickSpark } from '../react-bits';


/**
 * 引导式步骤定义。{@code id} 作为 React key,{@code title} 渲染在步骤指示器中。
 */
export interface GuidedStep {
  id: string;
  title: string;
}

const STEPPER_STYLE: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: '4px',
  padding: '12px 20px 0',
};

export interface GuidedProviderDialogProps {
  isOpen: boolean;
  onClose: () => void;
  ariaLabel: string;
  steps: GuidedStep[];
  currentStep: number;
  onStepChange: (step: number) => void;
  canProceed?: boolean;
  onFinish: () => void;
  finishLabel?: string;
  size?: DialogSize;
  children: ReactNode;
}

const STEP_ITEM_STYLE: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: '6px',
  flexShrink: 0,
};

const CONNECTOR_STYLE: CSSProperties = {
  flex: 1,
  height: '2px',
  minWidth: '12px',
  background: 'var(--border-color, #e2e2e2)',
  marginLeft: '4px',
};

const STEP_DOT_BASE: CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  width: '22px',
  height: '22px',
  borderRadius: '50%',
  fontSize: '12px',
  fontWeight: 600,
  flexShrink: 0,
};

const dotStyleFor = (state: 'done' | 'current' | 'future'): CSSProperties => {
  if (state === 'done') {
    return { ...STEP_DOT_BASE, background: 'var(--accent-primary, #4ea1ff)', color: '#fff' };
  }
  if (state === 'current') {
    return {
      ...STEP_DOT_BASE,
      background: 'var(--accent-primary, #4ea1ff)',
      color: '#fff',
      boxShadow: '0 0 0 3px rgba(78,161,255,0.25)',
    };
  }
  return { ...STEP_DOT_BASE, background: 'var(--bg-hover, #f0f0f0)', color: 'var(--text-tertiary, #999)' };
};

const titleStyleFor = (state: 'done' | 'current' | 'future'): CSSProperties => {
  const base: CSSProperties = { fontSize: '13px', whiteSpace: 'nowrap' };
  if (state === 'current') {
    return { ...base, fontWeight: 600, color: 'var(--text-primary, #222)' };
  }
  if (state === 'done') {
    return { ...base, color: 'var(--text-secondary, #666)' };
  }
  return { ...base, color: 'var(--text-tertiary, #999)' };
};

/**
 * 引导式 Provider 配置弹窗骨架 —— 可复用的多步骤向导壳。
 *
 * <p>职责分离:本组件只负责「步骤导航」(指示器 + Back/Next/Finish),
 * 不关心每一步的具体内容。父组件按 {@code currentStep} 渲染 {@code children},
 * 并通过 {@code canProceed} 控制前进门禁、{@code onFinish} 收尾。
 *
 * <p>复用 {@link BaseDialog} 的统一遮罩/ESC/无障碍能力,三 provider
 * (Claude/OpenCode/Codex)的配置弹窗共享此骨架。
 */
export function GuidedProviderDialog({
  isOpen,
  onClose,
  ariaLabel,
  steps,
  currentStep,
  onStepChange,
  canProceed = true,
  onFinish,
  finishLabel,
  size = 'lg',
  children,
}: GuidedProviderDialogProps) {
  const { t } = useTranslation();
  const total = steps.length;
  const isFirst = currentStep <= 0;
  const isLast = currentStep >= total - 1;
  const backLabel = t('common.back', 'Back');
  const nextLabel = t('common.next', 'Next');
  const doneLabel = finishLabel ?? t('common.finish', 'Finish');

  return (
    <BaseDialog isOpen={isOpen} onClose={onClose} size={size} ariaLabel={ariaLabel} animation="pop">
      <div className="provider-dialog guided-provider-dialog">
        <DialogHeader title={ariaLabel} onClose={onClose} />
        <div className="guided-stepper" role="list" style={STEPPER_STYLE}>
          {steps.map((step: GuidedStep, idx: number) => {
            const state: 'done' | 'current' | 'future' =
              idx < currentStep ? 'done' : idx === currentStep ? 'current' : 'future';
            return (
              <div key={step.id} className={`guided-step guided-step--${state}`} style={STEP_ITEM_STYLE}>
                <span className="guided-step-dot" style={dotStyleFor(state)} role="listitem">
                  {state === 'done' ? <CheckIcon size={14} /> : idx + 1}
                </span>
                <span className="guided-step-title" style={titleStyleFor(state)}>{step.title}</span>
                {idx < total - 1 && <span className="guided-step-connector" style={CONNECTOR_STYLE} />}
              </div>
            );
          })}
        </div>
        <DialogBody>{children}</DialogBody>
        <DialogFooter>
          <button
            type="button"
            className="btn btn-secondary"
            onClick={() => onStepChange(currentStep - 1)}
            disabled={isFirst}
          >
            <ChevronLeftIcon size={16} />
            {backLabel}
          </button>
          {isLast ? (
            <ClickSpark>
              <button
                type="button"
                className="btn btn-primary"
                onClick={onFinish}
                disabled={!canProceed}
              >
                <CheckIcon size={16} />
                {doneLabel}
              </button>
            </ClickSpark>
          ) : (
            <ClickSpark>
              <button
                type="button"
                className="btn btn-primary"
                onClick={() => onStepChange(currentStep + 1)}
                disabled={!canProceed}
              >
                {nextLabel}
                <ChevronRightIcon size={16} />
              </button>
            </ClickSpark>
          )}
        </DialogFooter>
      </div>
    </BaseDialog>
  );
}
