import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import WaitingIndicator from './WaitingIndicator';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, fallbackOrOptions?: string | Record<string, unknown>) => {
      if (key === 'chat.queueWaiting') {
        const count = typeof fallbackOrOptions === 'object' ? fallbackOrOptions.count : 0;
        return `Queued, ${count} request(s) ahead`;
      }
      if (typeof fallbackOrOptions === 'string') {
        return fallbackOrOptions;
      }
      return key;
    },
  }),
}));

describe('WaitingIndicator', () => {
  afterEach(cleanup);

  it('does not render the old generating capsule content', () => {
    render(<WaitingIndicator loading queueAheadCount={2} />);

    expect(screen.queryByText(['Generating', 'response'].join(' '))).toBeNull();
    expect(screen.queryByText(['正在生成', '响应'].join(''))).toBeNull();
    expect(document.querySelector(['.gen', 'wave'].join('-'))).toBeNull();
    expect(document.querySelector(['.gen', 'time'].join('-'))).toBeNull();
    expect(screen.queryByText(/预计|Elapsed|\d+s/)).toBeNull();
  });

  it('still renders queued status text', () => {
    render(<WaitingIndicator loading queueAheadCount={2} />);

    expect(screen.getByText('Queued, 2 request(s) ahead')).toBeTruthy();
  });
});
