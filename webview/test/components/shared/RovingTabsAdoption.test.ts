import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const TAB_COMPONENTS = [
  'src/components/shared/DualViewSwitcher.tsx',
  'src/components/skills/SkillMarketDialog.tsx',
  'src/components/settings/ProviderTabSection/index.tsx',
  'src/components/settings/BasicConfigSection/AppearanceTab.tsx',
];

describe('roving tabs adoption contract', () => {
  it.each(TAB_COMPONENTS)('%s uses shared keyboard behavior and linked panels', (relativePath) => {
    const source = readFileSync(resolve(process.cwd(), relativePath), 'utf8');

    expect(source).toContain('useRovingTabs');
    expect(source).toContain('role="tablist"');
    expect(source).toContain('role="tab"');
    expect(source).toContain('aria-controls');
    expect(source).toContain('role="tabpanel"');
    expect(source).toContain('aria-labelledby');
  });
});
