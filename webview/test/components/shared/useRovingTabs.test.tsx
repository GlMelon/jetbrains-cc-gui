import { fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { useRovingTabs } from '../../../src/components/shared/useRovingTabs';

const VALUES = ['first', 'second', 'third'] as const;
type Value = (typeof VALUES)[number];

function TabsHarness({ rejectSecond = false }: { rejectSecond?: boolean }) {
  const [active, setActive] = useState<Value>('first');
  const onActivate = (value: Value) => {
    if (rejectSecond && value === 'second') return false;
    setActive(value);
    return true;
  };
  const { getTabProps } = useRovingTabs({ values: VALUES, activeValue: active, onActivate });

  return (
    <>
      <div role="tablist" aria-label="Views">
        {VALUES.map((value) => (
          <button
            {...getTabProps(value)}
            id={`${value}-tab`}
            key={value}
            type="button"
            role="tab"
            aria-selected={active === value}
            aria-controls={`${value}-panel`}
            onClick={() => onActivate(value)}
          >
            {value}
          </button>
        ))}
      </div>
      <div id={`${active}-panel`} role="tabpanel" aria-labelledby={`${active}-tab`}>
        {active} panel
      </div>
    </>
  );
}

describe('useRovingTabs', () => {
  it('keeps only the active tab in the tab order and preserves click activation', () => {
    render(<TabsHarness />);
    const tabs = screen.getAllByRole('tab');

    expect(tabs.map((tab) => tab.tabIndex)).toEqual([0, -1, -1]);
    fireEvent.click(tabs[2]);

    expect(tabs.map((tab) => tab.tabIndex)).toEqual([-1, -1, 0]);
    expect(tabs[2].getAttribute('aria-selected')).toBe('true');
    const panel = screen.getByRole('tabpanel');
    expect(tabs[2].getAttribute('aria-controls')).toBe(panel.id);
    expect(panel.getAttribute('aria-labelledby')).toBe(tabs[2].id);
  });

  it('supports Arrow keys with automatic activation and wrap-around', () => {
    render(<TabsHarness />);
    const tabs = screen.getAllByRole('tab');
    tabs[0].focus();

    fireEvent.keyDown(tabs[0], { key: 'ArrowRight' });
    expect(document.activeElement).toBe(tabs[1]);
    expect(tabs[1].getAttribute('aria-selected')).toBe('true');

    fireEvent.keyDown(tabs[1], { key: 'ArrowDown' });
    expect(document.activeElement).toBe(tabs[2]);

    fireEvent.keyDown(tabs[2], { key: 'ArrowRight' });
    expect(document.activeElement).toBe(tabs[0]);

    fireEvent.keyDown(tabs[0], { key: 'ArrowLeft' });
    expect(document.activeElement).toBe(tabs[2]);

    fireEvent.keyDown(tabs[2], { key: 'ArrowUp' });
    expect(document.activeElement).toBe(tabs[1]);
  });

  it('supports Home and End navigation', () => {
    render(<TabsHarness />);
    const tabs = screen.getAllByRole('tab');
    tabs[1].focus();

    fireEvent.keyDown(tabs[1], { key: 'End' });
    expect(document.activeElement).toBe(tabs[2]);
    expect(tabs[2].getAttribute('aria-selected')).toBe('true');

    fireEvent.keyDown(tabs[2], { key: 'Home' });
    expect(document.activeElement).toBe(tabs[0]);
    expect(tabs[0].getAttribute('aria-selected')).toBe('true');
  });

  it('does not move focus when a tab transition is rejected', () => {
    const focusSpy = vi.spyOn(HTMLElement.prototype, 'focus');
    render(<TabsHarness rejectSecond />);
    const tabs = screen.getAllByRole('tab');
    tabs[0].focus();
    focusSpy.mockClear();

    fireEvent.keyDown(tabs[0], { key: 'ArrowRight' });

    expect(tabs[0].getAttribute('aria-selected')).toBe('true');
    expect(focusSpy).not.toHaveBeenCalled();
    focusSpy.mockRestore();
  });
});
