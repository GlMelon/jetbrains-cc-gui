// framing.js — MCP stdio 帧编解码(双模:NDJSON 默认 + LSP Content-Length 兼容)。
//
// 背景(2026-07-03 排查 opencode gateway 首请求慢 30s):
//   MCP 官方 spec 规定 stdio 传输 = NDJSON(newline-delimited JSON,消息体不得含嵌入换行),
//   无 Content-Length header。opencode.exe(Go,严格遵循 spec,二进制内 0 处 Content-Length)
//   发 NDJSON initialize;而本文件原实现只认 LSP 风格 `Content-Length: N\r\n\r\n{json}`
//   (Language Server Protocol 帧)→ opencode 的 initialize 永远进不了解析路径 → gateway
//   不回 initialize → opencode 等满 30s initialize 超时,标记 melon_gateway status=failed。
//   反之 codex.exe(Rust,二进制内 11 处 Content-Length)沿用 LSP 帧,与原实现匹配,~1s 正常。
//
//   修复策略=双模:
//     · 解析:FramedReader 同时识别 NDJSON(\n 切分)与 LSP(Content-Length header),
//       并以 lastFormat 记录最近一条消息的帧格式。
//     · 编码:encodeMessage(message, format) 按指定格式产出;format 默认 'ndjson'(spec 标准)。
//     · 自适应:writeMessage(stream, msg, format) 的 format 取值优先级 =
//       显式参数 > stream.__mcpFrameFormat > 'ndjson'。两个 stdio 转发器
//       (gateway-stdio-client.js / transport/stdio-client.js)在 reader 探测到客户端帧格式后,
//       把 lastFormat 同步到对端 output stream 的 __mcpFrameFormat,使响应帧自动跟随客户端。
//       —— opencode 收发 NDJSON,codex 收发 LSP,各得其所,互不回归。
//
// 参考:
//   - MCP spec stdio: https://modelcontextprotocol.io/specification/draft/basic/transports/stdio
//     "Messages are delimited by newlines, and MUST NOT contain embedded newlines."
//   - 镜像 bug: https://github.com/modelcontextprotocol/python-sdk/issues/2546
//     (Python SDK NDJSON,客户端发 LSP Content-Length 帧时握手失败)

import { EventEmitter } from 'node:events';

const LSP_HEADER_SEP = '\r\n\r\n';
const CONTENT_LENGTH_RE = /content-length:\s*(\d+)/i;
// buffer 头部前若干字节即足以判断是否为 LSP header(ASCII,"Content-Length:" = 15 字节)。
const LSP_PEEK = 32;
const LSP_HEAD_RE = /^\s*content-length:/i;

/**
 * 把一条 JSON-RPC 消息编码为 stdio 帧字节。
 *
 * @param {object} message JSON-RPC 消息
 * @param {'ndjson'|'lsp'} [format='ndjson'] 帧格式:
 *   - 'ndjson'(默认,MCP spec):`{json}\n`,无 header
 *   - 'lsp'(兼容历史/部分客户端):`Content-Length: N\r\n\r\n{json}`
 * @returns {Buffer}
 */
export function encodeMessage(message, format = 'ndjson') {
  if (format === 'lsp') {
    const payload = Buffer.from(JSON.stringify(message), 'utf8');
    return Buffer.concat([
      Buffer.from(`Content-Length: ${payload.length}\r\n\r\n`, 'ascii'),
      payload,
    ]);
  }
  // ndjson(MCP spec 标准)
  return Buffer.from(JSON.stringify(message) + '\n', 'utf8');
}

/**
 * 帧读取器:从流上消费字节,按双模解析出 JSON-RPC 消息并 emit 'message'。
 *
 * 解析优先级(每帧循环):
 *   1. 若 buffer 头部像 LSP header(以 "Content-Length:" 开头,忽略前导空白):
 *      - 已含 `\r\n\r\n` 且 body 完整 → 按 Content-Length 切帧,emit,lastFormat='lsp'
 *      - header 未结束或 body 不完整 → 返回等待更多数据(绝不落 NDJSON 路径,避免 \r\n 被误切)
 *   2. 否则按 NDJSON 处理:以 `\n` 切分;空行跳过;有效行 JSON.parse 后 emit,lastFormat='ndjson'
 *
 * NDJSON 消息体(spec 规定不得含嵌入换行)不会出现裸 `\r\n\r\n`,故 LSP 检测不会误命中 NDJSON 流。
 */
export class FramedReader extends EventEmitter {
  constructor(stream) {
    super();
    this.buffer = Buffer.alloc(0);
    this.lastFormat = null;
    if (stream) {
      stream.on('data', (chunk) => this.push(chunk));
      stream.on('end', () => this.emit('end'));
      stream.on('error', (error) => this.emit('error', error));
    }
  }

  push(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    while (this.consumeOne()) {
      // 循环消费所有已完整的消息
    }
  }

  /**
   * 尝试从 buffer 头部消费一条消息(或跳过一个空行)。
   * @returns {boolean} true=消费/跳过了内容,应继续 loop;false=数据不完整,等下一 chunk。
   */
  consumeOne() {
    if (this.buffer.length === 0) return false;

    // LSP 路径:仅在 buffer 头部像 Content-Length header 时进入。
    if (LSP_HEAD_RE.test(this.buffer.subarray(0, LSP_PEEK).toString('latin1'))) {
      const headerEnd = this.buffer.indexOf(LSP_HEADER_SEP);
      if (headerEnd < 0) {
        // header 还没收完:等更多数据。绝不在此处按 \n 误切 header 里的 \r\n。
        return false;
      }
      const header = this.buffer.subarray(0, headerEnd).toString('ascii');
      const match = CONTENT_LENGTH_RE.exec(header);
      if (match) {
        const length = Number.parseInt(match[1], 10);
        const bodyStart = headerEnd + LSP_HEADER_SEP.length;
        const bodyEnd = bodyStart + length;
        if (this.buffer.length < bodyEnd) {
          return false; // body 不完整
        }
        const payload = this.buffer.subarray(bodyStart, bodyEnd).toString('utf8');
        this.buffer = this.buffer.subarray(bodyEnd);
        this.lastFormat = 'lsp';
        try {
          this.emit('message', JSON.parse(payload));
        } catch (error) {
          this.emit('error', error);
        }
        return true;
      }
      // 头部像 LSP header 但解析失败:落到 NDJSON 路径兜底(罕见,防御性)。
    }

    // NDJSON 路径:按 \n 切分。
    const nl = this.buffer.indexOf('\n');
    if (nl < 0) {
      return false; // 无完整行,等更多数据
    }
    const payload = this.buffer.subarray(0, nl).toString('utf8');
    this.buffer = this.buffer.subarray(nl + 1);
    const trimmed = payload.replace(/^\s+|\s+$/g, '');
    if (!trimmed) {
      return true; // 空行跳过,继续 loop
    }
    this.lastFormat = 'ndjson';
    try {
      this.emit('message', JSON.parse(trimmed));
    } catch (error) {
      this.emit('error', error);
    }
    return true;
  }
}

/**
 * 把消息按帧写到流。format 优先级:显式参数 > stream.__mcpFrameFormat > 'ndjson'。
 * stdio 转发器把 reader 探测到的客户端帧格式写入对端 stream 的 __mcpFrameFormat,
 * 即可实现"响应帧跟随客户端探测格式"的自适应。
 */
export function writeMessage(stream, message, format) {
  const fmt = format || (stream && stream.__mcpFrameFormat) || 'ndjson';
  stream.write(encodeMessage(message, fmt));
}
