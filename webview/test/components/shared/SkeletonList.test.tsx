import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SkeletonList } from '../../../src/components/shared/SkeletonList';

describe('SkeletonList (H5)', () => {
  it('renders a status region with the accessible loading label', () => {
    render(<SkeletonList label="加载中" />);
    const region = document.querySelector('.skeleton-list');
    expect(region).toBeTruthy();
    expect(region?.getAttribute('role')).toBe('status');
    expect(region?.getAttribute('aria-label')).toBe('加载中');
  });

  it('renders the default 3 skeleton items, each marked aria-hidden', () => {
    render(<SkeletonList label="加载中" />);
    const items = document.querySelectorAll('.skeleton-item');
    expect(items).toHaveLength(3);
    items.forEach((item) => {
      expect(item.getAttribute('aria-hidden')).toBe('true');
    });
  });

  it('renders the standard icon/title/sub placeholder bars per item', () => {
    render(<SkeletonList label="加载中" />);
    const items = document.querySelectorAll('.skeleton-item');
    expect(items.length).toBeGreaterThan(0);
    items.forEach((item) => {
      // 每个占位项含图标、标题条、副条三类骨架条(标准卡片式占位契约)
      expect(item.querySelector('.skeleton-bar-icon')).toBeTruthy();
      expect(item.querySelector('.skeleton-bar-title')).toBeTruthy();
      expect(item.querySelector('.skeleton-bar-sub')).toBeTruthy();
    });
  });

  it('honors a custom count', () => {
    render(<SkeletonList label="加载中" count={5} />);
    expect(document.querySelectorAll('.skeleton-item')).toHaveLength(5);
  });

  it('renders zero items when count is 0 (still exposes the status region)', () => {
    render(<SkeletonList label="加载中" count={0} />);
    expect(document.querySelector('.skeleton-list')).toBeTruthy();
    expect(document.querySelectorAll('.skeleton-item')).toHaveLength(0);
  });

  it('renders a custom item when provided', () => {
    render(
      <SkeletonList
        label="加载中"
        count={2}
        item={<div className="custom-placeholder" />}
      />,
    );
    expect(document.querySelectorAll('.custom-placeholder')).toHaveLength(2);
    // 自定义项时不再渲染默认的 title/sub 条
    expect(document.querySelector('.skeleton-bar-title')).toBeNull();
  });
});
