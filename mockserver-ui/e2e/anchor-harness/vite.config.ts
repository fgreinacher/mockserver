import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Standalone Vite server for the scroll-anchoring Playwright harness. It is
// isolated from the app's own vite.config.ts (no `/mockserver/dashboard/` base
// and no MockServer proxy) so the harness is served from `/` with nothing else
// in the way. `__APP_VERSION__` is defined because the imported dashboard source
// (via the store's analytics module) references it as a build-time constant.
export default defineConfig({
  // import.meta.dirname, not __dirname: Vite's native config loader does not
  // provide the CJS globals and warns that it will stop working.
  root: import.meta.dirname,
  base: '/',
  plugins: [react()],
  define: {
    __APP_VERSION__: JSON.stringify('anchor-harness'),
  },
  server: {
    port: 3100,
    strictPort: true,
  },
});
