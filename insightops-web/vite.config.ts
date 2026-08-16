import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 15173,
    proxy: {
      '/api': 'http://127.0.0.1:18080',
      '/actuator': 'http://127.0.0.1:18080',
    },
  },
})
