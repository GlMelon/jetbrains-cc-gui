// @ts-check
/**
 * ESLint flat config（T2：前端代码风格分阶段接入）。
 *
 * 接入策略（来自 docs/comprehensive-optimization-directions.md T2）：
 * - 不做一次全仓格式化；历史代码的既有偏离由「增量检查」逐步收紧——pre-commit/CI 只检查
 *   本次改动的文件，未被触碰的历史文件不强制格式化，避免一次性巨量 diff。
 * - 规则从宽松起步：仅保留高价值的 bug 防护（react-hooks/rules-of-hooks 等）为 error；
 *   项目既有约定（半 schema-less 透传层与 provider adapter 大量使用 any）不作为阻塞。
 * - 格式交给 Prettier（见 .prettierrc.json），此处通过 eslint-config-prettier 关闭所有
 *   与 Prettier 冲突的格式化规则。
 *
 * 检查范围：src/** 与 scripts/**。生成产物（src/generated、src/version）与 dist/coverage 在 ignores 中。
 */
import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import react from 'eslint-plugin-react';
import reactHooks from 'eslint-plugin-react-hooks';
import prettierConfig from 'eslint-config-prettier';

export default tseslint.config(
  {
    ignores: [
      'dist/**',
      'node_modules/**',
      'coverage/**',
      'src/generated/**',
      'src/version/**',
      'playwright-report/**',
      'test-results/**',
    ],
  },

  js.configs.recommended,
  ...tseslint.configs.recommended,

  {
    files: ['src/**/*.{ts,tsx}', 'scripts/**/*.{ts,mjs,js}'],
    plugins: {
      react,
      'react-hooks': reactHooks,
    },
    rules: {
      // React Hooks 正确性：最高价值的 bug 防护，保持 error。
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',

      // dangerouslySetInnerHTML 使用提示（XSS 风险信号）；历史代码中已有的
      // eslint-disable 注释因 plugin 注册而重新生效，无需改动源文件。
      'react/no-danger': 'warn',

      // TS 项目由类型系统接管「未定义/未使用」语义，关闭 ESLint 核心 no-undef/no-unused-vars，
      // 改用 @typescript-eslint 版本（更准确，且可配置下划线豁免）。
      'no-undef': 'off',
      'no-unused-vars': 'off',
      '@typescript-eslint/no-unused-vars': [
        'warn',
        {
          argsIgnorePattern: '^_',
          varsIgnorePattern: '^_',
          caughtErrorsIgnorePattern: '^_',
        },
      ],

      // 项目既有约定：provider adapter 与半 schema-less 透传层大量使用 any，历史既定，不作为 lint 阻塞。
      '@typescript-eslint/no-explicit-any': 'off',

      // 网络/解析降级路径中空 catch 常见，允许。
      'no-empty': ['warn', { allowEmptyCatch: true }],
    },
  },

  // 必须放最后：关闭所有与 Prettier 冲突的格式化规则。
  prettierConfig,
);
