import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ProviderModelIcon } from './ProviderModelIcon';

describe('ProviderModelIcon', () => {
  it('renders the prompt-enhancer provider icon set for core providers', () => {
    const { container: claudeContainer } = render(
      <ProviderModelIcon providerId="claude" colored />,
    );
    expect(claudeContainer.querySelector('title')?.textContent).toBe('Claude');

    const { container: codexContainer } = render(
      <ProviderModelIcon providerId="codex" colored />,
    );
    expect(codexContainer.querySelector('title')?.textContent).toBe('OpenAI');

    const { container: opencodeContainer } = render(
      <ProviderModelIcon providerId="opencode" colored />,
    );
    expect(opencodeContainer.querySelector('[aria-label="OpenCode"]')).toBeTruthy();
    expect(opencodeContainer.querySelector('title')).toBeNull();
  });

  it('renders the Xiaomi MiMo icon for MiMo model IDs on Claude-compatible providers', () => {
    const { container } = render(
      <ProviderModelIcon providerId="claude" modelId="mimo-v2.5-pro" colored />,
    );

    expect(container.querySelector('[aria-label="XiaomiMiMo"]')).toBeTruthy();
    expect(container.querySelector('title')?.textContent).toBe('XiaomiMiMo');
  });
});
