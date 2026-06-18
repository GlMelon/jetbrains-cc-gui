import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ModelRegistrySection from './index';
import { __setModelRegistryForTests, resetModelRegistryForTests } from '../../../utils/modelRegistry';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, fallback?: string) => fallback ?? key,
  }),
}));

describe('ModelRegistrySection', () => {
  beforeEach(() => {
    resetModelRegistryForTests();
    vi.restoreAllMocks();
    Object.defineProperty(window, 'sendToJava', {
      value: vi.fn(),
      writable: true,
      configurable: true,
    });
  });

  it('adds multiple Claude models for the same role without replacing existing entries', () => {
    const addToast = vi.fn();
    __setModelRegistryForTests({
      items: [
        {
          id: 'claude-role-sonnet',
          provider: 'claude',
          role: 'sonnet',
          label: 'Sonnet',
          actualModel: '',
          contextWindow: 200_000,
          supports1MContext: true,
          enabled: true,
        },
        {
          id: 'mimo-v2.5',
          provider: 'claude',
          role: 'sonnet',
          label: 'MiMo v2.5',
          actualModel: 'mimo-v2.5',
          contextWindow: 1_000_000,
          supports1MContext: true,
          enabled: true,
        },
      ],
    });

    render(<ModelRegistrySection addToast={addToast} />);

    fireEvent.click(screen.getByRole('button', { name: /add/i }));
    fireEvent.change(screen.getByPlaceholderText('label'), {
      target: { value: 'MiMo v2.5' },
    });
    fireEvent.change(screen.getByPlaceholderText('actual request model'), {
      target: { value: 'mimo-v2.5-pro' },
    });
    fireEvent.click(screen.getByRole('button', { name: /confirm/i }));

    expect(addToast).not.toHaveBeenCalledWith('A model with this ID already exists', 'error');

    const setRegistryCall = vi.mocked(window.sendToJava).mock.calls
      .map(([message]) => JSON.parse(message as string) as { type: string; content: string })
      .find((message) => message.type === 'set_model_registry');
    expect(setRegistryCall).toBeTruthy();

    const payload = JSON.parse(setRegistryCall?.content ?? '{}');
    expect(payload.items).toEqual(expect.arrayContaining([
      expect.objectContaining({
        id: 'mimo-v2.5-pro',
        provider: 'claude',
        role: 'sonnet',
        label: 'MiMo v2.5',
        actualModel: 'mimo-v2.5-pro',
      }),
      expect.objectContaining({
        id: 'mimo-v2.5',
        provider: 'claude',
        role: 'sonnet',
        actualModel: 'mimo-v2.5',
      }),
      expect.objectContaining({
        id: 'claude-role-sonnet',
        provider: 'claude',
        role: 'sonnet',
      }),
    ]));
  });

  it('adds multiple Codex models by id without a separate mapping field', () => {
    const addToast = vi.fn();
    __setModelRegistryForTests({
      items: [
        {
          id: 'mimo-v2.5',
          provider: 'codex',
          label: 'MiMo v2.5',
          actualModel: 'mimo-v2.5',
          contextWindow: 1_000_000,
          supports1MContext: true,
          enabled: true,
        },
      ],
    });

    render(<ModelRegistrySection addToast={addToast} />);

    fireEvent.click(screen.getByRole('button', { name: 'codex' }));
    fireEvent.click(screen.getByRole('button', { name: /add/i }));
    fireEvent.change(screen.getByPlaceholderText('model id'), {
      target: { value: 'mimo-v2.5-pro' },
    });
    fireEvent.change(screen.getByPlaceholderText('label'), {
      target: { value: 'MiMo v2.5 Pro' },
    });
    fireEvent.click(screen.getByRole('button', { name: /confirm/i }));

    expect(addToast).not.toHaveBeenCalledWith('A model with this ID already exists', 'error');

    const setRegistryCall = vi.mocked(window.sendToJava).mock.calls
      .map(([message]) => JSON.parse(message as string) as { type: string; content: string })
      .find((message) => message.type === 'set_model_registry');
    expect(setRegistryCall).toBeTruthy();

    const payload = JSON.parse(setRegistryCall?.content ?? '{}');
    expect(payload.items).toEqual(expect.arrayContaining([
      expect.objectContaining({
        id: 'mimo-v2.5-pro',
        provider: 'codex',
        label: 'MiMo v2.5 Pro',
      }),
      expect.objectContaining({
        id: 'mimo-v2.5',
        provider: 'codex',
      }),
    ]));
  });
});
