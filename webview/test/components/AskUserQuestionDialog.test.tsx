import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import AskUserQuestionDialog, {
  type AskUserQuestionRequest,
} from '../../src/components/AskUserQuestionDialog';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (_key: string, fallback?: string, _vars?: Record<string, unknown>) =>
      typeof fallback === 'string' ? fallback : _key,
  }),
}));

const buildRequest = (overrides: Partial<AskUserQuestionRequest> = {}): AskUserQuestionRequest => ({
  requestId: 'req-test-1',
  toolName: 'AskUserQuestion',
  questions: [
    {
      question: 'Pick a color',
      header: 'Color',
      multiSelect: false,
      options: [
        { label: 'Red', description: '' },
        { label: 'Blue', description: '' },
      ],
    },
  ],
  ...overrides,
});

describe('AskUserQuestionDialog provider label', () => {
  it('identifies Codex request_user_input requests', () => {
    render(
      <AskUserQuestionDialog
        isOpen
        request={buildRequest({ toolName: 'request_user_input', provider: 'codex' })}
        onSubmit={() => {}}
        onCancel={() => {}}
      />,
    );

    expect(screen.getByText('Codex 有一些问题想问你')).toBeTruthy();
  });
});

describe('AskUserQuestionDialog countdown', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    // Drain any pending timers/microtasks scheduled by the dialog's effects
    // before swapping back to real timers, otherwise leftover intervals can
    // bleed into the next test and produce phantom onCancel calls.
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  // The countdown is the user-visible deadline. When it hits zero the dialog must
  // call onCancel with the same requestId exactly once — duplicate cancels would
  // make the backend receive contradictory IPC, and a missed cancel would leak
  // the pending future on the Java side until the safety net fires (much later).

  it('auto-cancels with the original requestId after timeoutSeconds elapses', () => {
    const onCancel = vi.fn();
    const onSubmit = vi.fn();
    const request = buildRequest();

    render(
      <AskUserQuestionDialog
        isOpen
        request={request}
        onSubmit={onSubmit}
        onCancel={onCancel}
        timeoutSeconds={30}
      />,
    );

    expect(onCancel).not.toHaveBeenCalled();

    // Advance 30s + a tick to make sure the final setInterval iteration and the
    // subsequent auto-close effect both flush.
    act(() => {
      vi.advanceTimersByTime(30_000);
    });

    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onCancel).toHaveBeenCalledWith('req-test-1');
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('does not fire auto-cancel before the deadline', () => {
    const onCancel = vi.fn();
    const onSubmit = vi.fn();

    render(
      <AskUserQuestionDialog
        isOpen
        request={buildRequest()}
        onSubmit={onSubmit}
        onCancel={onCancel}
        timeoutSeconds={30}
      />,
    );

    // 29 seconds = one second short of expiry.
    act(() => {
      vi.advanceTimersByTime(29_000);
    });

    expect(onCancel).not.toHaveBeenCalled();
  });

  it('manual cancel suppresses the auto-cancel that would otherwise fire after timeout', () => {
    // This protects against a click+countdown race: if the user clicks cancel
    // and the timer also expires shortly after, the backend must not receive
    // two cancel messages.
    const onCancel = vi.fn();
    const onSubmit = vi.fn();

    render(
      <AskUserQuestionDialog
        isOpen
        request={buildRequest()}
        onSubmit={onSubmit}
        onCancel={onCancel}
        timeoutSeconds={30}
      />,
    );

    const cancelButton = screen.getByText('取消');
    fireEvent.click(cancelButton);
    expect(onCancel).toHaveBeenCalledTimes(1);

    // Run the timer well past the deadline; the duplicate-response guard should bail.
    act(() => {
      vi.advanceTimersByTime(60_000);
    });

    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('invalid-format cancel suppresses the later auto-cancel', () => {
    const onCancel = vi.fn();
    const onSubmit = vi.fn();

    render(
      <AskUserQuestionDialog
        isOpen
        request={buildRequest({ questions: [] })}
        onSubmit={onSubmit}
        onCancel={onCancel}
        timeoutSeconds={30}
      />,
    );

    fireEvent.click(screen.getByText('取消'));

    act(() => {
      vi.advanceTimersByTime(60_000);
    });

    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onCancel).toHaveBeenCalledWith('req-test-1');
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('submit suppresses the auto-cancel that would otherwise fire after timeout', () => {
    const onCancel = vi.fn();
    const onSubmit = vi.fn();

    render(
      <AskUserQuestionDialog
        isOpen
        request={buildRequest()}
        onSubmit={onSubmit}
        onCancel={onCancel}
        timeoutSeconds={30}
      />,
    );

    // Pick an option then submit
    fireEvent.click(screen.getByText('Red'));
    fireEvent.click(screen.getByText('提交'));

    expect(onSubmit).toHaveBeenCalledTimes(1);

    // Now race the countdown past the deadline; the backend should not see a duplicate response.
    act(() => {
      vi.advanceTimersByTime(60_000);
    });

    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onCancel).not.toHaveBeenCalled();
  });

  it('keeps the original timeout when the prop changes mid-flight', () => {
    // The Java-side safety net is scheduled once when the dialog is created
    // and cannot be rescheduled. The frontend must not drift to a new timeout
    // while the dialog is already open, otherwise the backend could auto-reject
    // while the user still sees a running countdown.
    const onCancel = vi.fn();
    const onSubmit = vi.fn();

    const { rerender } = render(
      <AskUserQuestionDialog
        isOpen
        request={buildRequest()}
        onSubmit={onSubmit}
        onCancel={onCancel}
        timeoutSeconds={30}
      />,
    );

    // Simulate the user changing the setting while the dialog is visible.
    rerender(
      <AskUserQuestionDialog
        isOpen
        request={buildRequest()}
        onSubmit={onSubmit}
        onCancel={onCancel}
        timeoutSeconds={60}
      />,
    );

    // The dialog must continue using the original 30s timeout.
    act(() => {
      vi.advanceTimersByTime(30_000);
    });
    expect(onCancel).toHaveBeenCalledTimes(1);
  });
});

describe('AskUserQuestionDialog keyboard guards', () => {
  // Escape is a destructive shortcut (cancels the whole turn). When the user is
  // typing into the chat box or any other input on the page, an Escape there
  // (e.g. to close an IME suggestion popup) must not propagate to the dialog.
  it('ignores Escape when focus is on an INPUT element', () => {
    const onCancel = vi.fn();
    const onSubmit = vi.fn();

    render(
      <AskUserQuestionDialog
        isOpen
        request={buildRequest()}
        onSubmit={onSubmit}
        onCancel={onCancel}
      />,
    );

    const input = document.createElement('input');
    document.body.appendChild(input);
    input.focus();
    try {
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    } finally {
      input.remove();
    }

    expect(onCancel).not.toHaveBeenCalled();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('still honors Escape when no editable element has focus', () => {
    const onCancel = vi.fn();
    const onSubmit = vi.fn();

    render(
      <AskUserQuestionDialog
        isOpen
        request={buildRequest()}
        onSubmit={onSubmit}
        onCancel={onCancel}
      />,
    );

    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));

    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onCancel).toHaveBeenCalledWith('req-test-1');
  });
});

// ─── Ported from upstream 8af6176d / acfa628b: manual answer + options work
// ─── together; DSH requests are titled as such; question detail renders.

vi.mock('../../src/components/MarkdownBlock', () => ({
  default: ({ content }: { content: string }) => <div data-testid="markdown-stub">{content}</div>,
}));

const OPTIONS = [
  { label: '1. 选项一', description: 'desc 1' },
  { label: '2. 选项二', description: 'desc 2' },
  { label: '3. 选项三', description: 'desc 3' },
];

const OTHER_PLACEHOLDER = '请输入您的答案...';

const renderAskDialog = (
  questions: AskUserQuestionRequest['questions'],
  provider?: AskUserQuestionRequest['provider'],
) => {
  const onSubmit = vi.fn();
  const onCancel = vi.fn();
  render(
    <AskUserQuestionDialog
      isOpen
      request={{ requestId: 'ask-1', toolName: 'AskUserQuestion', questions, provider }}
      onSubmit={onSubmit}
      onCancel={onCancel}
      timeoutSeconds={300}
    />,
  );
  return { onSubmit, onCancel };
};

const askQuestion = (
  options: AskUserQuestionRequest['questions'][number]['options'],
  multiSelect = false,
  extra: Record<string, unknown> = {},
): AskUserQuestionRequest['questions'] => [
  { question: 'Q', header: 'H', multiSelect, options, ...extra } as AskUserQuestionRequest['questions'][number],
];

const customBox = () => screen.getByPlaceholderText(OTHER_PLACEHOLDER) as HTMLTextAreaElement;
const optionRow = (label: string) =>
  screen.getByRole('button', { name: new RegExp(label) }) as HTMLButtonElement;
const otherRowClassName = () =>
  (screen.getByText('其他').closest('button') as HTMLButtonElement).className;
const submitAnswer = () => fireEvent.click(screen.getByText('提交'));

describe('AskUserQuestionDialog manual answer', () => {
  it('always offers the input, even before "Other" is clicked', () => {
    renderAskDialog(askQuestion(OPTIONS));
    expect(customBox()).toBeTruthy();
    expect(otherRowClassName()).not.toContain('selected');
  });

  it('typing selects the "Other" row so the text is part of the answer', () => {
    const { onSubmit } = renderAskDialog(askQuestion(OPTIONS));
    fireEvent.change(customBox(), { target: { value: '我的备注' } });
    expect(otherRowClassName()).toContain('selected');
    submitAnswer();
    expect(onSubmit).toHaveBeenCalledWith('ask-1', { Q: '我的备注' });
  });

  it('single-select: picking an option after typing replaces the text (no ghost answer)', () => {
    const { onSubmit } = renderAskDialog(askQuestion(OPTIONS));
    fireEvent.change(customBox(), { target: { value: '草稿' } });
    fireEvent.click(optionRow('1\. 选项一'));
    expect(customBox().value).toBe('');
    submitAnswer();
    expect(onSubmit).toHaveBeenCalledWith('ask-1', { Q: '1. 选项一' });
  });

  it('single-select: typing after picking an option wins over the picked label', () => {
    const { onSubmit } = renderAskDialog(askQuestion(OPTIONS));
    fireEvent.click(optionRow('1\. 选项一'));
    fireEvent.change(customBox(), { target: { value: '改主意了' } });
    submitAnswer();
    expect(onSubmit).toHaveBeenCalledWith('ask-1', { Q: '改主意了' });
  });

  it('multi-select: labels plus the note travel together', () => {
    const { onSubmit } = renderAskDialog(askQuestion(OPTIONS, true));
    fireEvent.click(optionRow('1\. 选项一'));
    fireEvent.change(customBox(), { target: { value: '补充说明' } });
    submitAnswer();
    expect(onSubmit).toHaveBeenCalledWith('ask-1', { Q: ['1. 选项一', '补充说明'] });
  });

  it('multi-select: emptying the box releases the "Other" marker', () => {
    renderAskDialog(askQuestion(OPTIONS, true));
    fireEvent.change(customBox(), { target: { value: '临时' } });
    expect(otherRowClassName()).toContain('selected');
    fireEvent.change(customBox(), { target: { value: '' } });
    expect(otherRowClassName()).not.toContain('selected');
  });

  it('a question without options drops the "Other" row and submits the text alone', () => {
    const { onSubmit } = renderAskDialog(askQuestion([]));
    expect(screen.queryByText('其他')).toBeNull();
    fireEvent.change(customBox(), { target: { value: '自由回答' } });
    submitAnswer();
    expect(onSubmit).toHaveBeenCalledWith('ask-1', { Q: '自由回答' });
  });

  it('submit stays disabled when nothing is selected and nothing typed', () => {
    renderAskDialog(askQuestion(OPTIONS));
    expect((screen.getByText('提交') as HTMLButtonElement).disabled).toBe(true);
  });
});

describe('AskUserQuestionDialog provider title and detail', () => {
  it('titles a DSH request as DeepSeek Harness', () => {
    renderAskDialog(askQuestion(OPTIONS), 'dsh');
    expect(screen.getByText('DeepSeek Harness 有一些问题想问你')).toBeTruthy();
  });

  it('renders a plan-review detail above the options with a label', () => {
    renderAskDialog(askQuestion(OPTIONS, false, {
      detail: '# 计划标题 步骤一',
      intent: { kind: 'plan-review', approve: '1. 选项一' },
    }));
    expect(screen.getByTestId('markdown-stub').textContent).toContain('计划标题');
    expect(screen.getByText('计划内容')).toBeTruthy();
  });

  it('renders no detail panel when the question has none', () => {
    renderAskDialog(askQuestion(OPTIONS));
    expect(screen.queryByTestId('markdown-stub')).toBeNull();
  });
});
