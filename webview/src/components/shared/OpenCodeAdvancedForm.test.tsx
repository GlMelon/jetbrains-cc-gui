import { fireEvent, render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import OpenCodeAdvancedForm, { extractAdvancedRaw } from './OpenCodeAdvancedForm';
import type { OpenCodeAdvancedFormState } from './dualView/adapters';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k }),
}));

describe('extractAdvancedRaw', () => {
  it('剥离已知业务字段,保留 opencode 原生透传字段(npm/options/custom 等)', () => {
    const raw = extractAdvancedRaw({
      id: 'openglm',
      name: 'GLM',
      baseURL: 'http://x',
      apiBase: 'http://y',
      apiKey: 'sk',
      models: { 'glm-5.2': {} },
      createdAt: 123,
      isActive: true,
      npm: '@opencode/opencode',
      options: { model: 'x' },
      customCommand: 'foo',
    } as any);
    expect(raw.npm).toBe('@opencode/opencode');
    expect(raw.options).toEqual({ model: 'x' });
    expect(raw.customCommand).toBe('foo');
    // 已知业务字段全部剥离
    for (const k of ['id', 'name', 'baseURL', 'apiBase', 'apiKey', 'models', 'createdAt', 'isActive']) {
      expect(raw[k]).toBeUndefined();
    }
  });

  it('只含已知字段 → {}', () => {
    expect(extractAdvancedRaw({ id: 'x', name: 'y', models: {} } as any)).toEqual({});
  });

  it('空对象 → {}', () => {
    expect(extractAdvancedRaw({} as any)).toEqual({});
  });
});

describe('OpenCodeAdvancedForm', () => {
  it('渲染 npm 字段(回显现有值)', () => {
    render(
      <OpenCodeAdvancedForm
        state={{ raw: { npm: '@opencode/opencode', options: {} } } as OpenCodeAdvancedFormState}
        onChange={vi.fn()}
      />,
    );
    expect((screen.getByLabelText('npm-package') as HTMLInputElement).value).toBe('@opencode/opencode');
  });

  it('npm 缺失 → 空串(不显示 undefined)', () => {
    render(
      <OpenCodeAdvancedForm state={{ raw: {} } as OpenCodeAdvancedFormState} onChange={vi.fn()} />,
    );
    expect((screen.getByLabelText('npm-package') as HTMLInputElement).value).toBe('');
  });

  it('改 npm → onChange 透传并保留其余 raw 字段(options 不丢)', () => {
    const onChange = vi.fn();
    render(
      <OpenCodeAdvancedForm
        state={{ raw: { npm: 'x', options: { k: 1 } } } as OpenCodeAdvancedFormState}
        onChange={onChange}
      />,
    );
    fireEvent.change(screen.getByLabelText('npm-package'), { target: { value: 'y' } });
    expect(onChange).toHaveBeenCalledWith({ raw: { npm: 'y', options: { k: 1 } } });
  });
});
