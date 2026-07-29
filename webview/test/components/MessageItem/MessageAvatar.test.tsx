import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PROVIDER_TYPE } from '../../../src/generated/protocol';
import type { AvatarConfig } from '../../../src/types/avatar';
import { AVATAR_MODE, AVATAR_PRESET } from '../../../src/types/avatar';
import { MessageAvatar } from '../../../src/components/MessageItem/MessageAvatar';

const CUSTOM_PNG_DATA_URL = 'data:image/png;base64,ZmFrZQ==';

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

  it('renders a custom assistant avatar data URL', () => {
    const avatarConfig: AvatarConfig = {
      assistant: {
        mode: AVATAR_MODE.CUSTOM,
        custom: {
          id: 'assistant-custom',
          mimeType: 'image/png',
          dataUrl: CUSTOM_PNG_DATA_URL,
        },
      },
      user: {
        mode: AVATAR_MODE.PRESET,
        preset: AVATAR_PRESET.USER_DEFAULT,
      },
    };

    const { container } = render(<MessageAvatar type="assistant" avatarConfig={avatarConfig} />);
    const avatar = container.querySelector('.message-avatar');
    const image = container.querySelector<HTMLImageElement>('img.message-avatar-image');
    expect(avatar?.classList.contains('message-avatar-custom')).toBe(true);
    expect(image).toBeTruthy();
    expect(image?.src).toBe(CUSTOM_PNG_DATA_URL);
  });

  it('renders a custom user avatar data URL', () => {
    const avatarConfig: AvatarConfig = {
      assistant: {
        mode: AVATAR_MODE.PROVIDER,
      },
      user: {
        mode: AVATAR_MODE.CUSTOM,
        custom: {
          id: 'user-custom',
          mimeType: 'image/png',
          dataUrl: CUSTOM_PNG_DATA_URL,
        },
      },
    };

    const { container } = render(<MessageAvatar type="user" avatarConfig={avatarConfig} />);
    const avatar = container.querySelector('.message-avatar');
    const image = container.querySelector<HTMLImageElement>('img.message-avatar-image');
    expect(avatar?.classList.contains('message-avatar-custom')).toBe(true);
    expect(image).toBeTruthy();
    expect(image?.src).toBe(CUSTOM_PNG_DATA_URL);
  });

  it('renders the current provider icon for assistant provider mode', () => {
    const avatarConfig: AvatarConfig = {
      assistant: {
        mode: AVATAR_MODE.PROVIDER,
      },
      user: {
        mode: AVATAR_MODE.PRESET,
        preset: AVATAR_PRESET.USER_DEFAULT,
      },
    };

    const { container } = render(
      <MessageAvatar type="assistant" currentProvider={PROVIDER_TYPE.CODEX} avatarConfig={avatarConfig} />,
    );

    expect(container.querySelector('.message-avatar-provider-icon')).toBeTruthy();
  });

  it('renders a provider preset icon for assistant preset mode', () => {
    const avatarConfig: AvatarConfig = {
      assistant: {
        mode: AVATAR_MODE.PRESET,
        preset: PROVIDER_TYPE.OPENCODE,
      },
      user: {
        mode: AVATAR_MODE.PRESET,
        preset: AVATAR_PRESET.USER_DEFAULT,
      },
    };

    const { container } = render(<MessageAvatar type="assistant" avatarConfig={avatarConfig} />);
    expect(container.querySelector('.message-avatar-provider-icon')).toBeTruthy();
  });

  it('renders the default assistant icon for assistant default preset mode', () => {
    const avatarConfig: AvatarConfig = {
      assistant: {
        mode: AVATAR_MODE.PRESET,
        preset: AVATAR_PRESET.ASSISTANT_DEFAULT,
      },
      user: {
        mode: AVATAR_MODE.PRESET,
        preset: AVATAR_PRESET.USER_DEFAULT,
      },
    };

    const { container } = render(<MessageAvatar type="assistant" avatarConfig={avatarConfig} />);
    expect(container.querySelector('.message-avatar-provider-icon')).toBeNull();
    expect(container.querySelector('.message-avatar svg')).toBeTruthy();
  });

  it('renders nothing for non-avatar message types', () => {
    const { container } = render(<MessageAvatar type="notification" />);
    expect(container.querySelector('.message-avatar')).toBeNull();
  });
});
