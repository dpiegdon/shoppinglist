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
    // This sandbox is CPU/memory-constrained: running multiple jsdom worker
    // files fully in parallel causes real async work (mount effects, mocked
    // promises) to occasionally miss the default test timeout under
    // contention - not a logic bug, confirmed by every affected test passing
    // reliably in isolation. Single-threaded file execution trades wall-clock
    // speed for determinism, which matters more for a suite this size.
    fileParallelism: false,
  },
})
