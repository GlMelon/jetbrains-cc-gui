import { EventEmitter } from 'node:events';

export function encodeMessage(message) {
  const payload = Buffer.from(JSON.stringify(message), 'utf8');
  return Buffer.concat([
    Buffer.from(`Content-Length: ${payload.length}\r\n\r\n`, 'ascii'),
    payload,
  ]);
}

export class FramedReader extends EventEmitter {
  constructor(stream) {
    super();
    this.buffer = Buffer.alloc(0);
    stream.on('data', (chunk) => this.push(chunk));
    stream.on('end', () => this.emit('end'));
    stream.on('error', (error) => this.emit('error', error));
  }

  push(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    while (true) {
      const headerEnd = this.buffer.indexOf('\r\n\r\n');
      if (headerEnd < 0) return;
      const header = this.buffer.subarray(0, headerEnd).toString('ascii');
      const match = /content-length:\s*(\d+)/i.exec(header);
      if (!match) {
        this.emit('error', new Error('Missing Content-Length header'));
        return;
      }
      const length = Number.parseInt(match[1], 10);
      const bodyStart = headerEnd + 4;
      const bodyEnd = bodyStart + length;
      if (this.buffer.length < bodyEnd) return;
      const payload = this.buffer.subarray(bodyStart, bodyEnd).toString('utf8');
      this.buffer = this.buffer.subarray(bodyEnd);
      try {
        this.emit('message', JSON.parse(payload));
      } catch (error) {
        this.emit('error', error);
      }
    }
  }
}

export function writeMessage(stream, message) {
  stream.write(encodeMessage(message));
}
