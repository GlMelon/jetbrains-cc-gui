/**
 * In-memory cache for block expand/collapse state that persists across
 * component remounts during streaming, but not across page reloads.
 *
 * NOTE: Keys are never evicted automatically. Callers should invoke
 * clearAllPersistedExpanded() on session switch to avoid unbounded growth.
 */
const expandedState = new Map<string, boolean>();

export function clearAllPersistedExpanded(): void {
  expandedState.clear();
}
