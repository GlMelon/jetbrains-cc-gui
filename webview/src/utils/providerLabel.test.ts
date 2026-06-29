import { describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';
import { getProviderDisplayName } from './providerLabel';

/** Builds a minimal t() that resolves `providers.<id>.label` from a map, mimicking
 *  i18next's "return the key when missing" behavior. */
const makeT = (labels: Record<string, string>): TFunction =>
  ((key: string) => labels[key] ?? key) as unknown as TFunction;

describe('getProviderDisplayName', () => {
  it('returns the Codex label for codex', () => {
    expect(getProviderDisplayName('codex', makeT({ 'providers.codex.label': 'Codex' }))).toBe('Codex');
  });

  it('returns the OpenCode label for opencode', () => {
    expect(getProviderDisplayName('opencode', makeT({ 'providers.opencode.label': 'OpenCode' }))).toBe('OpenCode');
  });

  it('returns the Claude Code label for claude', () => {
    expect(getProviderDisplayName('claude', makeT({ 'providers.claude.label': 'Claude Code' }))).toBe('Claude Code');
  });

  it('defaults to claude when the provider is undefined', () => {
    expect(getProviderDisplayName(undefined, makeT({ 'providers.claude.label': 'Claude Code' }))).toBe('Claude Code');
  });

  it('falls back to brand defaults when the i18n label is missing', () => {
    const t = ((key: string) => key) as unknown as TFunction; // i18next returns the key when missing
    expect(getProviderDisplayName('codex', t)).toBe('Codex');
    expect(getProviderDisplayName('opencode', t)).toBe('OpenCode');
    expect(getProviderDisplayName('claude', t)).toBe('Claude Code');
    expect(getProviderDisplayName('unknown-provider', t)).toBe('Claude Code');
  });
});
