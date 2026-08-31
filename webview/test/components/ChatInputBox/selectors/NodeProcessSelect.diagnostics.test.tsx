import { act, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NodeProcessSelect } from '../../../../src/components/ChatInputBox/selectors/NodeProcessSelect';
import type { NodeProcessSnapshot } from '../../../../src/utils/nodeProcessCapabilities';

const nodeProcessMocks = vi.hoisted(() => ({
  fetchNodeProcesses: vi.fn(),
  killAllOrphanProcesses: vi.fn(),
  killNodeProcess: vi.fn(),
  subscribeNodeProcessKillResult: vi.fn(() => vi.fn()),
  subscribeNodeProcesses: vi.fn(),
}));

vi.mock('../../../../src/utils/nodeProcessCapabilities', () => ({
  ...nodeProcessMocks,
  KILL_PROTECTED_CLI_SESSION: 'cli_session_protected',
}));

vi.mock('../../../../src/utils/viewport', () => ({
  getAppViewport: () => ({ left: 0, top: 0, width: 800, height: 700 }),
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, string | number>) => {
      const templates: Record<string, string> = {
        'config.nodeProcesses.diagnostics.title': 'Runtime diagnostics',
        'config.nodeProcesses.diagnostics.pendingInteractions': 'Pending interactions',
        'config.nodeProcesses.diagnostics.pendingPermissionRequests': 'Permission requests {{count}}',
        'config.nodeProcesses.diagnostics.pendingToolCalls': 'Tool calls {{count}}',
        'config.nodeProcesses.diagnostics.orphanToolResults': 'Orphan tool results {{count}}',
        'config.nodeProcesses.diagnostics.activeProcesses': 'Active · Node {{node}} · CLI {{cli}} · MCP {{mcp}} · Total {{all}}',
        'config.nodeProcesses.diagnostics.persistentRegistry': 'Persistent · Size {{size}} · Usable {{usable}} · Pending {{pending}} · Evictions {{evictions}} · Cooldown hits {{cooldownHits}}',
        'config.nodeProcesses.diagnostics.gateway': 'Gateway · {{state}} · Generation {{generation}} · Active {{active}} · Restarts {{restarts}}',
        'config.nodeProcesses.diagnostics.gatewayTimings': 'Cold start {{coldStart}} ms · Catalog ready {{catalogReady}} ms · Direct degraded {{degraded}}',
        'config.nodeProcesses.diagnostics.lastFailure': 'Last failure: {{error}}',
      };
      const template = templates[key] ?? options?.defaultValue ?? key;
      return template.replace(/\{\{(\w+)\}\}/g, (_match, name: string) => String(options?.[name] ?? ''));
    },
  }),
}));

function snapshotWithFailure(lastFailure: string | null): NodeProcessSnapshot {
  return {
    snapshotAt: 1,
    totals: {
      daemon: 0,
      channel: 0,
      orphan: 0,
      cliSession: 0,
      all: 0,
    },
    processes: [],
    diagnostics: {
      pendingInteractions: {
        pendingPermissionRequests: 14,
        pendingToolCalls: 15,
        orphanToolResults: 16,
      },
      activeProcesses: {
        node: 2,
        cli: 3,
        mcp: 1,
        all: 6,
      },
      persistentRegistry: {
        registrySize: 4,
        usableProcessCount: 3,
        pendingRebuildCount: 1,
        evictionCount: 7,
        rebuildCooldownHitCount: 8,
      },
      gateway: {
        lifecycleState: 'catalog_loading',
        lastFailure,
        processGeneration: 9,
        activeProcessCount: 1,
        refreshInFlight: false,
        restartCount: 10,
        lastColdStartDurationMs: 11,
        lastCatalogReadyDurationMs: 12,
        directDegradedCount: 13,
      },
    },
  };
}

describe('NodeProcessSelect runtime diagnostics', () => {
  let snapshotListener: ((snapshot: NodeProcessSnapshot) => void) | undefined;

  beforeEach(() => {
    vi.clearAllMocks();
    snapshotListener = undefined;
    nodeProcessMocks.subscribeNodeProcesses.mockImplementation((listener) => {
      snapshotListener = listener;
      return vi.fn();
    });
  });

  it('renders backend-aggregated process, registry, and gateway diagnostics', () => {
    render(<NodeProcessSelect embedded />);

    act(() => {
      snapshotListener?.(snapshotWithFailure('gateway failed'));
    });

    const diagnostics = screen.getByTestId('node-process-diagnostics');
    expect(diagnostics.textContent).toContain('Runtime diagnostics');
    expect(diagnostics.textContent).toContain(
      'Pending interactions · Permission requests 14 · Tool calls 15 · Orphan tool results 16',
    );
    expect(diagnostics.textContent).toContain('Active · Node 2 · CLI 3 · MCP 1 · Total 6');
    expect(diagnostics.textContent).toContain('Persistent · Size 4 · Usable 3 · Pending 1 · Evictions 7 · Cooldown hits 8');
    expect(diagnostics.textContent).toContain('Gateway · catalog_loading · Generation 9 · Active 1 · Restarts 10');
    expect(diagnostics.textContent).toContain('Cold start 11 ms · Catalog ready 12 ms · Direct degraded 13');
    expect(diagnostics.textContent).toContain('Last failure: gateway failed');
  });

  it('omits the failure row when the backend reports no gateway failure', () => {
    render(<NodeProcessSelect embedded />);

    act(() => {
      snapshotListener?.(snapshotWithFailure(null));
    });

    expect(screen.getByTestId('node-process-diagnostics').textContent).not.toContain('Last failure:');
  });
});
