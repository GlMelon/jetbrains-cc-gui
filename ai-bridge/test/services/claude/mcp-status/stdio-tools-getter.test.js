import assert from 'node:assert/strict';
import { realpathSync } from 'node:fs';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { getStdioServerTools } from '../../../../services/claude/mcp-status/stdio-tools-getter.js';
import { MAX_MESSAGE_BYTES } from '../../../../mcp-gateway/framing.js';

test('starts the MCP server in its configured working directory', async () => {
  const workingDirectory = await mkdtemp(path.join(os.tmpdir(), 'ccg-mcp-cwd-'));
  const serverScript = path.join(workingDirectory, 'server.mjs');
  await writeFile(serverScript, `
import readline from 'node:readline';

const lines = readline.createInterface({ input: process.stdin });
lines.on('line', (line) => {
  const request = JSON.parse(line);
  if (request.id === 1) {
    console.log(JSON.stringify({
      jsonrpc: '2.0',
      id: 1,
      result: {
        protocolVersion: '2024-11-05',
        capabilities: {},
        serverInfo: { name: 'cwd-test', version: '1.0.0' }
      }
    }));
  } else if (request.id === 2) {
    console.log(JSON.stringify({
      jsonrpc: '2.0',
      id: 2,
      result: {
        tools: [{
          name: 'working-directory',
          description: process.cwd(),
          inputSchema: { type: 'object' }
        }]
      }
    }));
  }
});
`, 'utf8');

  try {
    const result = await getStdioServerTools('cwd-test', {
      command: process.execPath,
      args: [serverScript],
      cwd: workingDirectory
    });

    assert.equal(result.error, null);
    assert.equal(result.tools.length, 1);
    // Compare real paths: process.cwd() resolves symlinks, while os.tmpdir()
    // does not (e.g. macOS /var -> /private/var).
    assert.equal(realpathSync(result.tools[0].description), realpathSync(workingDirectory));
  } finally {
    await rm(workingDirectory, {
      recursive: true,
      force: true,
      maxRetries: 5,
      retryDelay: 100
    });
  }
});

test('fails instead of growing the stdout buffer beyond MAX_MESSAGE_BYTES', async () => {
  const workingDirectory = await mkdtemp(path.join(os.tmpdir(), 'ccg-mcp-cap-'));
  const serverScript = path.join(workingDirectory, 'server.mjs');
  // Server floods stdout with more than MAX_MESSAGE_BYTES and never emits a
  // newline: the client must terminate with an error, not accumulate unbounded.
  await writeFile(serverScript, `
const flood = 'a'.repeat(${MAX_MESSAGE_BYTES + 1024});
process.stdout.write(flood);
setInterval(() => {}, 1000);
`, 'utf8');

  try {
    const result = await getStdioServerTools('flood-test', {
      command: process.execPath,
      args: [serverScript],
    });

    assert.match(result.error ?? '', /exceeds/);
    assert.deepEqual(result.tools, []);
  } finally {
    await rm(workingDirectory, {
      recursive: true,
      force: true,
      maxRetries: 5,
      retryDelay: 100
    });
  }
});
