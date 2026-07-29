import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PROVIDER_TYPE } from '../../../../src/generated/protocol';
import { promptProvider, resetPromptsState, setupPromptsCallback } from '../../../../src/components/ChatInputBox/providers/promptProvider';

describe('promptProvider provider isolation', () => {
  beforeEach(() => {
    resetPromptsState();
    window.sendToJava = vi.fn();
    setupPromptsCallback();
  });

  it('loads only prompts owned by the requested provider', async () => {
    const loading = promptProvider('', new AbortController().signal, PROVIDER_TYPE.CODEX);
    window.updateGlobalPrompts?.(
      JSON.stringify({
        provider: PROVIDER_TYPE.CODEX,
        prompts: [
          { id: 'codex-1', name: 'Codex prompt', content: 'codex', provider: PROVIDER_TYPE.CODEX },
          {
            id: 'claude-1',
            name: 'Claude prompt',
            content: 'claude',
            provider: PROVIDER_TYPE.CLAUDE,
          },
        ],
      }),
    );
    window.updateProjectPrompts?.(JSON.stringify({ provider: PROVIDER_TYPE.CODEX, prompts: [] }));

    const items = await loading;

    expect(items.map((item) => item.id)).toContain('codex-1');
    expect(items.map((item) => item.id)).not.toContain('claude-1');
  });

  it('ignores callbacks for a provider that is no longer active', async () => {
    window.updateGlobalPrompts?.(
      JSON.stringify({
        provider: PROVIDER_TYPE.CODEX,
        prompts: [
          { id: 'codex-1', name: 'Codex prompt', content: 'codex', provider: PROVIDER_TYPE.CODEX },
        ],
      }),
    );
    await promptProvider('', new AbortController().signal, PROVIDER_TYPE.CODEX);

    await promptProvider('', new AbortController().signal, PROVIDER_TYPE.CLAUDE);
    window.updateGlobalPrompts?.(
      JSON.stringify({
        provider: PROVIDER_TYPE.CODEX,
        prompts: [
          {
            id: 'codex-2',
            name: 'Stale Codex prompt',
            content: 'codex',
            provider: PROVIDER_TYPE.CODEX,
          },
        ],
      }),
    );
    window.updateGlobalPrompts?.(
      JSON.stringify({
        provider: PROVIDER_TYPE.CLAUDE,
        prompts: [{ id: 'claude-1', name: 'Claude prompt', content: 'claude' }],
      }),
    );
    window.updateProjectPrompts?.(JSON.stringify({ provider: PROVIDER_TYPE.CLAUDE, prompts: [] }));

    const items = await promptProvider('', new AbortController().signal, PROVIDER_TYPE.CLAUDE);

    expect(items.map((item) => item.id)).toContain('claude-1');
    expect(items.map((item) => item.id)).not.toContain('codex-2');
  });

  it('treats legacy array payloads as Claude-only data', async () => {
    const loading = promptProvider('', new AbortController().signal, PROVIDER_TYPE.CODEX);
    window.updateGlobalPrompts?.(
      JSON.stringify([{ id: 'legacy-1', name: 'Legacy prompt', content: 'legacy' }]),
    );
    window.updateProjectPrompts?.(
      JSON.stringify({
        provider: PROVIDER_TYPE.CODEX,
        prompts: [],
      }),
    );

    const items = await loading;

    expect(items.map((item) => item.id)).not.toContain('legacy-1');
  });

  it('keeps OpenCode prompts isolated from Claude and Codex', async () => {
    const loading = promptProvider('', new AbortController().signal, PROVIDER_TYPE.OPENCODE);
    window.updateGlobalPrompts?.(
      JSON.stringify({
        provider: PROVIDER_TYPE.OPENCODE,
        prompts: [
          {
            id: 'opencode-1',
            name: 'OpenCode prompt',
            content: 'opencode',
            provider: PROVIDER_TYPE.OPENCODE,
          },
          {
            id: 'codex-1',
            name: 'Codex prompt',
            content: 'codex',
            provider: PROVIDER_TYPE.CODEX,
          },
        ],
      }),
    );
    window.updateProjectPrompts?.(
      JSON.stringify({
        provider: PROVIDER_TYPE.OPENCODE,
        prompts: [],
      }),
    );

    const items = await loading;

    expect(items.map((item) => item.id)).toContain('opencode-1');
    expect(items.map((item) => item.id)).not.toContain('codex-1');
  });
});
