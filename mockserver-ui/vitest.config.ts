import { defineConfig, configDefaults } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  // Mirror the production `__APP_VERSION__` define so analytics code under test
  // compiles and reports a stable version string.
  define: {
    __APP_VERSION__: JSON.stringify('test'),
  },
  test: {
    environment: 'jsdom',
    globals: true,
    // The Playwright end-to-end specs under e2e/ are *.spec.ts too, but they run
    // in a real browser via `npm run test:e2e`, not under jsdom/vitest. Exclude
    // them so vitest's default glob does not try to run them as unit tests.
    exclude: [...configDefaults.exclude, 'e2e/**'],
    // @mui/material's ESM build (Transition.mjs) uses an extensionless directory import of
    // `react-transition-group/TransitionGroupContext`, which Node's strict ESM resolver (used for
    // externalised deps) rejects. Inline @mui so Vite transforms it and resolves the import.
    server: {
      deps: {
        inline: [/@mui\/material/],
      },
    },
    // Heavier component tests (render + multiple userEvent interactions + React
    // re-renders) can exceed the 5s default on slower/loaded CI agents, even with
    // userEvent delay:null. Use a generous global timeout so CI-load latency does
    // not cause spurious "Test timed out in 5000ms" failures.
    testTimeout: 20000,
    hookTimeout: 20000,
    setupFiles: ['./src/test-setup.ts'],
    css: true,
    reporters: ['default', 'junit'],
    outputFile: {
      junit: 'test-reports/junit.xml',
    },
    coverage: {
      thresholds: {
        statements: 64,
        branches: 55,
        functions: 54,
        lines: 67,
      },
    },
  },
});
