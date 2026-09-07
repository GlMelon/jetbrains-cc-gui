import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { ConfigSelect } from '../../../../src/components/ChatInputBox/selectors/ConfigSelect';
import { SPECIAL_PROVIDER_IDS } from '../../../../src/types/provider';

vi.mock('antd', () => ({
  Switch: ({ checked, onClick }: { checked?: boolean; onClick?: (checked: boolean, e: { stopPropagation: () => void }) => void }) => (
    <button type="button" aria-pressed={checked} onClick={() => onClick?.(!checked, { stopPropagation: vi.fn() })} />
  ),
}));

vi.mock('../../../../src/components/ChatInputBox/providers/agentProvider', () => ({
  CREATE_NEW_AGENT_ID: '__create__',
  EMPTY_STATE_ID: '__empty__',
  agentProvider: vi.fn(async () => []),
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: string | Record<string, string>) => ({
      'settings.configure': 'Configure',
      'settings.agent.title': 'Agent',
      'settings.basic.streaming.label': 'Streaming',
      'common.thinking': 'Thinking',
      'config.runtimeProvider.title': 'Switch provider',
      'config.runtimeProvider.empty': 'No providers',
      'config.runtimeProvider.loading': 'Loading providers',
      'config.runtimeProvider.switched': 'Provider switched to Proxy A',
      'settings.provider.localProviderName': 'Use local settings.json',
      'settings.provider.cliLoginProviderName': 'Use CLI login',
      'settings.codexProvider.dialog.cliLoginProviderName': 'Use local Codex config',
      'settings.openCodeProvider.dialog.localConfigProviderName': 'Use local OpenCode config',
    } as Record<string, string>)[key] ?? (typeof options === 'string' ? options : key),
  }),
}));

function rect(left: number, right: number, width = right - left): DOMRect {
  return {
    x: left,
    y: 0,
    left,
    right,
    top: 0,
    bottom: 200,
    width,
    height: 200,
    toJSON: () => ({}),
  } as DOMRect;
}

describe('ConfigSelect runtime provider submenu', () => {
  const bridgeCall = (type: string, content = '') =>
    JSON.stringify({ type, content });

  let getBoundingClientRectSpy: ReturnType<typeof vi.spyOn> | undefined;
  let originalInnerWidth: number;

  beforeEach(() => {
    originalInnerWidth = window.innerWidth;
    window.sendToJava = vi.fn();
    window.updateProviders = undefined;
    window.updateCodexProviders = undefined;
    window.updateOpenCodeProviders = undefined;
    window.updateActiveProvider = undefined;
    window.updateActiveCodexProvider = undefined;
    window.updateActiveOpenCodeProvider = undefined;
  });

  afterEach(() => {
    getBoundingClientRectSpy?.mockRestore();
    getBoundingClientRectSpy = undefined;
    Object.defineProperty(window, 'innerWidth', {
      configurable: true,
      writable: true,
      value: originalInnerWidth,
    });
  });

  it('switches Claude runtime providers from the configure menu', async () => {
    render(<ConfigSelect currentProvider="claude" />);

    fireEvent.click(screen.getByRole('button', { name: /Configure/i }));
    const providerMenuItem = screen.getByText('Switch provider').closest('.selector-option')!;
    expect(providerMenuItem.previousElementSibling?.className).toContain('selector-divider');
    expect(providerMenuItem.nextElementSibling?.className).toContain('selector-divider');
    fireEvent.mouseEnter(providerMenuItem);

    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('get_providers'));

    act(() => {
      window.updateProviders?.(JSON.stringify([
        { id: SPECIAL_PROVIDER_IDS.LOCAL_SETTINGS, name: 'hidden local', isActive: true },
        { id: SPECIAL_PROVIDER_IDS.CLI_LOGIN, name: 'hidden cli', isActive: false },
        { id: 'proxy-a', name: 'Proxy A', remark: 'fast route', isActive: false },
      ]));
    });

    const submenu = await screen.findByRole('listbox');
    expect(within(submenu).getByText('Use local settings.json')).toBeTruthy();
    expect(within(submenu).getByText('Use CLI login')).toBeTruthy();
    expect(within(submenu).getByText('Proxy A')).toBeTruthy();

    fireEvent.click(within(submenu).getByText('Proxy A'));

    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('switch_provider', '{"id":"proxy-a"}'));
    expect(await screen.findByText('Provider switched to Proxy A')).toBeTruthy();
  });

  it('switches Codex runtime providers from the configure menu', async () => {
    render(<ConfigSelect currentProvider="codex" />);

    fireEvent.click(screen.getByRole('button', { name: /Configure/i }));
    fireEvent.mouseEnter(screen.getByText('Switch provider').closest('.selector-option')!);

    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('get_codex_providers'));

    act(() => {
      window.updateCodexProviders?.(JSON.stringify([
        { id: SPECIAL_PROVIDER_IDS.CODEX_CLI_LOGIN, name: 'hidden codex local', isActive: true },
        { id: 'codex-proxy', name: 'Codex Proxy', remark: 'workspace config', isActive: false },
      ]));
    });

    const submenu = await screen.findByRole('listbox');
    expect(within(submenu).getByText('Use local Codex config')).toBeTruthy();
    expect(within(submenu).getByText('Codex Proxy')).toBeTruthy();

    fireEvent.click(within(submenu).getByText('Codex Proxy'));

    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('switch_codex_provider', '{"id":"codex-proxy"}'));
  });

  it('switches OpenCode runtime providers from the configure menu', async () => {
    render(<ConfigSelect currentProvider="opencode" />);

    fireEvent.click(screen.getByRole('button', { name: /Configure/i }));
    fireEvent.mouseEnter(screen.getByText('Switch provider').closest('.selector-option')!);

    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('get_opencode_providers'));

    act(() => {
      window.updateOpenCodeProviders?.(JSON.stringify([
        { id: SPECIAL_PROVIDER_IDS.OPENCODE_LOCAL_CONFIG, name: 'hidden opencode local', isActive: true },
        { id: 'openglm', name: 'OpenGLM', baseURL: 'https://open.bigmodel.cn', isActive: false },
      ]));
    });

    const submenu = await screen.findByRole('listbox');
    expect(within(submenu).getByText('Use local OpenCode config')).toBeTruthy();
    expect(within(submenu).getByText('OpenGLM')).toBeTruthy();

    fireEvent.click(within(submenu).getByText('OpenGLM'));

    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('switch_opencode_provider', '{"id":"openglm"}'));
  });

  it('refreshes selected provider when backend confirms active provider change', async () => {
    render(<ConfigSelect currentProvider="claude" />);

    fireEvent.click(screen.getByRole('button', { name: /Configure/i }));
    fireEvent.mouseEnter(screen.getByText('Switch provider').closest('.selector-option')!);

    act(() => {
      window.updateProviders?.(JSON.stringify([
        { id: 'a', name: 'Provider A', isActive: true },
        { id: 'b', name: 'Provider B', isActive: false },
      ]));
    });

    const submenu = await screen.findByRole('listbox');

    act(() => {
      window.updateActiveProvider?.(JSON.stringify({ id: 'b', name: 'Provider B', isActive: true }));
    });

    await waitFor(() => {
      expect(within(submenu).getByText('Provider B').closest('.selector-option')?.className).toContain('selected');
    });
  });
  it('opens the node process submenu in a narrow panel without entering a layout update loop', async () => {
    Object.defineProperty(window, 'innerWidth', {
      configurable: true,
      writable: true,
      value: 410,
    });
    getBoundingClientRectSpy = vi
      .spyOn(HTMLElement.prototype, 'getBoundingClientRect')
      .mockImplementation(function getMockRect(this: HTMLElement) {
        if (this.classList.contains('node-process-dropdown')) {
          return this.style.right === '100%'
            ? rect(10, 360, 350)
            : rect(390, 740, 350);
        }
        if (this.classList.contains('selector-option') && this.dataset.testid === 'config-option-node-processes') {
          return rect(190, 390, 200);
        }
        return rect(0, 0, 0);
      });

    const { container } = render(<ConfigSelect currentProvider="claude" />);

    fireEvent.click(screen.getByRole('button', { name: /Configure/i }));
    fireEvent.mouseEnter(screen.getByTestId('config-option-node-processes'));

    const dropdown = container.querySelector<HTMLElement>('.node-process-dropdown');
    expect(dropdown).not.toBeNull();

    await waitFor(() => {
      expect(dropdown?.style.right).toBe('100%');
    });
  });
});

describe('ConfigSelect streaming/thinking switches are provider-agnostic', () => {
  // 流式/思考区开关为 provider/调用模式无关的纯显示开关,对所有 provider(Claude/Codex/OpenCode)统一显示:
  // 流式 off → 后端缓冲到 turn 边界一次性推送(非增量);
  // 思考区 off → 不推送 thinking 类型数据(模型照常思考,纯显示控制)。
  beforeEach(() => {
    window.sendToJava = vi.fn();
    window.updateProviders = undefined;
    window.updateCodexProviders = undefined;
    window.updateOpenCodeProviders = undefined;
    window.updateActiveProvider = undefined;
    window.updateActiveCodexProvider = undefined;
    window.updateActiveOpenCodeProvider = undefined;
  });

  const openMenu = (provider: string) => {
    render(<ConfigSelect currentProvider={provider} />);
    fireEvent.click(screen.getByRole('button', { name: /Configure/i }));
  };

  it('shows streaming/thinking switches for Claude provider', () => {
    openMenu('claude');
    expect(screen.getByText('Streaming')).toBeTruthy();
    expect(screen.getByText('Thinking')).toBeTruthy();
  });

  it('shows streaming/thinking switches for Codex provider', () => {
    openMenu('codex');
    expect(screen.getByText('Streaming')).toBeTruthy();
    expect(screen.getByText('Thinking')).toBeTruthy();
  });

  it('shows streaming/thinking switches for OpenCode provider', () => {
    openMenu('opencode');
    expect(screen.getByText('Streaming')).toBeTruthy();
    expect(screen.getByText('Thinking')).toBeTruthy();
  });
  // 本地 RuntimeProviderSelect 对 opencode 有独立 providerKind 实现,不隐藏;
  // 仅 grok/kimi/pi/omp/dsh(回退 claude 列表,无意义)隐藏。
  it.each(['grok', 'kimi', 'pi', 'omp', 'dsh'])(
    'hides the runtime provider entry for the %s CLI',
    (provider) => {
      render(<ConfigSelect currentProvider={provider} />);

      fireEvent.click(screen.getByRole('button', { name: /Configure/i }));

      expect(screen.queryByText('Switch provider')).toBeNull();
      expect(screen.getByTestId('config-option-node-processes')).toBeTruthy();
    },
  );
});
