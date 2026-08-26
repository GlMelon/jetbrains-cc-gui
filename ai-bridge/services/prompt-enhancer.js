// @ts-check
/**
 * Prompt Enhancement Service.
 * Routes enhancement requests to Claude or Codex based on prompt enhancer config.
 *
 * Supports context information:
 * - User selected code snippets
 * - Current open file information (path, content, language type)
 * - Cursor position and surrounding code
 * - Related file information
 */

import { pathToFileURL } from 'node:url';

// NOTE: Direct SDK invocation has been removed (CLI-only mode).
// Prompt enhancement via this process always fails; the CLI handles it internally.

/**
 * Related-file entry supplied by the IDE for prompt enhancement.
 * @typedef {{ path?: string; content?: string }} PromptEnhancerRelatedFile
 */

/**
 * Currently-open file info supplied by the IDE for prompt enhancement.
 * @typedef {{ path?: string; language?: string; content?: string }} PromptEnhancerCurrentFile
 */

/**
 * IDE context supplied to the prompt enhancer. All fields optional.
 * @typedef {{
 *   selectedCode?: string;
 *   cursorContext?: string;
 *   cursorPosition?: { line?: number };
 *   currentFile?: PromptEnhancerCurrentFile;
 *   relatedFiles?: PromptEnhancerRelatedFile[];
 *   projectType?: string;
 * }} PromptEnhancerContext
 */

/**
 * Resolved runtime config driving provider/model selection for enhancement.
 * @typedef {{ provider: string; model: string; resolutionSource: string; actualModel: string | undefined }} PromptEnhancerRuntimeConfig
 */

const DEFAULT_PROMPT_ENHANCER_CONFIG = {
  provider: null,
  effectiveProvider: 'claude',
  resolutionSource: 'auto',
  models: {
    claude: 'claude-role-sonnet',
    codex: '',
  },
  availability: {
    claude: false,
    codex: false,
  },
};

// Context length limits (in characters) to avoid exceeding model token limits
const MAX_SELECTED_CODE_LENGTH = 2000;
const MAX_CURSOR_CONTEXT_LENGTH = 1000;
const MAX_CURRENT_FILE_LENGTH = 3000;
const MAX_RELATED_FILES_LENGTH = 2000;
const MAX_SINGLE_RELATED_FILE_LENGTH = 500;

/**
 * Read the entire stdin stream as a UTF-8 string.
 * @returns {Promise<string>}
 */
async function readStdin() {
  return new Promise((resolve, reject) => {
    let data = '';
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', (/** @type {string} */ chunk) => {
      data += chunk;
    });
    process.stdin.on('end', () => {
      resolve(data);
    });
    process.stdin.on('error', reject);
  });
}

/**
 * Truncate text to maxLength, optionally keeping the tail.
 * @param {string} text
 * @param {number} maxLength
 * @param {boolean} [fromEnd]
 * @returns {string}
 */
function truncateText(text, maxLength, fromEnd = false) {
  if (!text || text.length <= maxLength) {
    return text;
  }

  if (fromEnd) {
    return '...\n' + text.slice(-maxLength);
  }
  return text.slice(0, maxLength) + '\n...';
}

/**
 * Resolve a code language label from a file path's extension.
 * @param {string | undefined} filePath
 * @returns {string}
 */
function getLanguageFromPath(filePath) {
  if (!filePath) return 'text';

  const ext = filePath.split('.').pop()?.toLowerCase();
  /** @type {Record<string, string>} */
  const langMap = {
    'js': 'javascript',
    'jsx': 'javascript',
    'ts': 'typescript',
    'tsx': 'typescript',
    'py': 'python',
    'java': 'java',
    'kt': 'kotlin',
    'kts': 'kotlin',
    'go': 'go',
    'rs': 'rust',
    'rb': 'ruby',
    'php': 'php',
    'c': 'c',
    'cpp': 'cpp',
    'cc': 'cpp',
    'h': 'c',
    'hpp': 'cpp',
    'cs': 'csharp',
    'swift': 'swift',
    'scala': 'scala',
    'vue': 'vue',
    'html': 'html',
    'css': 'css',
    'scss': 'scss',
    'less': 'less',
    'json': 'json',
    'xml': 'xml',
    'yaml': 'yaml',
    'yml': 'yaml',
    'md': 'markdown',
    'sql': 'sql',
    'sh': 'bash',
    'bash': 'bash',
    'zsh': 'bash',
  };

  return (ext && langMap[ext]) || 'text';
}

/**
 * Compose the full enhancement prompt by splicing IDE context (selected code,
 * cursor context, current file, related files, project type) under the original prompt.
 * @param {string} originalPrompt
 * @param {PromptEnhancerContext | null | undefined} context
 * @returns {string}
 */
export function buildFullPrompt(originalPrompt, context) {
  let fullPrompt = `Please optimize the following prompt:\n\n${originalPrompt}`;

  if (!context) {
    return fullPrompt;
  }

  const contextParts = [];

  if (context.selectedCode && context.selectedCode.trim()) {
    const truncatedCode = truncateText(context.selectedCode, MAX_SELECTED_CODE_LENGTH);
    const language = context.currentFile?.language || getLanguageFromPath(context.currentFile?.path) || 'text';
    contextParts.push(`[User Selected Code]\n\`\`\`${language}\n${truncatedCode}\n\`\`\``);
    console.log(`[PromptEnhancer] Added selected code context, length: ${context.selectedCode.length}`);
  }

  if (!context.selectedCode && context.cursorContext && context.cursorContext.trim()) {
    const truncatedContext = truncateText(context.cursorContext, MAX_CURSOR_CONTEXT_LENGTH);
    const language = context.currentFile?.language || getLanguageFromPath(context.currentFile?.path) || 'text';
    const lineInfo = context.cursorPosition ? ` (line ${context.cursorPosition.line})` : '';
    contextParts.push(`[Code Around Cursor${lineInfo}]\n\`\`\`${language}\n${truncatedContext}\n\`\`\``);
    console.log(`[PromptEnhancer] Added cursor context, length: ${context.cursorContext.length}`);
  }

  if (context.currentFile) {
    const { path, language, content } = context.currentFile;
    let fileInfo = '';

    if (path) {
      const lang = language || getLanguageFromPath(path);
      fileInfo = `[Current File] ${path}\n[Language Type] ${lang}`;

      if (!context.selectedCode && !context.cursorContext && content && content.trim()) {
        const truncatedContent = truncateText(content, MAX_CURRENT_FILE_LENGTH);
        fileInfo += `\n[File Content Preview]\n\`\`\`${lang}\n${truncatedContent}\n\`\`\``;
        console.log(`[PromptEnhancer] Added file content preview, length: ${content.length}`);
      }

      contextParts.push(fileInfo);
      console.log(`[PromptEnhancer] Added current file info: ${path}`);
    }
  }

  if (context.relatedFiles && Array.isArray(context.relatedFiles) && context.relatedFiles.length > 0) {
    let totalLength = 0;
    const relatedFilesInfo = [];

    for (const file of context.relatedFiles) {
      if (totalLength >= MAX_RELATED_FILES_LENGTH) {
        console.log('[PromptEnhancer] Related files total length reached limit, skipping remaining files');
        break;
      }

      if (file.path) {
        let fileEntry = `- ${file.path}`;
        if (file.content && file.content.trim()) {
          const remainingLength = MAX_RELATED_FILES_LENGTH - totalLength;
          const maxLength = Math.min(MAX_SINGLE_RELATED_FILE_LENGTH, remainingLength);
          const truncatedContent = truncateText(file.content, maxLength);
          const lang = getLanguageFromPath(file.path);
          fileEntry += `\n\`\`\`${lang}\n${truncatedContent}\n\`\`\``;
          totalLength += truncatedContent.length;
        }
        relatedFilesInfo.push(fileEntry);
      }
    }

    if (relatedFilesInfo.length > 0) {
      contextParts.push(`[Related Files]\n${relatedFilesInfo.join('\n')}`);
      console.log(`[PromptEnhancer] Added ${relatedFilesInfo.length} related file(s)`);
    }
  }

  if (context.projectType) {
    contextParts.push(`[Project Type] ${context.projectType}`);
    console.log(`[PromptEnhancer] Added project type: ${context.projectType}`);
  }

  if (contextParts.length > 0) {
    fullPrompt += '\n\n---\nThe following is relevant context information, please refer to it when optimizing the prompt:\n\n'
      + contextParts.join('\n\n');
  }

  return fullPrompt;
}

/**
 * Coerce an externally-supplied prompt-enhancer config into a well-formed shape.
 * Semi schema-less passthrough (matches Java JsonObject handling).
 * @param {Record<string, any> | null | undefined} config
 * @returns {{ provider: string | null; effectiveProvider: string | null; resolutionSource: string; models: { claude: string; codex: string }; availability: { claude: boolean; codex: boolean } }}
 */
function normalizePromptEnhancerConfig(config) {
  if (!config || typeof config !== 'object') {
    return structuredClone(DEFAULT_PROMPT_ENHANCER_CONFIG);
  }

  return {
    provider: config.provider === 'claude' || config.provider === 'codex' ? config.provider : null,
    effectiveProvider: config.effectiveProvider === 'claude' || config.effectiveProvider === 'codex'
      ? config.effectiveProvider
      : null,
    resolutionSource: typeof config.resolutionSource === 'string' ? config.resolutionSource : 'auto',
    models: {
      claude: config.models?.claude || DEFAULT_PROMPT_ENHANCER_CONFIG.models.claude,
      codex: config.models?.codex || DEFAULT_PROMPT_ENHANCER_CONFIG.models.codex,
    },
    availability: {
      claude: Boolean(config.availability?.claude),
      codex: Boolean(config.availability?.codex),
    },
  };
}

/**
 * Resolve the runtime provider/model/source for the prompt enhancer.
 * @param {{ promptEnhancerConfig?: Record<string, any> | null; legacyModel?: string; actualModel?: string } | undefined} [input]
 * @returns {PromptEnhancerRuntimeConfig}
 */
export function resolvePromptEnhancerRuntimeConfig({ promptEnhancerConfig, legacyModel, actualModel } = {}) {
  if (!promptEnhancerConfig) {
    return {
      provider: 'claude',
      model: legacyModel || DEFAULT_PROMPT_ENHANCER_CONFIG.models.claude,
      resolutionSource: 'legacy',
      actualModel,
    };
  }

  const config = normalizePromptEnhancerConfig(promptEnhancerConfig);

  if (config.effectiveProvider === 'codex') {
    return {
      provider: 'codex',
      model: config.models.codex || DEFAULT_PROMPT_ENHANCER_CONFIG.models.codex,
      resolutionSource: config.resolutionSource,
      actualModel,
    };
  }

  if (config.effectiveProvider === 'claude') {
    return {
      provider: 'claude',
      model: config.models.claude || DEFAULT_PROMPT_ENHANCER_CONFIG.models.claude,
      resolutionSource: config.resolutionSource,
      actualModel,
    };
  }

  if (config.provider === 'codex') {
    throw new Error('Codex prompt enhancer is unavailable because no active Codex provider is configured.');
  }

  if (config.provider === 'claude') {
    throw new Error('Claude Code prompt enhancer is unavailable because no active Claude Code provider is configured.');
  }

  throw new Error('No available prompt enhancer provider is configured. Please configure Codex or Claude Code in Settings.');
}

/**
 * Compute the appended delta between two agent message snapshots (Codex streaming).
 * @param {string | undefined} previousText
 * @param {string | undefined} nextText
 * @returns {string}
 */
export function extractAppendedDelta(previousText, nextText) {
  const previous = typeof previousText === 'string' ? previousText : '';
  const next = typeof nextText === 'string' ? nextText : '';
  if (!next.trim()) return '';
  if (!previous) return next;
  if (next === previous) return '';
  if (!next.startsWith(previous)) return next;
  return next.slice(previous.length);
}

/**
 * Dispatch enhancement to the configured provider.
 * NOTE: Direct SDK invocation has been removed (CLI-only mode), so this always
 * throws — the CLI process handles prompt enhancement internally. The error
 * text is preserved so the Java-side failure surface stays unchanged.
 * @param {string} originalPrompt
 * @param {string} systemPrompt
 * @param {PromptEnhancerRuntimeConfig} runtimeConfig
 * @param {PromptEnhancerContext | undefined} context
 * @returns {Promise<string>}
 */
async function enhancePrompt(originalPrompt, systemPrompt, runtimeConfig, context) {
  const providerLabel = runtimeConfig.provider === 'codex' ? 'Codex SDK' : 'Claude Code SDK';
  throw new Error(
    `${providerLabel} is no longer available in CLI-only mode. ` +
    'Prompt enhancement is handled internally by the CLI process.'
  );
}

/**
 * Entry point for a single prompt-enhancement request.
 * @param {Record<string, any>} data
 * @returns {Promise<string>}
 */
export async function runPromptEnhancerRequest(data) {
  const { prompt, systemPrompt, legacyModel, context, promptEnhancerConfig, actualModel } = data;

  if (!prompt) {
    return '';
  }

  const runtimeConfig = resolvePromptEnhancerRuntimeConfig({
    promptEnhancerConfig,
    legacyModel,
    actualModel,
  });
  console.log(`[PromptEnhancer] Resolved provider: ${runtimeConfig.provider}, model: ${runtimeConfig.model}, source: ${runtimeConfig.resolutionSource}`);

  return enhancePrompt(prompt, systemPrompt, runtimeConfig, context);
}

async function main() {
  try {
    const input = await readStdin();
    const data = JSON.parse(input);

    const { prompt, context } = data;

    if (!prompt) {
      console.log('[ENHANCED]');
      process.exit(0);
    }

    if (context) {
      console.log('[PromptEnhancer] Received context info:');
      if (context.selectedCode) {
        console.log(`  - Selected code: ${context.selectedCode.length} chars`);
      }
      if (context.currentFile) {
        console.log(`  - Current file: ${context.currentFile.path}`);
      }
      if (context.cursorPosition) {
        console.log(`  - Cursor position: line ${context.cursorPosition.line}`);
      }
      if (context.relatedFiles) {
        console.log(`  - Related files: ${context.relatedFiles.length}`);
      }
    } else {
      console.log('[PromptEnhancer] No context info received');
    }

    const enhancedPrompt = await runPromptEnhancerRequest(data);
    const encodedPrompt = enhancedPrompt.replace(/\n/g, '{{NEWLINE}}');
    console.log(`[ENHANCED]${encodedPrompt}`);
    process.exit(0);
  } catch (error) {
    const msg = error instanceof Error ? error.message : String(error);
    console.error('[PromptEnhancer] Error:', msg);
    console.log(`[ENHANCED]Enhancement failed: ${msg}`);
    process.exit(1);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
