import { defineConfig, devices, type PlaywrightTestConfig } from '@playwright/test';

// End-to-end tests that drive the SERVED dashboard in a real browser against a
// REAL MockServer (the runnable netty JAR) — real REST + real WebSocket, no
// mocked fetch and no jsdom. This is the browser-level backstop the 178
// jsdom/vitest specs cannot provide.
//
// Topology (all same-origin, so no CORS and no dev-server proxy):
//   http://${HOST}:${PORT}/mockserver/dashboard/   ← the dashboard the JAR serves
//   http://${HOST}:${PORT}/mockserver/*            ← control plane (REST)
//   ws://${HOST}:${PORT}/_mockserver_ui_websocket  ← live log feed
//
// Two ways the server is provided:
//   • Local (default): Playwright boots the JAR itself via e2e/start-mockserver.mjs
//     (the `webServer` block), on 127.0.0.1:1084.
//   • CI (E2E_EXTERNAL_SERVER=1): the pipeline has already started MockServer in
//     a separate container; point at it via E2E_MS_HOST/E2E_MS_PORT and skip the
//     managed webServer.

const HOST = process.env.E2E_MS_HOST || '127.0.0.1';
const PORT = process.env.E2E_MS_PORT || '1084';
const BASE_ORIGIN = `http://${HOST}:${PORT}`;
const EXTERNAL_SERVER = process.env.E2E_EXTERNAL_SERVER === '1';

const config: PlaywrightTestConfig = {
  testDir: '.',
  testMatch: '**/*.spec.ts',
  // A single dashboard + one JVM: run serially so tests don't race on shared
  // server state (expectations, the log ring buffer).
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI
    ? [['list'], ['junit', { outputFile: 'test-reports/e2e-results.xml' }]]
    : [['list']],
  timeout: 60_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL: `${BASE_ORIGIN}/mockserver/dashboard/`,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  // A wide viewport keeps the full grouped navigation + toolbar action icons on
  // screen (below the `lg` breakpoint the dashboard collapses the nav into a
  // hamburger), so the AppBar controls the tests click are always present.
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1600, height: 1000 } },
    },
  ],
};

if (!EXTERNAL_SERVER) {
  config.webServer = {
    // cwd defaults to this config file's directory (e2e/), so the launcher is
    // referenced by its bare name; it resolves all its own paths from __dirname.
    command: 'node start-mockserver.mjs',
    // Playwright polls this URL with GET until it answers 2xx/3xx; the dashboard
    // GET is a good readiness signal for the whole server.
    url: `${BASE_ORIGIN}/mockserver/dashboard/`,
    // Generous: locally the JAR may need a Maven build on first run. In CI the
    // pipeline builds it first, so boot is just a JVM start.
    timeout: 300_000,
    reuseExistingServer: !process.env.CI,
    stdout: 'pipe',
    stderr: 'pipe',
  };
}

export default defineConfig(config);
