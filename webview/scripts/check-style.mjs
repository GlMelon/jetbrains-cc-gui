import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const WEBVIEW_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const REPOSITORY_ROOT = path.resolve(WEBVIEW_ROOT, '..');
const SCRIPT_REPOSITORY_PATH = 'webview/scripts/check-style.mjs';
const EMPTY_TREE_SHA = '4b825dc642cb6eb9a060e54bf8d69288fbee4904';
const ZERO_SHA_PATTERN = /^0+$/;

const FORMAT_PATTERNS = [/^src\/.+\.(?:ts|tsx|less|json)$/, /^scripts\/.+\.(?:mjs|js)$/];

function parseArguments(argv) {
  const options = {
    staged: false,
    adoptionBaseline: process.env.STYLE_ADOPTION_BASELINE === '1',
    base: process.env.STYLE_BASE_SHA || null,
    head: process.env.STYLE_HEAD_SHA || 'HEAD',
  };

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === '--staged') {
      options.staged = true;
    } else if (argument === '--adoption-baseline') {
      options.adoptionBaseline = true;
    } else if (argument === '--base' || argument === '--head') {
      const value = argv[index + 1];
      if (!value) {
        throw new Error(`${argument} requires a git revision`);
      }
      options[argument.slice(2)] = value;
      index += 1;
    } else if (argument.startsWith('--base=')) {
      options.base = argument.slice('--base='.length);
    } else if (argument.startsWith('--head=')) {
      options.head = argument.slice('--head='.length);
    } else {
      throw new Error(`Unknown argument: ${argument}`);
    }
  }

  if (options.staged && options.base) {
    throw new Error('--staged cannot be combined with --base');
  }

  return options;
}

function runGit(arguments_, { allowFailure = false } = {}) {
  try {
    return execFileSync('git', arguments_, {
      cwd: REPOSITORY_ROOT,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', allowFailure ? 'ignore' : 'inherit'],
    }).trim();
  } catch (error) {
    if (allowFailure) {
      return null;
    }
    throw error;
  }
}

function splitLines(output) {
  if (!output) {
    return [];
  }
  return output.split(/\r?\n/u).filter(Boolean);
}

function normalizeRevision(revision) {
  return ZERO_SHA_PATTERN.test(revision) ? EMPTY_TREE_SHA : revision;
}

function findAdoptionCommit(head) {
  const output = runGit(
    ['log', head, '--diff-filter=A', '--format=%H', '--follow', '--', SCRIPT_REPOSITORY_PATH],
    { allowFailure: true },
  );
  return splitLines(output).at(-1) ?? null;
}

function isAncestor(ancestor, descendant) {
  const result = spawnSync('git', ['merge-base', '--is-ancestor', ancestor, descendant], {
    cwd: REPOSITORY_ROOT,
    stdio: 'ignore',
  });
  return result.status === 0;
}

function resolveDiffBase(base, head, adoptionBaseline) {
  const normalizedBase = normalizeRevision(base);
  let diffBase = normalizedBase;

  if (normalizedBase !== EMPTY_TREE_SHA) {
    diffBase = runGit(['merge-base', normalizedBase, head]);
  }

  if (!adoptionBaseline) {
    return diffBase;
  }

  const adoptionCommit = findAdoptionCommit(head);
  if (!adoptionCommit || !isAncestor(adoptionCommit, head)) {
    return diffBase;
  }

  if (diffBase === EMPTY_TREE_SHA || isAncestor(diffBase, adoptionCommit)) {
    return adoptionCommit;
  }

  return diffBase;
}

function toWebviewRelativePath(repositoryPath) {
  const normalized = repositoryPath.replaceAll('\\', '/');
  if (!normalized.startsWith('webview/')) {
    return null;
  }
  return normalized.slice('webview/'.length);
}

function isFormatCandidate(relativePath) {
  return FORMAT_PATTERNS.some((pattern) => pattern.test(relativePath));
}

function collectFormatCandidates(options) {
  let repositoryPaths;

  if (options.staged) {
    repositoryPaths = splitLines(
      runGit([
        'diff',
        '--cached',
        '--name-only',
        '--diff-filter=ACMR',
        '--',
        'webview/src',
        'webview/scripts',
      ]),
    );
  } else if (options.base) {
    const diffBase = resolveDiffBase(options.base, options.head, options.adoptionBaseline);
    console.log(`[style] checking changed files from ${diffBase} to ${options.head}`);
    repositoryPaths = splitLines(
      runGit([
        'diff',
        '--name-only',
        '--diff-filter=ACMR',
        diffBase,
        options.head,
        '--',
        'webview/src',
        'webview/scripts',
      ]),
    );
  } else {
    repositoryPaths = [
      ...splitLines(
        runGit([
          'diff',
          '--name-only',
          '--diff-filter=ACMR',
          'HEAD',
          '--',
          'webview/src',
          'webview/scripts',
        ]),
      ),
      ...splitLines(
        runGit([
          'ls-files',
          '--others',
          '--exclude-standard',
          '--',
          'webview/src',
          'webview/scripts',
        ]),
      ),
    ];
  }

  return [...new Set(repositoryPaths.map(toWebviewRelativePath).filter(Boolean))]
    .filter(isFormatCandidate)
    .sort();
}

function runNodeTool(entryPoint, arguments_) {
  const result = spawnSync(process.execPath, [path.join(WEBVIEW_ROOT, entryPoint), ...arguments_], {
    cwd: WEBVIEW_ROOT,
    stdio: 'inherit',
  });

  if (result.error) {
    throw result.error;
  }

  return result.status ?? 1;
}

function runPrettierCheck(files) {
  if (files.length === 0) {
    console.log('[style] no changed Webview files require Prettier checks');
    return 0;
  }

  console.log(`[style] running Prettier checks for ${files.length} changed file(s)`);
  return runNodeTool('node_modules/prettier/bin/prettier.cjs', ['--check', ...files]);
}

function runEslint() {
  console.log('[style] running full Webview ESLint checks');
  return runNodeTool('node_modules/eslint/bin/eslint.js', ['src', 'scripts']);
}

try {
  const options = parseArguments(process.argv.slice(2));
  const formatCandidates = collectFormatCandidates(options);
  const prettierStatus = runPrettierCheck(formatCandidates);
  const eslintStatus = runEslint();
  process.exitCode = prettierStatus || eslintStatus ? 1 : 0;
} catch (error) {
  console.error(`[style] ${error instanceof Error ? error.message : String(error)}`);
  process.exitCode = 1;
}
