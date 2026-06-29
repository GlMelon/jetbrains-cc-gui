import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { MessageAvatar } from './MessageAvatar';

describe('MessageAvatar', () => {
  it('renders the provided assistant label under the AI avatar', () => {
    render(<MessageAvatar type="assistant" assistantLabel="Codex" />);
    expect(screen.getByText('Codex')).toBeTruthy();
  });

  it('defaults the assistant label to Claude when none is provided', () => {
    render(<MessageAvatar type="assistant" />);
    expect(screen.getByText('Claude')).toBeTruthy();
  });

  it('renders the provided user label under the user avatar', () => {
    render(<MessageAvatar type="user" userLabel="You" />);
    expect(screen.getByText('You')).toBeTruthy();
  });

  it('defaults the user label to 你 when none is provided', () => {
    render(<MessageAvatar type="user" />);
    expect(screen.getByText('你')).toBeTruthy();
  });

  it('renders nothing for non-avatar message types', () => {
    const { container } = render(<MessageAvatar type="notification" />);
    expect(container.querySelector('.message-avatar')).toBeNull();
  });
});
