import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'happy-dom',
    setupFiles: ['./vitest-setup.ts'],
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
    exclude: ['../.worktrees/**'],
    globals: true,
    coverage: {
      // 防倒退 gate 由 scripts/check-coverage.mjs 守门(职责分离,同 ai-bridge);
      // 这里只负责采集,不设 threshold。
      provider: 'v8',
      reporter: ['text-summary', 'json'],
      reportsDirectory: './coverage',
      // all:false 只计被测试运行时导入的文件(同 ai-bridge .c8rc all:false):
      // 避免 src 下无测试的组件/工具被算成 0% 拉低到噪声区,信号更清晰。
      all: false,
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'src/**/*.test.{ts,tsx}',
        'src/generated/**',
        'src/version/**',
        'src/**/*.d.ts',
      ],
    },
  },
});
