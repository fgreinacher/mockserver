// Boot a REAL MockServer (the runnable netty "no-dependencies" JAR) for the
// Playwright end-to-end suite. The JAR serves the freshly-built dashboard from
// `/mockserver/dashboard/` on the SAME origin as its control plane and the
// `/_mockserver_ui_websocket` live feed, so the e2e tests drive the actual
// browser against a real server over real REST + WebSocket — no mocked fetch,
// no jsdom.
//
// This is invoked by playwright.config.ts as its `webServer.command`. Playwright
// waits for `webServer.url` (a GET of the dashboard) to answer before running
// the tests, and sends SIGTERM to this process (and thus the JVM) on teardown.
//
// The JAR is located newest-first under mockserver-netty-no-dependencies/target.
// If none exists it is built with Maven (the `build-ui` profile bundles the
// current UI source into the JAR). Building can take a few minutes, so the
// Playwright `webServer.timeout` is set generously; in CI the JAR is built by
// the pipeline step BEFORE Playwright runs, so this path only locates + execs.
//
// Env:
//   E2E_MS_PORT   port MockServer listens on (default 1084 — deliberately not
//                 the conventional 1080, so the suite never silently reuses a
//                 hand-started demo server running a stale dashboard build).
//   E2E_MS_JAR    explicit path to a runnable JAR (skips discovery/build).

import { spawn, spawnSync } from 'node:child_process';
import { readdirSync, statSync, existsSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const uiDir = resolve(__dirname, '..');
const repoRoot = resolve(uiDir, '..');
const targetDir = join(repoRoot, 'mockserver', 'mockserver-netty-no-dependencies', 'target');

const PORT = process.env.E2E_MS_PORT || '1084';

function findJar() {
  if (process.env.E2E_MS_JAR) {
    return existsSync(process.env.E2E_MS_JAR) ? process.env.E2E_MS_JAR : null;
  }
  if (!existsSync(targetDir)) return null;
  const candidates = readdirSync(targetDir)
    .filter(
      (f) =>
        f.startsWith('mockserver-netty-no-dependencies-') &&
        f.endsWith('.jar') &&
        !f.includes('-sources') &&
        !f.includes('-javadoc') &&
        !f.startsWith('original-'),
    )
    .map((f) => join(targetDir, f))
    .map((p) => ({ p, mtime: statSync(p).mtimeMs }))
    .sort((a, b) => b.mtime - a.mtime);
  return candidates.length > 0 ? candidates[0].p : null;
}

function buildJar() {
  console.error(
    '[e2e] No runnable MockServer JAR found — building it (mvnw install -pl mockserver-netty-no-dependencies -am -DskipTests). This can take a few minutes…',
  );
  const mvnw = join(repoRoot, 'mockserver', 'mvnw');
  const result = spawnSync(
    mvnw,
    ['install', '-DskipTests', '-pl', 'mockserver-netty-no-dependencies', '-am', '-q'],
    { cwd: join(repoRoot, 'mockserver'), stdio: ['ignore', 'inherit', 'inherit'] },
  );
  if (result.status !== 0) {
    console.error('[e2e] MockServer JAR build FAILED');
    process.exit(1);
  }
}

let jar = findJar();
if (!jar) {
  buildJar();
  jar = findJar();
}
if (!jar) {
  console.error('[e2e] Could not locate a runnable MockServer JAR after build');
  process.exit(1);
}

console.error(`[e2e] Booting MockServer on port ${PORT} from ${jar}`);

// Modest heap + capped log ring buffer: the suite fires only a handful of
// requests, so it never needs the heap-scaled default (up to 100,000 entries).
const child = spawn(
  'java',
  [
    '-Xmx512m',
    '-Dmockserver.maxLogEntries=2000',
    '-jar',
    jar,
    '-serverPort',
    PORT,
    '-logLevel',
    'WARN',
  ],
  // Run in a throwaway temp dir so MockServer's startup artifacts (e.g. the
  // exported mockserver-ca.pem) never land in the source tree.
  { stdio: ['ignore', 'inherit', 'inherit'], cwd: mkdtempSync(join(tmpdir(), 'mockserver-e2e-')) },
);

// Forward termination from Playwright (SIGTERM/SIGINT) to the JVM so no server
// is left listening after the run.
for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => {
    child.kill('SIGTERM');
    process.exit(0);
  });
}
child.on('exit', (code) => process.exit(code ?? 0));
