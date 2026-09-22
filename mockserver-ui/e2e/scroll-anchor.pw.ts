import { test, expect, type Page } from '@playwright/test';

// REGRESSION: opening an item in a live dashboard panel must not be disturbed by
// new items streaming in. New rows are PREPENDED (the lists are newest-first), so
// without scroll anchoring the content below the insertion point shifts down by
// the height added, the row the reader scrolled to and expanded slides away, and
// — because the list is virtualized — it eventually unmounts, which reads as the
// opened item "closing". Panel.tsx anchors the viewport by adding exactly the
// height that appeared above the reader.
//
// This CANNOT be a jsdom/vitest test: jsdom has no layout engine, so scrollTop /
// scrollHeight / offsetHeight are always 0, virtualization never windows, and the
// prepend-shift cannot occur. It runs in a real browser (Chromium) against the
// real Panel + ProgressiveList via the Vite harness in e2e/anchor-harness.

// Locate the panel's scroll container (Panel's internal overflow-y:auto Box) and
// read/drive its scroll position, all in the page. Returns the element handle via
// a data attribute we stamp on it so later calls address the same element.
async function findScroller(page: Page) {
  const found = await page.evaluate(() => {
    const frame = document.querySelector('[data-testid="frame"]');
    if (!frame) return false;
    const all = frame.querySelectorAll<HTMLElement>('*');
    for (const el of Array.from(all)) {
      const oy = getComputedStyle(el).overflowY;
      if ((oy === 'auto' || oy === 'scroll') && el.scrollHeight > el.clientHeight + 1) {
        el.setAttribute('data-scroller', '');
        return true;
      }
    }
    return false;
  });
  expect(found, 'a scrollable panel container was found').toBeTruthy();
  return page.locator('[data-scroller]');
}

test('an expanded row stays put while new items are prepended above it', async ({ page }) => {
  await page.goto('/');

  // The initial dataset is mounted and the list has windowed (far fewer rows in
  // the DOM than the 60 that exist proves virtualization is active).
  await expect(page.getByTestId('frame')).toBeVisible();
  await page.waitForFunction(() => document.querySelectorAll('[data-vrow]').length > 0);
  const mounted = await page.locator('[data-vrow]').count();
  expect(mounted, 'list is windowed (not every row mounted)').toBeLessThan(60);

  const scroller = await findScroller(page);

  // Scroll DOWN, away from the top, so tail-following is off and prepends land
  // above the viewport — the condition under which the bug bites.
  await scroller.evaluate((el) => {
    (el as HTMLElement).scrollTop = 700;
  });
  await page.waitForTimeout(100);
  const scrollTopBefore = await scroller.evaluate((el) => (el as HTMLElement).scrollTop);
  expect(scrollTopBefore, 'scrolled away from the top').toBeGreaterThan(100);

  // Pick a row currently inside the viewport and expand it.
  const viewportRect = await scroller.evaluate((el) => {
    const r = (el as HTMLElement).getBoundingClientRect();
    return { top: r.top, bottom: r.bottom };
  });
  const targetKey = await page.evaluate((vp) => {
    const rows = Array.from(document.querySelectorAll<HTMLElement>('[data-testid^="row-"]'));
    for (const row of rows) {
      const r = row.getBoundingClientRect();
      // Comfortably inside the viewport, with room below for the expanded detail.
      if (r.top > vp.top + 20 && r.top < vp.bottom - 120) {
        return row.getAttribute('data-testid')!.replace('row-', '');
      }
    }
    return null;
  }, viewportRect);
  expect(targetKey, 'found a visible row to expand').not.toBeNull();

  const targetRow = page.getByTestId(`row-${targetKey}`);
  await page.getByTestId(`toggle-${targetKey}`).click();
  await expect(page.getByTestId(`detail-${targetKey}`)).toBeVisible();
  await expect(targetRow).toHaveAttribute('data-expanded', 'true');

  const topBefore = await targetRow.evaluate((el) => el.getBoundingClientRect().top);

  // Now stream in 50 new rows, prepended to the FRONT, in five pushes — exactly
  // what the live feed does roughly once a second.
  for (let i = 0; i < 5; i++) {
    await page.evaluate(() => (window as unknown as { prependRows: (n: number) => void }).prependRows(10));
    await page.waitForTimeout(60);
  }

  // The dataset grew from 60 to 110 rows.
  await expect(page.getByTestId('frame')).toBeVisible();

  // (a) The opened row is still in the DOM and still expanded.
  await expect(targetRow, 'opened row remained mounted').toHaveCount(1);
  await expect(targetRow).toHaveAttribute('data-expanded', 'true');
  await expect(page.getByTestId(`detail-${targetKey}`), 'opened row stayed expanded').toBeVisible();

  // (b) It is still within the visible viewport.
  const vpAfter = await scroller.evaluate((el) => {
    const r = (el as HTMLElement).getBoundingClientRect();
    return { top: r.top, bottom: r.bottom };
  });
  const topAfter = await targetRow.evaluate((el) => el.getBoundingClientRect().top);
  expect(topAfter, 'opened row still inside the viewport (top edge)').toBeGreaterThanOrEqual(vpAfter.top - 1);
  expect(topAfter, 'opened row still inside the viewport (bottom edge)').toBeLessThan(vpAfter.bottom);

  // (c) The viewport still shows the same content: the opened row has not moved
  // more than a couple of pixels despite 50 rows being inserted above it.
  expect(Math.abs(topAfter - topBefore)).toBeLessThanOrEqual(3);
});

// REGRESSION, the steady state: the server caps a dashboard panel at
// DEFAULT_LOG_UPDATE_ITEM_LIMIT (100) rows, so on any server that has handled more
// than that, every push PREPENDS new rows and EVICTS the same number from the tail.
// The list length is then constant for the rest of the session. That is the state a
// busy server spends virtually all its time in, so an anchor keyed on the row count
// growing would be inert exactly when it is needed. This test pins the constant-count
// case; the test above pins the initial growth phase.
test('an expanded row stays put when the list length is fixed and rows are evicted', async ({ page }) => {
  await page.goto('/');

  await expect(page.getByTestId('frame')).toBeVisible();
  await page.waitForFunction(() => document.querySelectorAll('[data-vrow]').length > 0);

  // Grow the list to 200 first, so it is long enough that the row we open is not
  // itself evicted by the pushes below. A prepended row pushes every existing row
  // one place further from the front, so a row opened near the top of a 60-row
  // list would fall off the END after 50 prepends — that would be the test
  // evicting its own subject, not the anchor failing.
  await page.evaluate(() => (window as unknown as { prependRows: (n: number) => void }).prependRows(140));
  await page.waitForFunction(() => (window as unknown as { rowCount: number }).rowCount === 200);

  const scroller = await findScroller(page);
  await scroller.evaluate((el) => {
    (el as HTMLElement).scrollTop = 700;
  });
  await page.waitForTimeout(100);

  const viewportRect = await scroller.evaluate((el) => {
    const r = (el as HTMLElement).getBoundingClientRect();
    return { top: r.top, bottom: r.bottom };
  });
  const targetKey = await page.evaluate((vp) => {
    const rows = Array.from(document.querySelectorAll<HTMLElement>('[data-testid^="row-"]'));
    for (const row of rows) {
      const r = row.getBoundingClientRect();
      if (r.top > vp.top + 20 && r.top < vp.bottom - 120) {
        return row.getAttribute('data-testid')!.replace('row-', '');
      }
    }
    return null;
  }, viewportRect);
  expect(targetKey, 'found a visible row to expand').not.toBeNull();

  const targetRow = page.getByTestId(`row-${targetKey}`);
  await page.getByTestId(`toggle-${targetKey}`).click();
  await expect(page.getByTestId(`detail-${targetKey}`)).toBeVisible();

  // The DATA length, not the mounted-row count — the virtualizer's window size
  // varies with row heights, so counting DOM rows would not test what it claims.
  const lengthBefore = await page.evaluate(
    () => (window as unknown as { rowCount: number }).rowCount,
  );
  const topBefore = await targetRow.evaluate((el) => el.getBoundingClientRect().top);

  // Five pushes of ten, each prepending ten and evicting ten — list length fixed.
  for (let i = 0; i < 5; i++) {
    await page.evaluate(() => (window as unknown as { pushRows: (n: number) => void }).pushRows(10));
    await page.waitForTimeout(60);
  }

  // Prove the premise of this test: the mounted-row count did not grow, so any
  // anchor gated on a growing count would not have fired at all.
  const lengthAfter = await page.evaluate(
    () => (window as unknown as { rowCount: number }).rowCount,
  );
  expect(lengthAfter, 'list length stayed fixed (rows evicted as rows arrived)').toBe(lengthBefore);

  await expect(targetRow, 'opened row remained mounted').toHaveCount(1);
  await expect(page.getByTestId(`detail-${targetKey}`), 'opened row stayed expanded').toBeVisible();

  const vpAfter = await scroller.evaluate((el) => {
    const r = (el as HTMLElement).getBoundingClientRect();
    return { top: r.top, bottom: r.bottom };
  });
  const topAfter = await targetRow.evaluate((el) => el.getBoundingClientRect().top);
  expect(topAfter, 'opened row still inside the viewport (top edge)').toBeGreaterThanOrEqual(vpAfter.top - 1);
  expect(topAfter, 'opened row still inside the viewport (bottom edge)').toBeLessThan(vpAfter.bottom);
  expect(Math.abs(topAfter - topBefore)).toBeLessThanOrEqual(3);
});
