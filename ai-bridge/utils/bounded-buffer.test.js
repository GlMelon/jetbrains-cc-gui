import test from 'node:test';
import assert from 'node:assert/strict';
import { appendBounded } from './bounded-buffer.js';

test('appendBounded keeps content under the bound untouched', () => {
  assert.equal(appendBounded('', 'hello', 10), 'hello');
  assert.equal(appendBounded('abc', 'def', 6), 'abcdef');
});

test('appendBounded rolls the tail, keeping only the most recent characters', () => {
  assert.equal(appendBounded('abc', 'def', 4), 'cdef');
  assert.equal(appendBounded('', 'x'.repeat(100), 10), 'x'.repeat(10));
  // 单个 chunk 自身超限也只保留其末尾
  assert.equal(appendBounded('old', '0123456789', 5), '56789');
});
