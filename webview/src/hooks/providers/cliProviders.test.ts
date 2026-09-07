import { describe, expect, it } from 'vitest';
import { isCliOnlyProvider, normalizeCliPermissionMode, ompModeForModelId } from './cliProviders';
import type { ModelInfo, PermissionMode } from '../../components/ChatInputBox/types';

describe('normalizeCliPermissionMode', () => {
  it('preserves plan/smol/slow for the omp provider (model roles)', () => {
    // smol/slow 是 omp 的 model role(后端 SessionState 白名单含),PermissionMode 静态类型之外,cast 处理。
    expect(normalizeCliPermissionMode('plan', 'omp')).toBe('plan');
    expect(normalizeCliPermissionMode('smol' as PermissionMode, 'omp')).toBe('smol');
    expect(normalizeCliPermissionMode('slow' as PermissionMode, 'omp')).toBe('slow');
    expect(normalizeCliPermissionMode('default', 'omp')).toBe('default');
    expect(normalizeCliPermissionMode('auto', 'omp')).toBe('default');
    expect(normalizeCliPermissionMode('autoEdit', 'omp')).toBe('default');
    expect(normalizeCliPermissionMode('acceptEdits', 'omp')).toBe('acceptEdits');
  });

  it('keeps coercing unsupported plan/auto modes to default for the other CLI providers', () => {
    for (const provider of ['pi', 'kimi', 'opencode']) {
      expect(normalizeCliPermissionMode('plan', provider)).toBe('default');
      expect(normalizeCliPermissionMode('auto', provider)).toBe('default');
      expect(normalizeCliPermissionMode('autoEdit', provider)).toBe('acceptEdits');
    }
  });

  it('preserves auto for grok (native auto-approve alias) while still coercing plan', () => {
    // Mirrors the backend degrade rule in SessionSendService: auto survives on
    // claude/codex/grok only.
    expect(normalizeCliPermissionMode('auto', 'grok')).toBe('auto');
    expect(normalizeCliPermissionMode('plan', 'grok')).toBe('default');
  });

  it('coerces unsupported plan/auto modes to default when no provider is given (legacy callers)', () => {
    expect(normalizeCliPermissionMode('plan')).toBe('default');
    expect(normalizeCliPermissionMode('auto')).toBe('default');
    expect(normalizeCliPermissionMode('default')).toBe('default');
  });

  it('passes non-plan modes through unchanged for other providers', () => {
    expect(normalizeCliPermissionMode('acceptEdits', 'pi')).toBe('acceptEdits');
    expect(normalizeCliPermissionMode('autoEdit', 'pi')).toBe('acceptEdits');
    expect(normalizeCliPermissionMode('bypassPermissions', 'grok')).toBe('bypassPermissions');
  });
});

describe('ompModeForModelId', () => {
  // A1:OMP_ROLE_MODELS 静态表已清空;测试显式构造 role fixtures(动态 listModels 形态)。
  const ROLE_FIXTURES: ModelInfo[] = [
    { id: 'smol', identifier: 'smol', label: 'Smol' },
    { id: 'slow', identifier: 'slow', label: 'Slow' },
    { id: 'plan', identifier: 'plan', label: 'Plan' },
  ];

  it('maps static role model ids to the same-named mode', () => {
    expect(ompModeForModelId('smol', ROLE_FIXTURES)).toBe('smol');
    expect(ompModeForModelId('slow', ROLE_FIXTURES)).toBe('slow');
    expect(ompModeForModelId('plan', ROLE_FIXTURES)).toBe('plan');
  });

  it('maps catalog model ids and auto to default', () => {
    expect(ompModeForModelId('github-copilot/claude-fable-5', ROLE_FIXTURES)).toBe('default');
    expect(ompModeForModelId('auto', ROLE_FIXTURES)).toBe('default');
  });

  it('maps a dynamic role to the same-named mode only when present in roles', () => {
    const roles = [...ROLE_FIXTURES, { id: 'designer', identifier: 'designer', label: 'Designer', description: 'opencode-go/deepseek-v4-flash' }];
    expect(ompModeForModelId('designer', roles)).toBe('designer');
    expect(ompModeForModelId('designer', ROLE_FIXTURES)).toBe('default');
  });
});

describe('isCliOnlyProvider', () => {
  it('recognizes omp as a CLI-only provider', () => {
    expect(isCliOnlyProvider('omp')).toBe(true);
    expect(isCliOnlyProvider('pi')).toBe(true);
    expect(isCliOnlyProvider('claude')).toBe(false);
    expect(isCliOnlyProvider(undefined)).toBe(false);
  });
});
