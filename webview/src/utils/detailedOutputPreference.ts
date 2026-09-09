const DETAILED_OUTPUT_ENABLED_KEY = 'detailedOutputEnabled';
const DETAILED_OUTPUT_ENABLED_EVENT = 'detailed-output-enabled-changed';
const DETAILED_OUTPUT_ENABLED_DEFAULT = true;

export interface DetailedOutputEnabledChangedDetail {
  enabled: boolean;
}

export function getDetailedOutputEnabled(): boolean {
  try {
    const stored = localStorage.getItem(DETAILED_OUTPUT_ENABLED_KEY);
    if (stored === null) {
      return DETAILED_OUTPUT_ENABLED_DEFAULT;
    }
    return stored === 'true';
  } catch {
    return DETAILED_OUTPUT_ENABLED_DEFAULT;
  }
}

export function setDetailedOutputEnabled(enabled: boolean): void {
  try {
    localStorage.setItem(DETAILED_OUTPUT_ENABLED_KEY, enabled ? 'true' : 'false');
  } catch (error) {
    console.warn('[detailedOutputPreference] failed to persist:', error);
    return;
  }

  window.dispatchEvent(new CustomEvent<DetailedOutputEnabledChangedDetail>(
    DETAILED_OUTPUT_ENABLED_EVENT,
    { detail: { enabled } }
  ));
}
