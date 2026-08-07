import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react-swc';
import tailwindcss from '@tailwindcss/vite';
import { visualizer } from 'rollup-plugin-visualizer';

// B2: bundle analyzer baseline — 仅在 ANALYZE=true 环境变量下启用
// 使用: ANALYZE=true npx vite build
// 产出 webview/dist/stats.html 分析报告
const useAnalyzer = process.env.ANALYZE === 'true';

export default defineConfig({
  // The production build is served from a stable JCEF custom origin. Relative
  // asset URLs keep module imports and lazy chunks on that same origin.
  base: './',
  plugins: [
    react(),
    tailwindcss(),
    ...(useAnalyzer
      ? [
          visualizer({
            filename: 'dist/stats.html',
            open: false,
            gzipSize: true,
            brotliSize: true,
          }),
        ]
      : []),
  ],
  build: {
    minify: 'esbuild',
    esbuild: {
      drop: ['console', 'debugger'],
    },
    assetsInlineLimit: 1024 * 1024,
    cssCodeSplit: false,
    sourcemap: false,
    rollupOptions: {
      output: {
        manualChunks: undefined,
      },
    },
  },
});
