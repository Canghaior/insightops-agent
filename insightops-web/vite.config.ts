import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

const apiTarget = process.env.INSIGHTOPS_DEV_SERVER_URL || 'http://127.0.0.1:18080'

export default defineConfig({
  plugins: [vue()],
  publicDir: fileURLToPath(new URL('../infra/caddy/public/', import.meta.url)),
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 15173,
    proxy: {
      '/api': apiTarget,
      '/actuator': apiTarget,
    },
  },
})
