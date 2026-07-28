// @ts-check
/**
 * Stdin reader utility module (unified version).
 * Supports both Claude and Codex SDKs.
 */

/**
 * Read JSON data from stdin.
 * @param {string} provider - 'claude', 'codex', or 'opencode'
 * @returns {Promise<Object|null>} The parsed JSON object, or null
 */
export async function readStdinData(provider = 'claude') {
  // Check whether stdin input is enabled
  // §15.7 B2:每个 provider 独立 stdin 开关,opencode 用 OPENCODE_USE_STDIN(原先落入 else
  // 查 CLAUDE_USE_STDIN 致 stdin 永不读取 → baseUrl 等字段丢失)。
  const envKey = provider === 'codex' ? 'CODEX_USE_STDIN'
    : provider === 'opencode' ? 'OPENCODE_USE_STDIN'
    : 'CLAUDE_USE_STDIN';
  if (process.env[envKey] !== 'true') {
    return null;
  }

  return new Promise((resolve) => {
    let data = '';
    const stdin = process.stdin;

    stdin.setEncoding('utf8');

    // Cleanup: remove all listeners and stop reading
    const cleanup = () => {
      stdin.removeListener('readable', onReadable);
      stdin.removeListener('end', onEnd);
      stdin.removeListener('error', onError);
      stdin.pause();
    };

    // Set a timeout to avoid waiting indefinitely
    const timeout = setTimeout(() => {
      cleanup();
      resolve(null);
    }, 5000);

    const onReadable = () => {
      let chunk;
      while ((chunk = stdin.read()) !== null) {
        data += chunk;
      }
    };

    const onEnd = () => {
      clearTimeout(timeout);
      cleanup();
      if (data.trim()) {
        try {
          const parsed = JSON.parse(data.trim());
          resolve(parsed);
        } catch (e) {
          console.error('[STDIN_PARSE_ERROR]', e instanceof Error ? e.message : e);
          resolve(null);
        }
      } else {
        resolve(null);
      }
    };

    const onError = (/** @type {Error} */ err) => {
      clearTimeout(timeout);
      cleanup();
      console.error('[STDIN_ERROR]', err.message);
      resolve(null);
    };

    stdin.on('readable', onReadable);
    stdin.on('end', onEnd);
    stdin.on('error', onError);
  });
}
