// @ts-check
/** @type {number} */
const MAX_TOOL_RESULT_CONTENT_CHARS = 20000;
/** @type {string[]} */
const ERROR_CONTENT_PREFIXES = ['API Error', 'API error', 'Error:', 'Error '];

export { MAX_TOOL_RESULT_CONTENT_CHARS, ERROR_CONTENT_PREFIXES };

/**
 * 截断超长字符串,尾部追加截断标记。
 *
 * @param {string | null | undefined} str 待截断字符串
 * @param {number} [maxLen=1000] 最大保留长度
 * @returns {string | null | undefined}
 */
export function truncateString(str, maxLen = 1000) {
  if (!str || str.length <= maxLen) return str;
  return str.substring(0, maxLen) + `... [truncated, total ${str.length} chars]`;
}

// Patterns covering common credential shapes that can leak through CLI
// stderr / stack traces. Each entry pairs a regex with its replacement string.
// Patterns that need to preserve a leading label (Bearer, Authorization:, etc.)
// use a $1 capture-group reference; bare token patterns substitute the entire
// match. NOTE: ordering matters — longest-prefix variants (sk-ant-, sk-proj-)
// come before the generic sk-/pk-/rk- catch-all so the label is preserved.

/**
 * @typedef {{ re: RegExp; replacement: string }} SecretPattern
 */

/** @satisfies {SecretPattern[]} */
const SECRET_PATTERNS = [
  // Anthropic / OpenAI / similar
  { re: /\bsk-ant-[A-Za-z0-9_-]{16,}/g, replacement: 'sk-ant-***REDACTED***' },
  { re: /\bsk-proj-[A-Za-z0-9_-]{16,}/g, replacement: 'sk-proj-***REDACTED***' },
  { re: /\b(?:sk|pk|rk)-[A-Za-z0-9_-]{16,}/g, replacement: '***REDACTED***' },
  // GitHub tokens
  { re: /\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}/g, replacement: '***REDACTED***' },
  { re: /\bgithub_pat_[A-Za-z0-9_]{20,}/g, replacement: 'github_pat_***REDACTED***' },
  // Authorization / Bearer / x-api-key / api_key headers (preserve the label)
  { re: /(Bearer\s+)[A-Za-z0-9._\-+/=]{16,}/gi, replacement: '$1***REDACTED***' },
  { re: /(Authorization\s*[:=]\s*)[^\s"'\n,;]{16,}/gi, replacement: '$1***REDACTED***' },
  { re: /(x-api-key\s*[:=]\s*)[^\s"'\n,;]{16,}/gi, replacement: '$1***REDACTED***' },
  { re: /(api[_-]?key\s*[:=]\s*["']?)[^"'\s\n,;]{16,}/gi, replacement: '$1***REDACTED***' },
];

/**
 * 对任意值做凭证脱敏(转字符串后按 SECRET_PATTERNS 依次替换)。null/undefined 原样返回。
 *
 * @param {unknown} value
 * @returns {unknown} 与输入同型(string)或原 null/undefined
 */
export function redactSecrets(value) {
  if (value == null) return value;
  let text = typeof value === 'string' ? value : String(value);
  for (const { re, replacement } of SECRET_PATTERNS) {
    text = text.replace(re, replacement);
  }
  return text;
}

/**
 * 仅对错误类内容(以 ERROR_CONTENT_PREFIXES 开头)做截断;其余原样返回。
 *
 * @param {string | null | undefined} content
 * @param {number} [maxLen=1000]
 * @returns {string | null | undefined}
 */
export function truncateErrorContent(content, maxLen = 1000) {
  if (!content || content.length <= maxLen) return content;
  const isError = ERROR_CONTENT_PREFIXES.some(prefix => content.startsWith(prefix));
  if (!isError) return content;
  return content.substring(0, maxLen) + `... [truncated, total ${content.length} chars]`;
}

/**
 * 工具结果块(truncate tool result block)。支持 string content 与 array content
 * (Anthropic 消息的工具结果可以是文本数组,逐项截断 text 子块)。
 *
 * @param {any} block 工具结果块
 * @returns {any} 截断后的块(原块未超长则原样返回)
 */
export function truncateToolResultBlock(block) {
  if (!block || !block.content) return block;
  const content = block.content;
  if (typeof content === 'string' && content.length > MAX_TOOL_RESULT_CONTENT_CHARS) {
    const head = Math.floor(MAX_TOOL_RESULT_CONTENT_CHARS * 0.65);
    const tail = MAX_TOOL_RESULT_CONTENT_CHARS - head;
    return {
      ...block,
      content: content.substring(0, head) +
        `\n...\n(truncated, original length: ${content.length} chars)\n...\n` +
        content.substring(content.length - tail)
    };
  }
  if (Array.isArray(content)) {
    let changed = false;
    const truncated = content.map(item => {
      if (item && item.type === 'text' && typeof item.text === 'string' && item.text.length > MAX_TOOL_RESULT_CONTENT_CHARS) {
        changed = true;
        const head = Math.floor(MAX_TOOL_RESULT_CONTENT_CHARS * 0.65);
        const tail = MAX_TOOL_RESULT_CONTENT_CHARS - head;
        return {
          ...item,
          text: item.text.substring(0, head) +
            `\n...\n(truncated, original length: ${item.text.length} chars)\n...\n` +
            item.text.substring(item.text.length - tail)
        };
      }
      return item;
    });
    return changed ? { ...block, content: truncated } : block;
  }
  return block;
}
