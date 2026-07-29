import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { GuidedProviderDialog, type GuidedStep } from '../../../src/components/shared/GuidedProviderDialog';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, fallback?: string) => fallback ?? k,
    i18n: { language: 'en' },
  }),
}));

const steps: GuidedStep[] = [
  { id: 'access', title: 'Access' },
  { id: 'creds', title: 'Credentials' },
  { id: 'models', title: 'Models' },
];

const renderDialog = (overrides: Partial<React.ComponentProps<typeof GuidedProviderDialog>> = {}) =>
  render(
    <GuidedProviderDialog
      isOpen
      onClose={vi.fn()}
      ariaLabel="guided"
      steps={steps}
      currentStep={0}
      onStepChange={vi.fn()}
      onFinish={vi.fn()}
      finishLabel="Done"
      {...overrides}
    >
      <div>step-body</div>
    </GuidedProviderDialog>,
  );

describe('GuidedProviderDialog', () => {
  it('renders all step titles in the stepper', () => {
    renderDialog();
    // getByText 找不到会抛错,三个都能取到即说明指示器渲染了全部标题
    screen.getByText('Access');
    screen.getByText('Credentials');
    screen.getByText('Models');
  });

  it('renders the provided step body', () => {
    renderDialog();
    screen.getByText('step-body');
  });

  it('disables the back button on the first step', () => {
    renderDialog({ currentStep: 0 });
    const back = screen.getByRole('button', { name: /back/i }) as HTMLButtonElement;
    expect(back.disabled).toBe(true);
  });

  it('enables the back button on a non-first step', () => {
    renderDialog({ currentStep: 1 });
    const back = screen.getByRole('button', { name: /back/i }) as HTMLButtonElement;
    expect(back.disabled).toBe(false);
  });

  it('shows the next button on a non-last step', () => {
    renderDialog({ currentStep: 0 });
    expect(screen.queryByRole('button', { name: /next/i })).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'Done' })).toBeNull();
  });

  it('shows the finish button on the last step', () => {
    renderDialog({ currentStep: 2 });
    expect(screen.queryByRole('button', { name: 'Done' })).not.toBeNull();
    expect(screen.queryByRole('button', { name: /next/i })).toBeNull();
  });

  it('disables next when canProceed is false', () => {
    renderDialog({ currentStep: 0, canProceed: false });
    const next = screen.getByRole('button', { name: /next/i }) as HTMLButtonElement;
    expect(next.disabled).toBe(true);
  });

  it('advances to next step on next click', () => {
    const onStepChange = vi.fn();
    renderDialog({ currentStep: 0, onStepChange });
    fireEvent.click(screen.getByRole('button', { name: /next/i }));
    expect(onStepChange).toHaveBeenCalledWith(1);
  });

  it('goes to previous step on back click', () => {
    const onStepChange = vi.fn();
    renderDialog({ currentStep: 1, onStepChange });
    fireEvent.click(screen.getByRole('button', { name: /back/i }));
    expect(onStepChange).toHaveBeenCalledWith(0);
  });

  it('calls onFinish on last step finish click', () => {
    const onFinish = vi.fn();
    renderDialog({ currentStep: 2, onFinish });
    fireEvent.click(screen.getByRole('button', { name: 'Done' }));
    expect(onFinish).toHaveBeenCalledTimes(1);
  });
});
