import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  cacheDir: '../../.vite-cache',
  plugins: [vue()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': 'http://127.0.0.1:5050'
    }
  },
  preview: {
    port: 5173,
    proxy: {
      '/api': 'http://127.0.0.1:5050'
    }
  }
})
