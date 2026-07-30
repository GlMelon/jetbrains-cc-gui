import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react-swc';
import tailwindcss from '@tailwindcss/vite';
import { viteSingleFile } from 'vite-plugin-singlefile';
import { visualizer } from 'rollup-plugin-visualizer';

// B2: bundle analyzer baseline — 仅在 ANALYZE=true 环境变量下启用
// 使用: ANALYZE=true npx vite build
// 产出 webview/dist/stats.html 分析报告
const useAnalyzer = process.env.ANALYZE === 'true';

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    viteSingleFile(),
    ...(useAnalyzer ? [visualizer({
      filename: 'dist/stats.html',
      open: false,
      gzipSize: true,
      brotliSize: true,
    })] : []),
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
  server: {
    proxy: {
      // Dev-mode fallback for the vendored TokenTracker dashboard: when the
      // webview runs in a plain browser (no JCEF bridge), dashboard traffic
      // goes to a locally running `tokentracker serve` instance instead.
      '/tt-dev': {
        // 端口变更时同步更新 useTokenTrackerServer.ts 的 TT_DEV_PREVIEW_PORT。
        target: 'http://127.0.0.1:7680',
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/tt-dev/, ''),
      },
    },
  },
});

