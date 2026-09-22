import { test, expect } from '@playwright/test';

// An item opened in a dashboard panel must stay open, and stay still, while live
// traffic streams in. This is the defect the repo owner reported three times.
//
// Why this exists ALONGSIDE e2e/scroll-anchor.pw.ts, which tests the same
// behaviour: that one drives Panel + ProgressiveList through a synthetic harness,
// and a harness can only test the situation its author imagined. An earlier fix
// shipped with a harness test that was green, degrade-confirmed red, on the right
// components — and still certified a fix that did nothing in production, because
// the harness modelled a list that GROWS while the real panel is capped at
// DEFAULT_LOG_UPDATE_ITEM_LIMIT (100) rows and evicts as it prepends, so its
// length never changes. The harness and the code shared a false premise, so the
// harness could not contradict the code.
//
// This test removes that degree of freedom: a real MockServer, the real bundled
// dashboard, the real WebSocket, and real traffic it generates itself. It cannot
// disagree with production about how the list behaves, because it IS production.
//
// Degrade-confirmed against a real JAR: with ProgressiveList's anchor correction
// disabled, the opened request disappears from the list within 8s of live traffic
// (`present: false`); with it, the row's viewport top is unchanged to the pixel.

const BASE = process.env.E2E_MS_HOST
  ? `http://${process.env.E2E_MS_HOST}:${process.env.E2E_MS_PORT || '1080'}`
  : 'http://127.0.0.1:1084';

// Comfortably past the 100-row panel cap, so the panel is in the steady state a
// busy server spends effectively all its time in: every push prepends a row and
// evicts one, and the row count never moves again.
const SEED_REQUESTS = 160;
const TRAFFIC_INTERVAL_MS = 250;
const TRAFFIC_WINDOW_MS = 8000;

test.beforeEach(async ({ request }) => {
  const res = await request.put(`${BASE}/mockserver/reset`);
  expect(res.ok(), `reset returned ${res.status()}`).toBeTruthy();
});

test('an opened request stays open and still while live traffic arrives', async ({ page, request }) => {
  for (let i = 0; i < SEED_REQUESTS; i++) {
    await request.get(`${BASE}/seed/${i}/item`);
  }

  // The dashboard opens on the Get Started view and does not auto-switch, so the
  // panels are not mounted until the view is selected. The view is mirrored in
  // the URL hash, which is steadier to address than a button label.
  await page.goto(`${BASE}/mockserver/dashboard/#/dashboard`);

  await page.waitForFunction(() => document.querySelectorAll('[data-vrow]').length > 5, undefined, {
    timeout: 60_000,
  });

  // Find the Received Requests panel's scroll box by walking up from its heading.
  const found = await page.evaluate(() => {
    const heading = Array.from(document.querySelectorAll('*')).find(
      (el) => el.children.length === 0 && el.textContent?.trim() === 'Received Requests',
    );
    if (!heading) return false;
    let node: HTMLElement | null = heading.parentElement;
    while (node) {
      const box = Array.from(node.querySelectorAll<HTMLElement>('*')).find((el) => {
        const oy = getComputedStyle(el).overflowY;
        return (oy === 'auto' || oy === 'scroll') && el.scrollHeight > el.clientHeight + 1;
      });
      if (box) {
        box.setAttribute('data-live-scroller', '');
        return true;
      }
      node = node.parentElement;
    }
    return false;
  });
  expect(found, 'found the Received Requests scroll box').toBeTruthy();
  const box = page.locator('[data-live-scroller]');

  // Scroll away from the top — tail-following is off there, and prepends land
  // above the viewport, which is the condition the bug needs. Proportional to the
  // panel's own height so it holds at any viewport size.
  await box.evaluate((el) => {
    const e = el as HTMLElement;
    e.scrollTop = Math.min(600, Math.floor(e.scrollHeight / 3));
  });
  await page.waitForTimeout(400);
  expect(
    await box.evaluate((el) => (el as HTMLElement).scrollTop),
    'scrolled away from the top',
  ).toBeGreaterThan(50);

  // Identify the row by its request PATH. Its rendered text is NOT its identity:
  // the panel renders a display ordinal that changes for the same request every
  // time a newer one arrives.
  const targetPath = await page.evaluate(() => {
    const el = document.querySelector('[data-live-scroller]') as HTMLElement;
    const vp = el.getBoundingClientRect();
    for (const row of Array.from(el.querySelectorAll<HTMLElement>('[data-vrow]'))) {
      const r = row.getBoundingClientRect();
      if (r.top > vp.top + 10 && r.top < vp.bottom - 80) {
        const m = (row.textContent || '').match(/\/seed\/\d+\/item/);
        if (m) {
          row.setAttribute('data-live-anchor', '');
          return m[0];
        }
      }
    }
    return null;
  });
  expect(targetPath, 'found a visible request row to open').not.toBeNull();

  const anchor = page.locator('[data-live-anchor]');
  const topBefore = await anchor.evaluate((el) => el.getBoundingClientRect().top);
  const collapsedHeight = await anchor.evaluate((el) => el.getBoundingClientRect().height);

  await anchor.click();
  await page.waitForTimeout(800);
  const openedHeight = await anchor.evaluate((el) => el.getBoundingClientRect().height);
  // Without this the test could pass by holding a COLLAPSED row still, which is
  // not the thing that was reported broken.
  expect(openedHeight, 'the click expanded the row').toBeGreaterThan(collapsedHeight * 1.5);

  // Stream live traffic, as a busy server does, while the row sits open.
  const deadline = Date.now() + TRAFFIC_WINDOW_MS;
  let n = 0;
  while (Date.now() < deadline) {
    await request.get(`${BASE}/seed/live-${n++}/item`);
    await page.waitForTimeout(TRAFFIC_INTERVAL_MS);
  }

  const after = await page.evaluate((want) => {
    const el = document.querySelector('[data-live-scroller]') as HTMLElement;
    const match = Array.from(el.querySelectorAll<HTMLElement>('[data-vrow]')).find((r) =>
      (r.textContent || '').includes(want!),
    );
    if (!match) return { present: false, top: 0, height: 0 };
    const r = match.getBoundingClientRect();
    return { present: true, top: r.top, height: r.height };
  }, targetPath);

  expect(after.present, 'the opened request is still in the list').toBeTruthy();
  expect(after.height, 'the opened request is still expanded').toBeGreaterThan(openedHeight * 0.8);
  expect(
    Math.abs(after.top - topBefore),
    'the opened request has not moved in the viewport',
  ).toBeLessThanOrEqual(6);
});
