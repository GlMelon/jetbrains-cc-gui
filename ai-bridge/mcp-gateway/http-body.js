// @ts-check
/**
 * HTTP 请求体读取工具:供控制面(ipc-server)与 MCP 数据面(streamable-http)共用,
 * 避免两模块互相 import 形成循环依赖。
 */

/** HTTP 请求体字节上限,防超大请求体撑爆内存(与 FramedReader MAX_MESSAGE_BYTES 对称)。 */
export const MAX_REQUEST_BYTES = 16 * 1024 * 1024;

/**
 * 读取并解析 JSON 请求体;超过 {@link MAX_REQUEST_BYTES} 拒绝并销毁请求流。
 *
 * @param {import('node:http').IncomingMessage} req
 * @returns {Promise<any>}
 */
export function readJson(req) {
  return new Promise((resolve, reject) => {
    /** @type {Buffer[]} */
    const chunks = [];
    let total = 0;
    let aborted = false;
    req.on('data', (/** @type {Buffer} */ chunk) => {
      if (aborted) return;
      total += chunk.length;
      if (total > MAX_REQUEST_BYTES) {
        aborted = true;
        reject(new Error(`Request body exceeds ${MAX_REQUEST_BYTES} bytes`));
        try { req.destroy(); } catch { /* best effort */ }
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (aborted) return;
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'));
      } catch (error) {
        reject(error);
      }
    });
    req.on('error', reject);
  });
}
