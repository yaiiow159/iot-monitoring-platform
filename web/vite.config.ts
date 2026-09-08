import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 開發時走 proxy 而不是直接打 8080，是為了讓前端在 mock 與真實後端之間切換時
// 不必處理 CORS，也讓部署時前後端可以掛在同一個網域下。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      // 8080 常被本機其他服務佔用，API 固定在 8090（與 application.yml、contracts.md 一致）
      '/api': { target: 'http://localhost:8090', changeOrigin: true },
      '/ws': { target: 'ws://localhost:8090', ws: true },
    },
  },
  build: {
    // uPlot 與 React 分開切塊，圖表函式庫不會因為頁面程式碼改動而失效快取。
    rollupOptions: {
      output: {
        manualChunks: {
          uplot: ['uplot'],
          react: ['react', 'react-dom', 'react-router-dom'],
        },
      },
    },
  },
});
