import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      // ws: true is required, otherwise the proxy doesn't upgrade to a WebSocket
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
})
