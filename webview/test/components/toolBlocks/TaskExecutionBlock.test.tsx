import { act, fireEvent, render } from '@testing-library/react';
import TaskExecutionBlock from '../../../src/components/toolBlocks/TaskExecutionBlock';

const mockSendBridgeEvent = vi.fn();
const mockHistories: Record<string, unknown> = {};
const mockUseSessionId = vi.fn<() => string | null>();
const mockGetToolResultRaw = vi.fn<(toolUseId: string) => Record<string, unknown> | null>();
const mockUseTaskEvent = vi.fn<(toolUseId?: string) => unknown>();

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}));

vi.mock('../../../src/utils/bridge', () => ({
  sendBridgeEvent: (...args: unknown[]) => mockSendBridgeEvent(...args),
}));

vi.mock('../../../src/contexts/SubagentContext', () => ({
  useSubagentHistories: () => mockHistories,
  useSessionId: () => mockUseSessionId(),
  useSessionProvider: () => 'claude',
  useGetToolResultRaw: () => mockGetToolResultRaw,
  useTaskEvent: (id?: string) => mockUseTaskEvent(id),
}));

describe('TaskExecutionBlock polling', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    mockSendBridgeEvent.mockReset();
    mockUseSessionId.mockReset();
    mockGetToolResultRaw.mockReset();
    mockUseTaskEvent.mockReset();

    // Clear the shared history map without resetting the reference the mock
    // factory closes over.
    for (const key of Object.keys(mockHistories)) {
      delete mockHistories[key];
    }

    mockGetToolResultRaw.mockReturnValue(null);
    mockUseSessionId.mockReturnValue('session-1');
    mockUseTaskEvent.mockReturnValue(undefined);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('does not start polling before the card is expanded', () => {
    const setIntervalSpy = vi.spyOn(window, 'setInterval');

    const { container } = render(
      <TaskExecutionBlock
        name="Task"
        toolId="task-1"
        input={{
          description: 'Inspect render path',
          subagent_type: 'Explore',
        }}
      />,
    );

    // Collapsed card must not schedule any polling interval.
    expect(setIntervalSpy).not.toHaveBeenCalled();

    fireEvent.click(container.querySelector('.task-header') as HTMLElement);

    // Expanding an unresolved agent card starts the history-poll interval.
    expect(setIntervalSpy).toHaveBeenCalled();
  });

  it('keeps the task header expandable without rendering a chevron icon', () => {
    const { container } = render(
      <TaskExecutionBlock
        name="Task"
        toolId="task-1"
        input={{
          description: 'Inspect render path',
          subagent_type: 'Explore',
        }}
      />,
    );

    expect(container.querySelector('.task-chevron')).toBeNull();

    fireEvent.click(container.querySelector('.task-header') as HTMLElement);

    expect(container.querySelector('.task-details')).toBeTruthy();
  });

  it('stops polling once a tool result marks the agent task completed', () => {
    const clearIntervalSpy = vi.spyOn(window, 'clearInterval');

    const { container, rerender } = render(
      <TaskExecutionBlock
        name="Task"
        toolId="task-1"
        input={{
          description: 'Inspect render path',
          subagent_type: 'Explore',
        }}
      />,
    );

    fireEvent.click(container.querySelector('.task-header') as HTMLElement);

    act(() => {
      vi.advanceTimersByTime(2_000);
    });

    expect(mockSendBridgeEvent).toHaveBeenCalledWith(
      'load_subagent_session',
      expect.stringContaining('"toolUseId":"task-1"'),
    );

    rerender(
      <TaskExecutionBlock
        name="Task"
        toolId="task-1"
        result={{ type: 'tool_result', tool_use_id: 'task-1', content: 'done' } as any}
        input={{
          description: 'Inspect render path',
          subagent_type: 'Explore',
        }}
      />,
    );

    expect(clearIntervalSpy).toHaveBeenCalled();

    mockSendBridgeEvent.mockClear();
    act(() => {
      vi.advanceTimersByTime(4_000);
    });

    expect(mockSendBridgeEvent).not.toHaveBeenCalled();
  });
});
