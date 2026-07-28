// @ts-check
/**
 * HTTP 鉴权与 token 脱敏工具。
 */

/**
 * 从请求头 `Authorization: Bearer <token>` 中提取 bearer token。
 *
 * @param {import('node:http').IncomingMessage} req HTTP 请求对象
 * @returns {string} bearer token(无 Authorization 头或格式不符时返回空串)
 */
export function tokenFromRequest(req) {
  const header = req.headers.authorization ?? '';
  return header.startsWith('Bearer ') ? header.slice('Bearer '.length) : '';
}

/**
 * 校验请求是否携带期望的 bearer token。
 *
 * @param {import('node:http').IncomingMessage} req HTTP 请求对象
 * @param {string} token 期望的 token
 * @returns {boolean} 匹配返回 true
 */
export function requireToken(req, token) {
  return tokenFromRequest(req) === token;
}

/**
 * 把文本中出现的 token 替换为 `[redacted-token]`,避免日志/错误泄漏。
 *
 * @param {string} text 原始文本
 * @param {string} token 需脱敏的 token(空串则原样返回)
 * @returns {string} 脱敏后的文本
 */
export function redactToken(text, token) {
  if (!token) return text;
  return String(text).split(token).join('[redacted-token]');
}
