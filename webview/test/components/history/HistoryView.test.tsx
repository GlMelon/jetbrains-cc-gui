import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { HistoryData } from '../../../src/types';
import { sendAction } from '../../../src/bridge/typed';
import { HISTORY_EXPORT_FORMAT, UPSTREAM } from '../../../src/generated/protocol';
import HistoryView from '../../../src/components/history/HistoryView';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, unknown>) => {
      const translations: Record<string, string> = {
        'history.totalSessions': `${options?.count} sessions · ${options?.total} messages`,
        'history.messageCount': `${options?.count} messages`,
        'history.selectMode': 'Select',
        'history.exitSelectMode': 'Exit selection',
        'history.selectedSessions': `${options?.count} selected`,
        'history.selectAll': 'Select all',
        'history.clearSelection': 'Clear',
        'history.deleteSelected': 'Delete selected',
        'history.archiveSession': 'Archive session',
        'history.archiveSelected': 'Archive selected',
        'history.confirmArchive': 'Confirm Archive',
        'history.archiveMessage': 'Archive this session?',
        'history.confirmArchiveSelected': 'Confirm Archive Selected',
        'history.archiveSelectedMessage': `Archive ${options?.count} selected sessions?`,
        'history.confirmDeleteSelected': 'Confirm Delete',
        'history.deleteSelectedMessage': `Delete ${options?.count} selected sessions?`,
        'history.selectSession': 'Select session',
        'history.selectSessionWithTitle': `Select ${String(options?.title ?? '')}`,
        'history.searchPlaceholder': 'Search session titles...',
        'history.deepSearchTooltip': 'Deep Search',
        'history.favoriteSession': 'Favorite session',
        'history.unfavoriteSession': 'Unfavorite session',
        'history.convertToCliSession': 'Convert to CLI session',
        'history.convertButton': 'Convert',
        'history.confirmConvert': 'Convert to CLI?',
        'history.convertConfirmMessage': 'This changes the entrypoint.',
        'common.cancel': 'Cancel',
        'common.delete': 'Delete',
      };
      return translations[key] ?? key;
    },
  }),
}));

vi.mock('../../../src/components/shared/ProviderModelIcon', () => ({
  ProviderModelIcon: () => <span data-testid="provider-icon" />,
}));

vi.mock('../../../src/bridge/typed', () => ({
  sendAction: vi.fn(),
}));

vi.mock('../../../src/utils/copyUtils', () => ({
  copyToClipboard: vi.fn(async () => true),
}));

const historyData: HistoryData = {
  success: true,
  total: 10,
  capabilities: { canDelete: true, canArchive: false },
  sessions: [
    {
      sessionId: 'session-one',
      title: 'First session',
      messageCount: 4,
      lastTimestamp: new Date().toISOString(),
      provider: 'claude',
    },
    {
      sessionId: 'session-two',
      title: 'Second session',
      messageCount: 6,
      lastTimestamp: new Date().toISOString(),
      provider: 'codex',
    },
  ],
};

beforeEach(() => {
  vi.clearAllMocks();
});

describe('HistoryView multi-select', () => {
  it('deletes selected sessions after confirmation without loading them', () => {
    const onLoadSession = vi.fn();
    const onDeleteSession = vi.fn();
    const onDeleteSessions = vi.fn();

    render(
      <HistoryView
        historyData={historyData}
        currentProvider="claude"
        onLoadSession={onLoadSession}
        onDeleteSession={onDeleteSession}
        onDeleteSessions={onDeleteSessions}
        onArchiveSessions={vi.fn()}
        onExportSession={vi.fn()}
        onPrintSessionPdf={vi.fn()}
        onToggleFavorite={vi.fn()}
        onUpdateTitle={vi.fn()}
        onConvertToCliSession={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Select' }));

    fireEvent.click(screen.getByRole('checkbox', { name: 'Select First session' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select Second session' }));

    expect(screen.getByText('2 selected')).toBeTruthy();
    expect(onLoadSession).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Delete selected' }));

    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Delete 2 selected sessions?')).toBeTruthy();

    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));

    expect(onDeleteSession).not.toHaveBeenCalled();
    expect(onDeleteSessions).toHaveBeenCalledTimes(1);
    expect(onDeleteSessions).toHaveBeenCalledWith(['session-one', 'session-two']);
    expect(onLoadSession).not.toHaveBeenCalled();
  });
});

describe('HistoryView conversion', () => {
  it('confirms SDK session conversion without loading the row', () => {
    const onLoadSession = vi.fn();
    const onConvertToCliSession = vi.fn();

    render(
      <HistoryView
        historyData={{
          ...historyData,
          sessions: [
            {
              ...historyData.sessions![0],
              entrypoint: 'sdk-cli',
            },
          ],
        }}
        currentProvider="claude"
        onLoadSession={onLoadSession}
        onDeleteSession={vi.fn()}
        onDeleteSessions={vi.fn()}
        onArchiveSessions={vi.fn()}
        onExportSession={vi.fn()}
        onPrintSessionPdf={vi.fn()}
        onToggleFavorite={vi.fn()}
        onUpdateTitle={vi.fn()}
        onConvertToCliSession={onConvertToCliSession}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Convert to CLI session' }));

    const dialog = screen.getByRole('dialog', { name: 'Convert to CLI?' });
    expect(within(dialog).getByText('This changes the entrypoint.')).toBeTruthy();
    expect(onLoadSession).not.toHaveBeenCalled();

    fireEvent.click(within(dialog).getByRole('button', { name: 'Convert' }));

    expect(onConvertToCliSession).toHaveBeenCalledTimes(1);
    expect(onConvertToCliSession).toHaveBeenCalledWith('session-one');
    expect(onLoadSession).not.toHaveBeenCalled();
  });

  it('hides the convert button for the currently active session', () => {
    render(
      <HistoryView
        historyData={{
          ...historyData,
          sessions: [
            {
              ...historyData.sessions![0],
              entrypoint: 'sdk-cli',
            },
          ],
        }}
        currentProvider="claude"
        currentSessionId="session-one"
        onLoadSession={vi.fn()}
        onDeleteSession={vi.fn()}
        onDeleteSessions={vi.fn()}
        onArchiveSessions={vi.fn()}
        onExportSession={vi.fn()}
        onPrintSessionPdf={vi.fn()}
        onToggleFavorite={vi.fn()}
        onUpdateTitle={vi.fn()}
        onConvertToCliSession={vi.fn()}
      />,
    );

    expect(screen.queryByRole('button', { name: 'Convert to CLI session' })).toBeNull();
  });

  it('does not offer conversion for unknown entrypoints the backend cannot rewrite', () => {
    render(
      <HistoryView
        historyData={{
          ...historyData,
          sessions: [
            {
              ...historyData.sessions![0],
              entrypoint: 'some-future-entrypoint',
            },
          ],
        }}
        currentProvider="claude"
        onLoadSession={vi.fn()}
        onDeleteSession={vi.fn()}
        onDeleteSessions={vi.fn()}
        onArchiveSessions={vi.fn()}
        onExportSession={vi.fn()}
        onPrintSessionPdf={vi.fn()}
        onToggleFavorite={vi.fn()}
        onUpdateTitle={vi.fn()}
        onConvertToCliSession={vi.fn()}
      />,
    );

    expect(screen.queryByRole('button', { name: 'Convert to CLI session' })).toBeNull();
  });

  it('clears deep search state when existing history data refreshes', () => {
    const { rerender } = render(
      <HistoryView
        historyData={historyData}
        currentProvider="claude"
        onLoadSession={vi.fn()}
        onDeleteSession={vi.fn()}
        onDeleteSessions={vi.fn()}
        onArchiveSessions={vi.fn()}
        onExportSession={vi.fn()}
        onPrintSessionPdf={vi.fn()}
        onToggleFavorite={vi.fn()}
        onUpdateTitle={vi.fn()}
        onConvertToCliSession={vi.fn()}
      />,
    );

    const deepSearchButton = screen.getByRole('button', { name: 'Deep Search' });
    fireEvent.click(deepSearchButton);

    expect(sendAction).toHaveBeenCalledWith(UPSTREAM.DEEP_SEARCH_HISTORY, 'claude');
    expect(deepSearchButton).toHaveProperty('disabled', true);

    rerender(
      <HistoryView
        historyData={{
          ...historyData,
          total: 11,
        }}
        currentProvider="claude"
        onLoadSession={vi.fn()}
        onDeleteSession={vi.fn()}
        onDeleteSessions={vi.fn()}
        onArchiveSessions={vi.fn()}
        onExportSession={vi.fn()}
        onPrintSessionPdf={vi.fn()}
        onToggleFavorite={vi.fn()}
        onUpdateTitle={vi.fn()}
        onConvertToCliSession={vi.fn()}
      />,
    );

    expect(screen.getByRole('button', { name: 'Deep Search' })).toHaveProperty('disabled', false);
  });
});


describe('HistoryView archive capabilities', () => {
  const archiveHistoryData: HistoryData = {
    ...historyData,
    capabilities: { canDelete: false, canArchive: true },
  };

  it('shows archive actions and hides delete actions from backend capabilities', () => {
    render(
      <HistoryView
        historyData={archiveHistoryData}
        currentProvider="opencode"
        onLoadSession={vi.fn()}
        onDeleteSession={vi.fn()}
        onDeleteSessions={vi.fn()}
        onArchiveSessions={vi.fn()}
        onExportSession={vi.fn()}
        onPrintSessionPdf={vi.fn()}
        onToggleFavorite={vi.fn()}
        onUpdateTitle={vi.fn()}
        onConvertToCliSession={vi.fn()}
      />,
    );

    expect(screen.getAllByRole('button', { name: 'Archive session' })).toHaveLength(2);
    expect(screen.queryByRole('button', { name: 'history.deleteSession' })).toBeNull();
  });

  it('archives one session only after confirmation', () => {
    const onArchiveSessions = vi.fn();
    render(
      <HistoryView
        historyData={archiveHistoryData}
        currentProvider="opencode"
        onLoadSession={vi.fn()}
        onDeleteSession={vi.fn()}
        onDeleteSessions={vi.fn()}
        onArchiveSessions={onArchiveSessions}
        onExportSession={vi.fn()}
        onPrintSessionPdf={vi.fn()}
        onToggleFavorite={vi.fn()}
        onUpdateTitle={vi.fn()}
        onConvertToCliSession={vi.fn()}
      />,
    );

    fireEvent.click(screen.getAllByRole('button', { name: 'Archive session' })[0]);
    const dialog = screen.getByRole('dialog', { name: 'Confirm Archive' });
    expect(within(dialog).getByText('Archive this session?')).toBeTruthy();
    expect(onArchiveSessions).not.toHaveBeenCalled();

    fireEvent.click(within(dialog).getByRole('button', { name: 'Archive session' }));
    expect(onArchiveSessions).toHaveBeenCalledWith(['session-one']);
  });

  it('archives selected sessions only after confirmation', () => {
    const onArchiveSessions = vi.fn();
    render(
      <HistoryView
        historyData={archiveHistoryData}
        currentProvider="opencode"
        onLoadSession={vi.fn()}
        onDeleteSession={vi.fn()}
        onDeleteSessions={vi.fn()}
        onArchiveSessions={onArchiveSessions}
        onExportSession={vi.fn()}
        onPrintSessionPdf={vi.fn()}
        onToggleFavorite={vi.fn()}
        onUpdateTitle={vi.fn()}
        onConvertToCliSession={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Select' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select First session' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select Second session' }));
    fireEvent.click(screen.getByRole('button', { name: 'Archive selected' }));

    const dialog = screen.getByRole('dialog', { name: 'Confirm Archive Selected' });
    expect(within(dialog).getByText('Archive 2 selected sessions?')).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Archive selected' }));

    expect(onArchiveSessions).toHaveBeenCalledWith(['session-one', 'session-two']);
  });

  it('hides destructive selection actions when backend reports no capability', () => {
    render(
      <HistoryView
        historyData={{ ...historyData, capabilities: { canDelete: false, canArchive: false } }}
        currentProvider="opencode"
        onLoadSession={vi.fn()}
        onDeleteSession={vi.fn()}
        onDeleteSessions={vi.fn()}
        onArchiveSessions={vi.fn()}
        onExportSession={vi.fn()}
        onPrintSessionPdf={vi.fn()}
        onToggleFavorite={vi.fn()}
        onUpdateTitle={vi.fn()}
        onConvertToCliSession={vi.fn()}
      />,
    );

    expect(screen.queryByRole('button', { name: 'Select' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Archive session' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'history.deleteSession' })).toBeNull();
  });
});

describe('HistoryView favorite visibility', () => {
  it('marks favorited session actions for persistent display', () => {
    render(
      <HistoryView
        historyData={{
          ...historyData,
          sessions: [
            {
              ...historyData.sessions![0],
              isFavorited: true,
              favoritedAt: Date.now(),
            },
            historyData.sessions![1],
          ],
        }}
        currentProvider="claude"
        onLoadSession={vi.fn()}
        onDeleteSession={vi.fn()}
        onDeleteSessions={vi.fn()}
        onArchiveSessions={vi.fn()}
        onExportSession={vi.fn()}
        onPrintSessionPdf={vi.fn()}
        onToggleFavorite={vi.fn()}
        onUpdateTitle={vi.fn()}
        onConvertToCliSession={vi.fn()}
      />,
    );

    const favoritedButton = screen.getByRole('button', { name: 'Unfavorite session' });
    const unfavoritedButton = screen.getByRole('button', { name: 'Favorite session' });

    expect(favoritedButton.closest('.history-action-buttons')?.classList.contains('has-favorite')).toBe(true);
    expect(unfavoritedButton.closest('.history-action-buttons')?.classList.contains('has-favorite')).toBe(false);
  });
});


describe('HistoryView export formats', () => {
  it('forwards JSON and HTML format constants without loading the session', () => {
    const onLoadSession = vi.fn();
    const onExportSession = vi.fn();
    const onPrintSessionPdf = vi.fn();
    render(
      <HistoryView
        historyData={historyData}
        currentProvider="claude"
        onLoadSession={onLoadSession}
        onDeleteSession={vi.fn()}
        onDeleteSessions={vi.fn()}
        onArchiveSessions={vi.fn()}
        onExportSession={onExportSession}
        onPrintSessionPdf={onPrintSessionPdf}
        onToggleFavorite={vi.fn()}
        onUpdateTitle={vi.fn()}
        onConvertToCliSession={vi.fn()}
      />,
    );

    fireEvent.click(screen.getAllByRole('button', { name: 'history.exportSession (JSON)' })[0]);
    fireEvent.click(screen.getAllByRole('button', { name: 'history.exportSession (HTML)' })[0]);
    fireEvent.click(screen.getAllByRole('button', { name: 'history.exportPdf' })[0]);

    expect(onExportSession).toHaveBeenNthCalledWith(
      1,
      'session-one',
      'First session',
      HISTORY_EXPORT_FORMAT.JSON,
    );
    expect(onExportSession).toHaveBeenNthCalledWith(
      2,
      'session-one',
      'First session',
      HISTORY_EXPORT_FORMAT.HTML,
    );
    expect(onPrintSessionPdf).toHaveBeenCalledWith('session-one', 'First session');
    expect(onLoadSession).not.toHaveBeenCalled();
  });
});
