import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PromptEnhancerDialog } from './PromptEnhancerDialog';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: { defaultValue?: string }) => {
      const map: Record<string, string> = {
        'promptEnhancer.title': 'Enhance Prompt',
        'promptEnhancer.originalPrompt': 'Original Prompt',
        'promptEnhancer.enhancedPrompt': 'Enhanced Prompt',
        'promptEnhancer.enhancing': 'Enhancing...',
        'promptEnhancer.useEnhanced': 'Use Enhanced',
        'promptEnhancer.keepOriginal': 'Keep Original',
      };
      return map[key] ?? options?.defaultValue ?? key;
    },
  }),
}));

/**
 * 本地实现是纯 7-prop 对话框(upstream 的 usage-meta 链在本仓 pre-merge 已删,
 * 见 v0.5.4 合并审计)。用例对齐本地行为:开关/loading/回调,不含 usageInfo。
 */
describe('PromptEnhancerDialog', () => {
  it('renders nothing when closed', () => {
    const { container } = render(
      <PromptEnhancerDialog
        isOpen={false}
        isLoading={false}
        originalPrompt="hi"
        enhancedPrompt=""
        onUseEnhanced={vi.fn()}
        onKeepOriginal={vi.fn()}
        onClose={vi.fn()}
      />
    );
    expect(container.firstChild).toBeNull();
  });

  it('shows original and enhanced prompts when open', () => {
    render(
      <PromptEnhancerDialog
        isOpen
        isLoading={false}
        originalPrompt="rewrite this"
        enhancedPrompt="better text"
        onUseEnhanced={vi.fn()}
        onKeepOriginal={vi.fn()}
        onClose={vi.fn()}
      />
    );
    expect(screen.getByText('rewrite this')).toBeTruthy();
    expect(screen.getByText('better text')).toBeTruthy();
  });

  it('closes via the close button', () => {
    const onClose = vi.fn();
    const { container } = render(
      <PromptEnhancerDialog
        isOpen
        isLoading={false}
        originalPrompt="x"
        enhancedPrompt="y"
        onUseEnhanced={vi.fn()}
        onKeepOriginal={vi.fn()}
        onClose={onClose}
      />
    );
    fireEvent.click(container.querySelector('.prompt-enhancer-close')!);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('invokes onUseEnhanced when the primary button is clicked', () => {
    const onUseEnhanced = vi.fn();
    render(
      <PromptEnhancerDialog
        isOpen
        isLoading={false}
        originalPrompt="x"
        enhancedPrompt="y"
        onUseEnhanced={onUseEnhanced}
        onKeepOriginal={vi.fn()}
        onClose={vi.fn()}
      />
    );
    fireEvent.click(screen.getByText('Use Enhanced'));
    expect(onUseEnhanced).toHaveBeenCalledTimes(1);
  });

  it('disables Use Enhanced while loading or when there is no enhanced prompt', () => {
    const { rerender } = render(
      <PromptEnhancerDialog
        isOpen
        isLoading
        originalPrompt="x"
        enhancedPrompt=""
        onUseEnhanced={vi.fn()}
        onKeepOriginal={vi.fn()}
        onClose={vi.fn()}
      />
    );
    const primaryButtons = () =>
      screen.getAllByText('Use Enhanced').map((el) => (el as HTMLButtonElement).disabled);
    expect(primaryButtons().every(Boolean)).toBe(true);

    rerender(
      <PromptEnhancerDialog
        isOpen
        isLoading={false}
        originalPrompt="x"
        enhancedPrompt=""
        onUseEnhanced={vi.fn()}
        onKeepOriginal={vi.fn()}
        onClose={vi.fn()}
      />
    );
    expect(primaryButtons().every(Boolean)).toBe(true);

    rerender(
      <PromptEnhancerDialog
        isOpen
        isLoading={false}
        originalPrompt="x"
        enhancedPrompt="ready"
        onUseEnhanced={vi.fn()}
        onKeepOriginal={vi.fn()}
        onClose={vi.fn()}
      />
    );
    expect(primaryButtons().some((disabled) => !disabled)).toBe(true);
  });

  it('closes on Escape key', () => {
    const onClose = vi.fn();
    render(
      <PromptEnhancerDialog
        isOpen
        isLoading={false}
        originalPrompt="x"
        enhancedPrompt="y"
        onUseEnhanced={vi.fn()}
        onKeepOriginal={vi.fn()}
        onClose={onClose}
      />
    );
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
