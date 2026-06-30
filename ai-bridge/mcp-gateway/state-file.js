import fs from 'node:fs';
import path from 'node:path';

export function writeStateFile(path, state) {
  fs.mkdirSync(pathModuleDirname(path), { recursive: true });
  fs.writeFileSync(path, JSON.stringify(state, null, 2), { encoding: 'utf8', mode: 0o600 });
}

export function readStateFile(path) {
  return JSON.parse(fs.readFileSync(path, 'utf8'));
}

export function removeStateFile(path) {
  try {
    fs.unlinkSync(path);
  } catch {
    // best effort
  }
}

function pathModuleDirname(filePath) {
  return path.dirname(filePath);
}
