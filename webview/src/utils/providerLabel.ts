import type { TFunction } from 'i18next';

/**
 * Resolve a provider id to its human-readable display name.
 *
 * Driven by the i18n `providers.<id>.label` SSOT — the same keys used by the
 * welcome screen and provider selectors — so every provider-aware surface stays
 * in sync. Falls back to brand defaults when the provider is unknown or its
 * label is missing (i18next returns the key itself when a translation is absent).
 */
export function getProviderDisplayName(providerId: string | undefined, t: TFunction): string {
  const id = providerId ?? 'claude';
  const key = `providers.${id}.label`;
  const label = t(key);
  // i18next returns the key itself when missing; guard against that.
  if (label && label !== key) {
    return label;
  }
  if (id === 'codex') return 'Codex';
  if (id === 'opencode') return 'OpenCode';
  return 'Claude Code';
}
