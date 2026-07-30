import { describe, expect, it } from 'vitest';
import {
  compareProtocolEntries,
  parseGeneratedProtocolConstant,
} from '../../scripts/check-event-literals.mjs';

describe('check-event-literals protocol consistency', () => {
  it('parses generated UPSTREAM and DOWNSTREAM constant blocks', () => {
    const source = `
      export const UPSTREAM = {
        SEND: 'send' as const,
      } as const;
      export const DOWNSTREAM = {
        READY: 'ready' as const,
        DONE: 'done' as const,
      } as const;
    `;

    expect(parseGeneratedProtocolConstant(source, 'UPSTREAM')).toEqual([
      { name: 'SEND', value: 'send' },
    ]);
    expect(parseGeneratedProtocolConstant(source, 'DOWNSTREAM')).toEqual([
      { name: 'READY', value: 'ready' },
      { name: 'DONE', value: 'done' },
    ]);
  });

  it('fails parsing when a generated constant block disappears', () => {
    expect(() => parseGeneratedProtocolConstant('export const OTHER = {} as const;', 'UPSTREAM'))
      .toThrow(/UPSTREAM/);
  });

  it('detects missing, unexpected, value drift and duplicate names', () => {
    const errors = compareProtocolEntries(
      [
        { name: 'A', value: 'a' },
        { name: 'B', value: 'b' },
      ],
      [
        { name: 'A', value: 'changed' },
        { name: 'C', value: 'c' },
        { name: 'C', value: 'c-again' },
      ],
      'java',
      'generated',
    );

    expect(errors).toEqual(expect.arrayContaining([
      expect.stringContaining('value drift for A'),
      expect.stringContaining('missing B'),
      expect.stringContaining('unexpected C'),
      expect.stringContaining('duplicate name C'),
    ]));
  });

  it('accepts identical name/value sets regardless of entry order', () => {
    expect(compareProtocolEntries(
      [{ name: 'A', value: 'a' }, { name: 'B', value: 'b' }],
      [{ name: 'B', value: 'b' }, { name: 'A', value: 'a' }],
      'java',
      'manifest',
    )).toEqual([]);
  });
});
