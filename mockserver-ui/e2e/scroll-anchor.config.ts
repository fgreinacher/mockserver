import { defineConfig, devices } from '@playwright/test';

// Playwright config for the Panel scroll-anchoring regression test. It drives a
// self-contained Vite harness (e2e/anchor-harness) that renders the REAL Panel +
// ProgressiveList — no MockServer, because the bug is a pure client-side
// virtualization/scroll interaction that jsdom cannot reproduce (no layout
// engine). Kept separate from playwright.config.ts (which boots the JAR) and
// matched on `*.pw.ts` so the two suites never pick up each other's specs.
export default defineConfig({
  testDir: '.',
  testMatch: '**/*.pw.ts',
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: process.env.CI
    ? [['list'], ['junit', { outputFile: 'test-reports/e2e-anchor-results.xml' }]]
    : [['list']],
  timeout: 60_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL: 'http://localhost:3100/',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      // Playwright's own bundled Chromium by default, exactly as the JAR-backed
      // suite uses it, so CI and a normal local run drive the same browser build.
      // PW_CHANNEL=chrome selects an installed Google Chrome instead — the escape
      // hatch for a machine behind the corporate TLS proxy, where Playwright
      // cannot download its bundled Chromium at all.
      use: {
        ...devices['Desktop Chrome'],
        ...(process.env.PW_CHANNEL ? { channel: process.env.PW_CHANNEL } : {}),
        viewport: { width: 900, height: 800 },
      },
    },
  ],
  webServer: {
    // cwd defaults to this config file's directory (e2e/), so the harness config
    // is referenced relative to it.
    command: 'npx vite --config anchor-harness/vite.config.ts',
    url: 'http://localhost:3100/',
    timeout: 120_000,
    reuseExistingServer: !process.env.CI,
    stdout: 'pipe',
    stderr: 'pipe',
  },
});
