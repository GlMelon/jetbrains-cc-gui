import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import PromptEnhancerSection from './index';
import type { PromptEnhancerConfig } from '../../../types/promptEnhancer';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}));

describe('PromptEnhancerSection', () => {
  it('renders prompt enhancer settings as a standalone section', () => {
    const config: PromptEnhancerConfig = {
      provider: null,
      effectiveProvider: 'codex',
      resolutionSource: 'auto',
      models: {
        claude: 'claude-role-sonnet',
        codex: '',
        opencode: '',
      },
      availability: {
        claude: true,
        codex: true,
        opencode: true,
      },
    };

    render(
      <PromptEnhancerSection
        promptEnhancerConfig={config}
        onPromptEnhancerProviderChange={vi.fn()}
        onPromptEnhancerModelChange={vi.fn()}
      />
    );

    expect(screen.getByText('settings.promptEnhancer.title')).toBeTruthy();
    expect(screen.getByText('settings.promptEnhancer.description')).toBeTruthy();
    expect(screen.getByTestId('prompt-enhancer-provider-card')).toBeTruthy();
    expect(screen.getAllByRole('combobox')).toHaveLength(2);
  });
});
