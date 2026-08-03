// @ts-check
/**
 * Gateway 状态文件读写工具(原子性靠 fs.*Sync + mode 0o600 保守保证)。
 *
 * 注:本模块 import 了 `path`(node:path);下方各函数的形参亦命名为 `path`,
 * 在函数作用域内形参遮蔽模块 import —— 函数体未直接使用 path 模块,
 * 真正调用 path.dirname 的 pathModuleDirname 内部使用的是模块级 import。
 */
import fs from 'node:fs';
import path from 'node:path';

/**
 * 将 state 以 JSON 写入指定文件;先确保父目录存在,并以 mode 0o600 限制权限。
 *
 * @param {string} path 目标文件绝对路径
 * @param {unknown} state 任意可 JSON 序列化的状态
 * @returns {void}
 */
export function writeStateFile(path, state) {
  fs.mkdirSync(pathModuleDirname(path), { recursive: true });
  const data = JSON.stringify(state, null, 2);
  // 项9:writeFileSync 直接写目标文件,写到一半被并发进程读到部分 JSON 致 parse 失败。
  // 改"写同目录临时文件 + rename":rename 在同文件系统内原子,读者要么见旧版要么见新版,不读半截。
  const tmp = `${path}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, data, { encoding: 'utf8', mode: 0o600 });
  fs.renameSync(tmp, path);
}

/**
 * 读取并解析状态文件。
 *
 * @param {string} path 目标文件绝对路径
 * @returns {any} 解析后的状态(JSON.parse 结果,结构由调用方解释)
 */
export function readStateFile(path) {
  return JSON.parse(fs.readFileSync(path, 'utf8'));
}

/**
 * 尽力删除状态文件,忽略不存在的错误。
 *
 * @param {string} path 目标文件绝对路径
 * @returns {void}
 */
export function removeStateFile(path) {
  try {
    fs.unlinkSync(path);
  } catch {
    // best effort
  }
}

/**
 * @param {string} filePath
 * @returns {string}
 */
function pathModuleDirname(filePath) {
  return path.dirname(filePath);
}
