// Capture documentation screenshots of every MockServer dashboard tab.
//
// This drives a *running* demo dashboard with headless Chromium and writes one
// PNG per tab. It does NOT start the demo itself — bring the dashboard up first
// with `npm run demo` (see scripts/launch-with-demo-data.sh) so every panel is
// populated with representative data, then run this against it.
//
// The capture geometry matches the existing website screenshots: a 1920-wide
// viewport at deviceScaleFactor 2, i.e. ~3840px-wide Retina PNGs, so new shots
// are as crisp as the ones already on www.mock-server.com.
//
// Usage:
//   node scripts/capture-dashboard-screenshots.mjs
//   ONLY=chaos,metrics node scripts/capture-dashboard-screenshots.mjs
//   FULL_PAGE=true OUT_DIR=/tmp/shots node scripts/capture-dashboard-screenshots.mjs
//
// Env (all optional):
//   UI_PORT      dev-server port the dashboard is served on   (default 3000)
//   MS_PORT      MockServer control-plane port (?port=)        (default 1080)
//   OUT_DIR      directory to write PNGs into                  (default jekyll-www.mock-server.com/images)
//   ONLY         comma-separated tab values to capture         (default all)
//   WIDTH        CSS viewport width                            (default 1920)
//   HEIGHT       CSS viewport height                           (default 900)
//   SCALE        deviceScaleFactor (Retina = 2)                (default 2)
//   FULL_PAGE    "true" to capture the whole scroll height     (default false)
//   SETTLE_MS    extra settle delay before each capture (ms)   (default 1200)
//   THEME        "light" or "dark" colour scheme               (default light)
//   BROWSER_CHANNEL  Playwright browser channel to launch        (default: bundled
//                Chromium if installed, else the installed "chrome")

import { chromium } from 'playwright';
import { fileURLToPath } from 'node:url';
import { dirname, resolve, join } from 'node:path';
import { mkdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(__dirname, '..', '..');

const UI_PORT = process.env.UI_PORT || '3000';
const MS_PORT = process.env.MS_PORT || '1080';
const OUT_DIR = process.env.OUT_DIR
  ? resolve(process.env.OUT_DIR)
  : join(repoRoot, 'jekyll-www.mock-server.com', 'images');
const WIDTH = Number(process.env.WIDTH || 1920);
const HEIGHT = Number(process.env.HEIGHT || 900);
const SCALE = Number(process.env.SCALE || 2);
const FULL_PAGE = process.env.FULL_PAGE === 'true';
const SETTLE_MS = Number(process.env.SETTLE_MS || 1200);
const THEME = process.env.THEME === 'dark' ? 'dark' : 'light';
const ONLY = (process.env.ONLY || '').split(',').map((s) => s.trim()).filter(Boolean);

const DASHBOARD_URL = `http://localhost:${UI_PORT}/mockserver/dashboard/?port=${MS_PORT}`;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// One entry per dashboard tab. `value` + `ariaLabel` mirror NAV_TABS in
// src/components/AppBar.tsx; `file` follows the website's MockServer<Name>.png
// convention (existing names reused so docs pages need no <img> edits).
//   lazy      — panel is React.lazy-loaded and shows a "Loading…" placeholder
//               that must clear before we shoot.
//   settleMs  — extra dwell before the shot, for panels that keep filling in
//               after mount (the Metrics/Performance time-series charts render
//               "collecting…" until they have a few sampling intervals; gRPC,
//               LLM Optimise, and Sessions fetch/group their data async).
//   prepare   — interactions to reach a richer documentation state before the
//               shot (open the Advanced editor, select an LLM conversation,
//               expand the HTTP chaos form). Best-effort: a failure is logged
//               and the capture still happens.
//   followExempt — why this tab is allowed to be captured with a panel away
//               from the tail. See "Follow state" below: every other tab must be
//               following, and only a tab that deliberately drives a panel off
//               the tail may opt out, with its reason stated here.
const CHART_SETTLE = Number(process.env.CHART_SETTLE_MS || 8000);
const SLOW_SETTLE = Number(process.env.SLOW_SETTLE_MS || 6000);

const TABS = [
  { value: 'get-started',  ariaLabel: 'Get started view',          file: 'MockServerGetStarted.png' },
  { value: 'dashboard',    ariaLabel: 'Dashboard view',            file: 'MockServerDashboard.png' },
  {
    value: 'traffic', ariaLabel: 'Traffic inspector view', file: 'MockServerTrafficInspector.png', settleMs: SLOW_SETTLE,
    // Selecting a row deliberately stops that panel following (TrafficInspector's
    // `interacting`), which is the documented behaviour this shot is showing — so
    // the at-the-tail assertion is waived HERE and nowhere else.
    followExempt: 'prepare selects an exchange, which intentionally pauses following',
    // Open an LLM exchange and show its conversation, not a bare HTTP row.
    prepare: async (page) => {
      // Search for the LLM exchanges first, rather than hunting for one in the
      // unfiltered feed. In console order the list is pinned to its tail, and what
      // is AT the tail depends on how the demo happened to finish seeding — its MCP
      // forwards land last, several seconds after the LLM traffic, so on a slower
      // run every visible row is an MCP forward and no /v1/messages row is on screen
      // to click. Picking one by DOM order instead is no better: the list is
      // virtualized, so an off-screen match is not reliably scrollable into view.
      // Filtering makes the shot the same every time, and shows the search field
      // doing its job.
      const search = page.locator('#traffic-inspector-search');
      await search.waitFor({ state: 'visible', timeout: 8000 });
      await search.fill('/v1/messages');
      const row = page.getByText('/v1/messages', { exact: false }).last();
      await row.waitFor({ state: 'visible', timeout: 10000 });
      await row.click();
      const convo = page.getByRole('tab', { name: 'Conversation' });
      await convo.waitFor({ state: 'visible', timeout: 8000 });
      await convo.click();
    },
  },
  { value: 'breakpoints',  ariaLabel: 'Breakpoints view',          file: 'MockServerBreakpoints.png' },
  {
    value: 'composer', ariaLabel: 'Mocks view', file: 'MockServerComposer.png', lazy: true, settleMs: SLOW_SETTLE,
    // Show the full Advanced expectation editor rather than Quick mode.
    prepare: async (page) => {
      const advanced = page.locator('[aria-label="Advanced"]').first();
      await advanced.waitFor({ state: 'visible', timeout: 8000 });
      await advanced.click();
    },
  },
  {
    value: 'chaos', ariaLabel: 'Service chaos view', file: 'MockServerChaos.png',
    // Expand the HTTP Service Chaos section so its form fields show, while the
    // other high-level sections stay collapsed.
    prepare: async (page) => {
      const header = page.getByText('HTTP Service Chaos', { exact: false }).first();
      await header.waitFor({ state: 'visible', timeout: 8000 });
      const expand = page.getByRole('button', { name: 'Expand HTTP chaos' });
      if (await expand.isVisible().catch(() => false)) await expand.click();
      else await header.click();
    },
  },
  {
    value: 'performance', ariaLabel: 'Performance testing view', file: 'MockServerPerformance.png', lazy: true, settleMs: CHART_SETTLE,
    // Show the RUNNING load scenarios (live stats and charts), not the empty create
    // form. The panel renders "Running Now" once GET /mockserver/loadScenario reports
    // a live run — so just wait for it. Shoot this EARLY in the load run (during
    // ramp): at sustained peak the status endpoint is starved and the panel falls
    // back to the create form. If it never appears, we still capture.
    //
    // `load-running-scenarios`, not `load-live-status`: the latter is the older
    // single-run readout, which the panel only renders for a lone `status`. The demo
    // starts two scenarios, so it never appeared and this step timed out for 20s on
    // every run before quietly giving up — while the shot it was waiting for was on
    // screen the whole time.
    prepare: async (page) => {
      await page.locator('[data-testid="load-running-scenarios"]').first()
        .waitFor({ state: 'visible', timeout: 20000 });
    },
  },
  { value: 'optimise',     ariaLabel: 'LLM Optimise view',         file: 'MockServerOptimise.png',     lazy: true, settleMs: SLOW_SETTLE },
  { value: 'mcp-health',   ariaLabel: 'MCP server health view',    file: 'MockServerMcpHealth.png',    lazy: true, settleMs: SLOW_SETTLE },
  { value: 'async',        ariaLabel: 'AsyncAPI broker mock view', file: 'MockServerAsyncAPI.png' },
  { value: 'grpc',         ariaLabel: 'gRPC services view',        file: 'MockServerGRPC.png',         settleMs: SLOW_SETTLE },
  { value: 'sessions',     ariaLabel: 'Trace inspector view',      file: 'MockServerSessions.png',     settleMs: SLOW_SETTLE },
  { value: 'library',      ariaLabel: 'Library of captured content', file: 'MockServerLibrary.png' },
  { value: 'drift',        ariaLabel: 'Drift detection view',      file: 'MockServerDrift.png' },
  { value: 'verification', ariaLabel: 'Verification view',         file: 'MockServerVerification.png' },
  { value: 'contract',     ariaLabel: 'Contract test view',        file: 'MockServerContract.png' },
  { value: 'slo',          ariaLabel: 'SLO verification view',     file: 'MockServerSLO.png',          settleMs: SLOW_SETTLE },
  { value: 'cluster',      ariaLabel: 'Cluster status view',       file: 'MockServerCluster.png' },
  { value: 'metrics',      ariaLabel: 'Metrics view',              file: 'MockServerMetrics.png',      lazy: true, settleMs: CHART_SETTLE },
];

// Which nav group (by its group-button aria-label) each view lives under in the
// grouped AppBar nav (NAV_GROUPS in src/components/AppBar.tsx). `sessions` (Trace)
// appears under both Observe and AI; Observe is used here for navigation.
const GROUP_OF = {
  'get-started': 'Mock views', composer: 'Mock views', grpc: 'Mock views', async: 'Mock views',
  dashboard: 'Observe views', traffic: 'Observe views', sessions: 'Observe views', metrics: 'Observe views',
  verification: 'Verify views', contract: 'Verify views', slo: 'Verify views', drift: 'Verify views',
  chaos: 'Resilience views', performance: 'Resilience views',
  optimise: 'AI views', 'mcp-health': 'AI views',
  breakpoints: 'Inspect views', library: 'Inspect views', cluster: 'Inspect views',
};

// Navigate to a tab. The AppBar nav has two layouts (AppBar.tsx `compactNav`,
// `useMediaQuery(down('lg'))`): below lg a single "Open navigation menu"
// hamburger lists every view; at/above lg (our 1920 capture width) one button
// per GROUP opens a dropdown of that group's views. In both layouts each view is
// a `[role="menuitem"]` whose accessible name is `tab.ariaLabel`.
async function gotoTab(page, tab) {
  const item = page.locator(`[role="menuitem"][aria-label="${tab.ariaLabel}"]`).first();
  const compact = page.locator('[aria-label="Open navigation menu"]').first();
  if (await compact.isVisible().catch(() => false)) {
    await compact.click();
  } else {
    const groupLabel = GROUP_OF[tab.value];
    if (!groupLabel) throw new Error(`No nav group mapped for tab "${tab.value}"`);
    const groupButton = page.locator(`button[aria-label="${groupLabel}"]`).first();
    if (!(await groupButton.isVisible().catch(() => false))) {
      throw new Error(`No way to reach tab "${tab.value}" — neither the hamburger nor the "${groupLabel}" group button is visible`);
    }
    await groupButton.click();
  }
  await item.waitFor({ state: 'visible', timeout: 8000 });
  await item.click();
}

// --- Follow state -----------------------------------------------------------
//
// The live panels render in CONSOLE ORDER: oldest first, newest appended at the
// bottom. A panel that is not following therefore sits at the TOP of its window,
// showing its OLDEST rows — so a screenshot can render perfectly while showing
// stale content, which is exactly the failure this capture must not ship.
//
// Panels default to following (store `autoScroll: true`, see src/store/index.ts,
// plumbed through src/hooks/useFollow.ts), so a freshly loaded page should already
// be at the tail. That default is asserted rather than assumed: it is the sort of
// thing a future UI change can flip with nothing failing.

/** Within this many px of the bottom counts as "at the tail" (useTailFollow uses 8). */
const AT_TAIL_TOLERANCE_PX = 24;

/**
 * Turn following back on wherever it is off: the toolbar master switch first
 * (AppBar.tsx labels it "Follow new entries" when OFF), then any individual panel
 * chip, which Panel.tsx labels "Follow" when off and "Following" when on.
 */
async function ensureFollowing(page) {
  const master = page.locator('[aria-label="Follow new entries"]').first();
  if (await master.isVisible().catch(() => false)) {
    console.warn('    ! toolbar master follow switch was OFF — turning it on');
    await master.click().catch(() => {});
    await sleep(400);
  }
  // Selected and clicked in the page, on exactly the predicate followState reports
  // on. A Playwright `hasText` locator was tried first and over-counted — it claimed
  // three paused chips on a Dashboard whose chips all read "Following", so the run
  // log described a problem that was not there. An instrument that reports on one
  // thing while acting on another is worse than no instrument.
  const clicked = await page.evaluate(() =>
    Array.from(document.querySelectorAll('.MuiChip-root'))
      .filter((chip) => (chip.textContent || '').trim() === 'Follow')
      .map((chip) => {
        chip.click();
        return true;
      }).length,
  );
  if (clicked) {
    console.warn(`    ! ${clicked} panel chip(s) were not following — clicked Follow`);
    await sleep(800);
  }
}

/**
 * Per Follow-capable panel: its chip state and how far its scroll container is
 * from the bottom. Reported for every shot so the log is the evidence that the
 * panels were at the tail when the shutter fired.
 */
async function followState(page) {
  return page.evaluate(() => {
    const scrollerIn = (root) =>
      Array.from(root.querySelectorAll('*')).find((el) => {
        const style = getComputedStyle(el);
        return (
          (style.overflowY === 'auto' || style.overflowY === 'scroll') &&
          el.scrollHeight - el.clientHeight > 4
        );
      });
    return Array.from(document.querySelectorAll('.MuiChip-root'))
      .filter((chip) => /^(Follow|Following)$/.test((chip.textContent || '').trim()))
      .map((chip) => {
        const panel = chip.closest('.MuiPaper-root') || document.body;
        const heading = panel.querySelector('h1, h2, h3, h4, h5, h6, .MuiTypography-root');
        const el = scrollerIn(panel);
        return {
          panel: ((heading && heading.textContent) || 'panel').trim().slice(0, 28),
          following: (chip.textContent || '').trim() === 'Following',
          // No scroller means the content fits — nothing can be scrolled out of shot.
          scrollable: Boolean(el),
          gap: el ? Math.round(el.scrollHeight - el.scrollTop - el.clientHeight) : 0,
        };
      });
  });
}

async function main() {
  await mkdir(OUT_DIR, { recursive: true });

  const wanted = ONLY.length ? TABS.filter((t) => ONLY.includes(t.value)) : TABS;
  if (!wanted.length) {
    throw new Error(`ONLY=${process.env.ONLY} matched no tabs. Valid values: ${TABS.map((t) => t.value).join(', ')}`);
  }

  // Playwright's own Chromium build cannot always be downloaded (a corporate
  // TLS-inspecting proxy times out cdn.playwright.dev), so fall back to an
  // installed Google Chrome, which is the same Chromium and renders these shots
  // identically. BROWSER_CHANNEL forces a channel either way.
  const launchOptions = { headless: true };
  if (process.env.BROWSER_CHANNEL) {
    launchOptions.channel = process.env.BROWSER_CHANNEL;
  } else {
    let bundled = null;
    try { bundled = chromium.executablePath(); } catch { /* not registered */ }
    if (!bundled || !existsSync(bundled)) {
      console.warn("    ! bundled Chromium not installed — using the installed Chrome (channel=chrome)");
      launchOptions.channel = 'chrome';
    }
  }
  const browser = await chromium.launch(launchOptions);
  const context = await browser.newContext({
    viewport: { width: WIDTH, height: HEIGHT },
    deviceScaleFactor: SCALE,
    colorScheme: THEME,
  });
  // Force the dashboard's own theme to THEME before the app boots. The app does
  // NOT honour the browser `colorScheme`/prefers-color-scheme — it reads its own
  // `mockserver-theme` localStorage key (default 'dark', see store getInitialTheme),
  // so without this the shots come out dark regardless of the context colorScheme.
  await context.addInitScript((mode) => {
    try { window.localStorage.setItem('mockserver-theme', mode); } catch { /* localStorage unavailable */ }
  }, THEME);

  const page = await context.newPage();

  console.log(`→ Opening ${DASHBOARD_URL}`);
  await page.goto(DASHBOARD_URL, { waitUntil: 'domcontentloaded' });

  // Wait for the nav to exist (app booted), then for the WebSocket to actually
  // connect — the dashboard fills the traffic/log/expectation stores from the
  // server's snapshot only once the header flips from "Connecting" to
  // "Connected". Capturing before that yields empty panels.
  await page.waitForSelector('[aria-label="Open navigation menu"], button[aria-label="Mock views"]', { timeout: 30000 });
  await page
    .waitForFunction(() => /\bConnected\b/.test(document.body.innerText || ''), null, { timeout: 30000 })
    .catch(() => console.warn('    ! header never showed "Connected" — panels may be empty (is load injection saturating the connection?)'));
  await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});
  await sleep(2000);

  let ok = 0;
  const notAtTail = [];
  for (const tab of wanted) {
    try {
      await gotoTab(page, tab);
      // Lazy panels render a "Loading …" placeholder first; let it clear.
      if (tab.lazy) {
        await page
          .getByText(/Loading/i)
          .first()
          .waitFor({ state: 'hidden', timeout: 20000 })
          .catch(() => {});
      }
      await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
      // Pin every panel to its tail BEFORE the prepare step, so whatever prepare
      // selects is chosen from the newest rows rather than the oldest.
      await ensureFollowing(page);
      // Drive the tab into a richer documentation state (best-effort).
      if (tab.prepare) {
        try {
          await tab.prepare(page);
        } catch (err) {
          console.warn(`    ! ${tab.value} prepare step skipped: ${err.message.split('\n')[0]}`);
        }
      }
      // Charts (Metrics/Performance) keep drawing as samples arrive — give those
      // tabs longer to finish "collecting…" before the shot.
      await page.getByText(/collecting/i).first().waitFor({ state: 'hidden', timeout: 12000 }).catch(() => {});
      await sleep(tab.settleMs || SETTLE_MS);

      // Pre-capture assertion: panels must be following and sitting at the tail,
      // or the shot shows the oldest rows they hold. Re-pin once, then record any
      // panel that still is not — a rendered-but-wrong screenshot must fail loudly.
      let follow = await followState(page);
      if (!tab.followExempt && follow.some((p) => !p.following || p.gap > AT_TAIL_TOLERANCE_PX)) {
        await ensureFollowing(page);
        await sleep(1200);
        follow = await followState(page);
      }
      if (follow.length) {
        console.log(
          `      follow: ${follow
            .map((p) => `${p.panel}=${p.following ? 'Following' : 'Follow'}@${p.gap}px`)
            .join(', ')}`,
        );
      }
      const stale = tab.followExempt
        ? []
        : follow.filter((p) => !p.following || p.gap > AT_TAIL_TOLERANCE_PX);
      if (tab.followExempt) {
        console.log(`      follow assertion waived — ${tab.followExempt}`);
      }
      if (stale.length) {
        notAtTail.push(`${tab.value}: ${stale.map((p) => `${p.panel} (${p.following ? 'following' : 'NOT following'}, ${p.gap}px from the tail)`).join('; ')}`);
        console.warn(`    ! ${tab.value} NOT at the tail — ${stale.length} panel(s) would show stale rows`);
      }

      const out = join(OUT_DIR, tab.file);
      await page.screenshot({ path: out, fullPage: FULL_PAGE });
      console.log(`  ✓ ${tab.value.padEnd(13)} → ${out}`);
      ok++;
    } catch (err) {
      console.error(`  ✗ ${tab.value.padEnd(13)} FAILED: ${err.message}`);
    }
  }

  await browser.close();
  console.log(`\nCaptured ${ok}/${wanted.length} tab(s) into ${OUT_DIR} at ${WIDTH}x${HEIGHT}@${SCALE}x`);
  if (notAtTail.length) {
    console.error(`\n✗ ${notAtTail.length} tab(s) were captured with a panel away from the tail — those shots show STALE rows:`);
    for (const line of notAtTail) console.error(`    ${line}`);
    process.exitCode = 1;
  } else {
    console.log('✓ every Follow-capable panel was following and at the tail when captured');
  }
  if (ok < wanted.length) process.exitCode = 1;
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
