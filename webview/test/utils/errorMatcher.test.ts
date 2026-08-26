import { describe, expect, it } from 'vitest';
import { matchErrorPattern } from '../../src/utils/errorMatcher';

describe('matchErrorPattern', () => {
  it('returns null for empty input', () => {
    expect(matchErrorPattern('')).toBeNull();
  });

  it('returns null when no pattern matches the error text', () => {
    expect(matchErrorPattern('Some unrelated error message')).toBeNull();
  });

  it('matches spawn EBUSY error and exposes both solutions', () => {
    const result = matchErrorPattern('Error: spawn EBUSY');
    expect(result?.code).toBe('spawnEbusy');
    expect(result?.solutions).toHaveLength(2);
    const checkNode = result?.solutions.find((s) => s.key === 'checkNodeVersion');
    expect(checkNode?.recommended).toBe(true);
    expect(checkNode?.steps[0]?.kind).toBe('command');
    if (checkNode?.steps[0]?.kind === 'command') {
      expect(checkNode.steps[0].command).toBe('node -v');
    }
    const reinstall = result?.solutions.find((s) => s.key === 'reinstallLatestSdk');
    expect(reinstall?.steps[0]?.kind).toBe('command');
    expect(reinstall?.steps[1]?.kind).toBe('navigation');
  });

  it('matches spawn EBUSY case-insensitively', () => {
    expect(matchErrorPattern('SPAWN EBUSY')?.code).toBe('spawnEbusy');
    expect(matchErrorPattern('something failed: spawn ebusy at line 42')?.code).toBe('spawnEbusy');
  });
});
