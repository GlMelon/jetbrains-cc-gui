import { describe, expect, it } from 'vitest';
import { normalizeSkillsConfig } from '../../../src/components/skills/SkillsSettingsSection';

describe('normalizeSkillsConfig', () => {
  it('fills missing provider buckets with empty records', () => {
    const config = normalizeSkillsConfig({
      user: {
        'user:review': {
          id: 'user:review',
          name: 'review',
          type: 'directory',
          scope: 'user',
          path: 'C:\\Users\\me\\.agents\\skills\\review',
          enabled: true,
        },
      },
    });

    expect(config.global).toEqual({});
    expect(config.local).toEqual({});
    expect(Object.keys(config.user ?? {})).toEqual(['user:review']);
    expect(config.repo).toEqual({});
  });

  it('returns empty buckets for malformed payloads', () => {
    expect(normalizeSkillsConfig(null)).toEqual({
      global: {},
      local: {},
      user: {},
      repo: {},
    });
  });
});
