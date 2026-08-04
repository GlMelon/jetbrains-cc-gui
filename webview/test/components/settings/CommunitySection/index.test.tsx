import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CommunitySection from '../../../../src/components/settings/CommunitySection/index';

// 双层修复(:156 AppDialogs 实例 + :71 CommunitySection 第二实例)的回归守护:
// 设置页 Version History 按钮必须调用全局 openChangelogDialog(UIStateContext),
// 而非驱动本地第二实例(其 onClose 不写 localStorage,与 AppDialogs 实例叠加成双层)。
const openChangelogDialog = vi.fn();

vi.mock('../../../../src/contexts/UIStateContext', () => ({
  useUIState: () => ({ openChangelogDialog }),
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}));

describe('CommunitySection', () => {
  beforeEach(() => {
    openChangelogDialog.mockClear();
  });

  it('Version History 按钮调用全局 openChangelogDialog(而非本地 state 第二实例)', () => {
    render(<CommunitySection addToast={vi.fn()} />);

    fireEvent.click(screen.getByRole('button', { name: 'settings.versionHistory' }));

    expect(openChangelogDialog).toHaveBeenCalledTimes(1);
  });
});
