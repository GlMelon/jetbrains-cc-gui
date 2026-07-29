import assert from 'node:assert/strict';
import test from 'node:test';

import { buildIDEContextPrompt, sanitizePromptValue } from './system-prompts.js';

test('sanitizePromptValue handles empty, unsafe, and overlong metadata', () => {
  assert.equal(sanitizePromptValue(null), '');
  assert.equal(sanitizePromptValue(undefined), '');
  assert.equal(sanitizePromptValue('  src/`unsafe`\nfile.ts\t'), 'src/ unsafe file.ts');
  assert.equal(sanitizePromptValue(42), '42');

  const exactLimit = 'a'.repeat(256);
  assert.equal(sanitizePromptValue(exactLimit), exactLimit);
  assert.equal(sanitizePromptValue(`${exactLimit}b`), `${exactLimit}...`);
});

test('buildIDEContextPrompt returns base constraints for absent or empty IDE context', () => {
  const absent = buildIDEContextPrompt(null);
  assert.match(absent, /Windows path/i);
  assert.doesNotMatch(absent, /User's Current IDE Context/);

  const empty = buildIDEContextPrompt({});
  assert.match(empty, /Windows path/i);
  assert.doesNotMatch(empty, /User's Current IDE Context/);
});

test('buildIDEContextPrompt includes short and long agent instructions', () => {
  const shortPrompt = buildIDEContextPrompt(null, '  reviewer  ');
  assert.match(shortPrompt, /Agent Role and Instructions/);
  assert.match(shortPrompt, /reviewer/);

  const longAgent = 'x'.repeat(101);
  const longPrompt = buildIDEContextPrompt(null, longAgent);
  assert.match(longPrompt, /Agent Role and Instructions/);
  assert.match(longPrompt, new RegExp(`x{101}`));

  const blankPrompt = buildIDEContextPrompt(null, '   ');
  assert.doesNotMatch(blankPrompt, /Agent Role and Instructions/);
});

test('buildIDEContextPrompt renders complete workspace, selection, and other-file context', () => {
  const prompt = buildIDEContextPrompt({
    active: 'workspace/app/src/index.ts#L2-4',
    selection: {
      startLine: 2,
      endLine: 4,
      selectedText: 'const value = 1;',
    },
    others: ['workspace/lib/a.ts', 'workspace/lib/`unsafe`.ts'],
    isWorkspace: true,
    workspaceRoot: 'workspace',
    activeSubproject: 'app',
    subprojects: [
      { name: 'app', path: 'workspace/app', type: 'frontend' },
      { name: '', path: '', type: '', loaded: false },
    ],
    modules: [],
  });

  assert.match(prompt, /Multi-Project Workspace Structure/);
  assert.match(prompt, /Workspace Root.*workspace/);
  assert.match(prompt, /\*\*app\*\* \(frontend\)/);
  assert.match(prompt, /\*\*unknown\*\* \[not loaded\]/);
  assert.match(prompt, /active file belongs to subproject \*\*app\*\*/);
  assert.match(prompt, /Currently Active File/);
  assert.match(prompt, /selected lines 2-4/);
  assert.match(prompt, /const value = 1;/);
  assert.match(prompt, /Other Open Files/);
  assert.match(prompt, /workspace\/lib\/ unsafe \.ts/);
  assert.match(prompt, /target the appropriate subproject/);
});

test('buildIDEContextPrompt renders module and active-file context without selection', () => {
  const prompt = buildIDEContextPrompt({
    active: 'src/main.ts',
    selection: null,
    others: [],
    isWorkspace: false,
    modules: [{ name: 'core' }, {}],
  });

  assert.match(prompt, /Project Module Structure/);
  assert.match(prompt, /`core`/);
  assert.match(prompt, /`unknown`/);
  assert.match(prompt, /No code is currently selected/);
  assert.doesNotMatch(prompt, /Multi-Project Workspace Structure/);
  assert.doesNotMatch(prompt, /Other Open Files/);
});

test('buildIDEContextPrompt supports other files without an active file', () => {
  const prompt = buildIDEContextPrompt({
    active: '',
    selection: { selectedText: '' },
    others: ['src/secondary.ts'],
    isWorkspace: false,
    modules: [{ name: 'single' }],
  });

  assert.doesNotMatch(prompt, /Currently Active File/);
  assert.match(prompt, /Other Open Files/);
  assert.match(prompt, /src\/secondary\.ts/);
  assert.doesNotMatch(prompt, /Project Module Structure/);
});
