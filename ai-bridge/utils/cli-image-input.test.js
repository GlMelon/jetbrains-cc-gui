/**
 * Unit tests for CLI image attachment helpers.
 * Run: node --test utils/cli-image-input.test.js
 */

import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  buildGrokImageBlocks,
  buildKimiPromptWithImages,
  buildReadPathPromptWithImages,
  estimateBase64DecodedBytes,
  KIMI_IMAGE_INJECTION_MARKER,
  normalizeImageMimeType,
  parseAttachmentData,
} from './cli-image-input.js';

// 1x1 PNG
const TINY_PNG_B64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

describe('parseAttachmentData', () => {
  it('accepts raw base64', () => {
    const parsed = parseAttachmentData(TINY_PNG_B64);
    assert.ok(parsed);
    assert.equal(parsed.mimeType, null);
    assert.equal(parsed.base64, TINY_PNG_B64);
  });

  it('accepts data URL', () => {
    const parsed = parseAttachmentData(`data:image/png;base64,${TINY_PNG_B64}`);
    assert.ok(parsed);
    assert.equal(parsed.mimeType, 'image/png');
    assert.equal(parsed.base64, TINY_PNG_B64);
  });

  it('rejects empty', () => {
    assert.equal(parseAttachmentData(''), null);
    assert.equal(parseAttachmentData(null), null);
  });
});

describe('buildGrokImageBlocks', () => {
  it('builds ACP image blocks from attachments', () => {
    const { blocks, loaded, errors } = buildGrokImageBlocks([
      { fileName: 'dot.png', mediaType: 'image/png', data: TINY_PNG_B64 },
    ]);
    assert.equal(loaded, 1);
    assert.equal(errors.length, 0);
    assert.equal(blocks.length, 1);
    assert.equal(blocks[0].type, 'image');
    assert.equal(blocks[0].mimeType, 'image/png');
    assert.ok(blocks[0].data.length > 0);
  });

  it('skips non-image attachments', () => {
    const { blocks, loaded } = buildGrokImageBlocks([
      { fileName: 'notes.txt', mediaType: 'text/plain', data: 'aGVsbG8=' },
    ]);
    assert.equal(loaded, 0);
    assert.equal(blocks.length, 0);
  });
});

describe('kimi / read-path prompt builders', () => {
  it('kimi injects ReadMediaFile instructions and path tags', () => {
    const prompt = buildKimiPromptWithImages('describe', ['/tmp/a.png']);
    assert.ok(prompt.includes(KIMI_IMAGE_INJECTION_MARKER));
    assert.ok(prompt.includes('ReadMediaFile'));
    assert.ok(prompt.includes('<image path="/tmp/a.png"></image>'));
    assert.ok(prompt.startsWith('describe'));
  });

  it('read-path builder keeps user text and lists paths', () => {
    const prompt = buildReadPathPromptWithImages('hello', ['/tmp/b.png']);
    assert.ok(prompt.includes('/tmp/b.png'));
    assert.ok(prompt.includes('Read tool'));
    assert.ok(prompt.includes('hello'));
  });
});

describe('misc helpers', () => {
  it('normalizeImageMimeType defaults to png', () => {
    assert.equal(normalizeImageMimeType('image/jpeg'), 'image/jpeg');
    assert.equal(normalizeImageMimeType(''), 'image/png');
  });

  it('estimateBase64DecodedBytes handles padding', () => {
    assert.equal(estimateBase64DecodedBytes('YQ=='), 1);
    assert.equal(estimateBase64DecodedBytes('YWI='), 2);
    assert.equal(estimateBase64DecodedBytes('YWJj'), 3);
  });
});
