import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    // Built straight into the server package so it can be embedded and
    // served by the blueprint (routes/webapp.py) with no separate copy step.
    outDir: '../server/src/shoppinglist_server/web_dist',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      // In `npm run dev`, the SPA is served by Vite on its own port, so API
      // calls need proxying to a real Flask server. In production the built
      // app is served BY that same Flask server (same-origin), so this proxy
      // is dev-only and irrelevant to the production build.
      '/api': {
        target: process.env.VITE_API_PROXY_TARGET || 'http://localhost:5000',
        changeOrigin: true,
      },
      '/invite': {
        target: process.env.VITE_API_PROXY_TARGET || 'http://localhost:5000',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
  },
})
