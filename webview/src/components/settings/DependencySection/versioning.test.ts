import { describe, expect, it } from 'vitest';
import {
  buildVersionOptions,
  getRequestedVersion,
  resolveVersionAction,
} from './versioning';

describe('DependencySection versioning helpers', () => {
  it('uses the selected dropdown version as the requested version', () => {
    expect(getRequestedVersion(' v0.2.88 ')).toBe('0.2.88');
  });

  it('returns undefined when no dropdown version is selected', () => {
    expect(getRequestedVersion('')).toBeUndefined();
  });

  it('resolves install when the SDK is not installed', () => {
    expect(resolveVersionAction({
      installed: false,
      targetVersion: '0.2.88',
    })).toBe('install');
  });

  it('looks up the action from the backend versionActions map by target version', () => {
    const versionActions = {
      '0.2.81': 'rollback',
      '0.2.88': 'current',
      '0.2.90': 'update',
    } as const;

    expect(resolveVersionAction({
      installed: true,
      targetVersion: '0.2.90',
      versionActions,
    })).toBe('update');

    expect(resolveVersionAction({
      installed: true,
      targetVersion: '0.2.81',
      versionActions,
    })).toBe('rollback');

    expect(resolveVersionAction({
      installed: true,
      targetVersion: '0.2.88',
      versionActions,
    })).toBe('current');
  });

  it('falls back to current when the map is missing or target version is absent', () => {
    // 已安装但后端未下发 versionActions（降级保护）
    expect(resolveVersionAction({
      installed: true,
      targetVersion: '0.2.90',
    })).toBe('current');

    // 已安装但目标版本未在下发表内
    expect(resolveVersionAction({
      installed: true,
      targetVersion: '0.2.99',
      versionActions: { '0.2.88': 'current' },
    })).toBe('current');

    // 已安装但目标版本为空（用户未选择）
    expect(resolveVersionAction({
      installed: true,
      versionActions: { '0.2.88': 'current' },
    })).toBe('current');
  });

  it('keeps the installed version in the options even when it was not returned by the registry', () => {
    expect(buildVersionOptions({
      availableVersions: ['0.2.90', '0.2.89'],
      fallbackVersions: ['0.2.88'],
      installedVersion: '0.2.81',
    })).toEqual(['0.2.90', '0.2.89', '0.2.88', '0.2.81']);
  });

});
