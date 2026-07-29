// @ts-check
/**
 * ESLint flat config for ai-bridge (T4：ai-bridge TypeScript 化 Phase 1)。
 *
 * 接入策略（来自 docs/comprehensive-optimization-directions.md T4）：
 * - Node.js ESM 项目，使用 @eslint/js 推荐规则 + eslint-config-prettier 关闭格式冲突。
 * - 规则从宽松起步：仅保留高价值的 bug 防护（no-undef 等）为 error；
 *   项目既有约定（Node.js 服务端 console.log 正常，空 catch 常见）不作为阻塞。
 * - 格式交给 Prettier（见 .prettierrc.json）。
 * - 不执行全仓格式化，只检查增量文件。
 * - .ts 文件使用 typescript-eslint 解析，支持 TypeScript 语法。
 */
import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import prettierConfig from 'eslint-config-prettier';

const sharedRules = {
  // 未使用变量设为 warn，下划线前缀豁免
  'no-unused-vars': 'off',
  '@typescript-eslint/no-unused-vars': [
    'warn',
    {
      argsIgnorePattern: '^_',
      varsIgnorePattern: '^_',
      caughtErrorsIgnorePattern: '^_',
    },
  ],

  // 网络/解析降级路径中空 catch 常见，允许
  'no-empty': ['warn', { allowEmptyCatch: true }],

  // Node.js 服务端日志正常
  'no-console': 'off',

  // 允许 throw 非 Error 值（项目既有模式）
  'no-throw-literal': 'off',

  // 预存问题：值赋值后未使用（历史代码中较多，设为 warn 逐步清理）
  'no-useless-assignment': 'warn',

  // 预存问题：throw e 时丢失 cause（历史代码中较多，设为 warn 逐步清理）
  'preserve-caught-error': 'warn',

  // 允许显式 any
  '@typescript-eslint/no-explicit-any': 'off',

  // .cjs 文件使用 require() 是正常的
  '@typescript-eslint/no-require-imports': 'off',
};

export default tseslint.config(
  {
    ignores: ['node_modules/**', 'coverage/**'],
  },

  js.configs.recommended,
  ...tseslint.configs.recommended,

  {
    files: ['**/*.js', '**/*.mjs', '**/*.cjs', '**/*.ts'],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
    rules: {
      ...sharedRules,
      // JS 项目保留 no-undef
      'no-undef': 'error',
    },
  },

  // 必须放最后：关闭所有与 Prettier 冲突的格式化规则
  prettierConfig,
);
