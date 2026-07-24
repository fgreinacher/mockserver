import { test, expect, type APIRequestContext } from '@playwright/test';

// Real-browser end-to-end coverage of the SERVED dashboard against a live
// MockServer (booted by e2e/start-mockserver.mjs). Unlike the 178 jsdom/vitest
// specs — which mock `fetch` and the WebSocket — these drive the actual browser
// against the real server over real REST and a real WebSocket.
//
// Covers the two HIGH coverage gaps:
//   #15  no Playwright e2e drives the served dashboard against a live server,
//        and nothing exercises the live WebSocket log stream end to end.
//   #17  expectation CRUD from the dashboard is only tested with mocked fetch,
//        never against the real /mockserver/* control-plane endpoints.

// Must match playwright.config.ts's baseURL origin: local runs use the managed
// JAR on 127.0.0.1:1084; CI points at the already-running server container via
// E2E_MS_HOST/E2E_MS_PORT.
const HOST = process.env.E2E_MS_HOST || '127.0.0.1';
const PORT = process.env.E2E_MS_PORT || '1084';
const ORIGIN = `http://${HOST}:${PORT}`;

// Query the server's active expectation list over the wire (PUT /mockserver/retrieve).
// This is the SERVER's own state — the assertion that the UI actions really
// mutated the server, not just the client store.
async function activeExpectations(request: APIRequestContext): Promise<Array<Record<string, unknown>>> {
  const res = await request.put(`${ORIGIN}/mockserver/retrieve?type=active_expectations`);
  expect(res.ok(), `retrieve returned ${res.status()}`).toBeTruthy();
  return (await res.json()) as Array<Record<string, unknown>>;
}

function responseStatusOf(expectation: Record<string, unknown>): number | undefined {
  const resp = expectation['httpResponse'] as Record<string, unknown> | undefined;
  const code = resp?.['statusCode'];
  return typeof code === 'number' ? code : undefined;
}

function requestPathOf(expectation: Record<string, unknown>): string | undefined {
  const req = expectation['httpRequest'] as Record<string, unknown> | undefined;
  const p = req?.['path'];
  return typeof p === 'string' ? p : undefined;
}

function responseBodyOf(expectation: Record<string, unknown>): unknown {
  const resp = expectation['httpResponse'] as Record<string, unknown> | undefined;
  return resp?.['body'];
}

// Reset server state before each test so the active-expectation assertions
// count only what the test itself registered. Same wire path the UI uses.
test.beforeEach(async ({ request }) => {
  const res = await request.put(`${ORIGIN}/mockserver/reset`);
  expect(res.ok(), `reset returned ${res.status()}`).toBeTruthy();
});

// ---------------------------------------------------------------------------
// #15 — live WebSocket log stream
// ---------------------------------------------------------------------------
test('creates an expectation in the composer, then streams the matching request into the log panel live over the WebSocket', async ({
  page,
  request,
}) => {
  const path = `/e2e/live-${Date.now()}`;

  // Land straight on the composer (the store reads the view from the URL hash).
  await page.goto('./#/composer');

  // The WebSocket connects on mount — wait for the AppBar status chip to say so,
  // proving the real ws://…/_mockserver_ui_websocket handshake succeeded.
  await expect(page.getByText('connected', { exact: true })).toBeVisible();

  // Author a minimal HTTP mock in the Quick form and register it through the UI.
  const quick = page.getByTestId('quick-mock-form');
  await expect(quick).toBeVisible();
  await quick.getByLabel('Path', { exact: true }).fill(path);
  await quick.getByLabel('Status code', { exact: true }).fill('201');
  await page.getByRole('button', { name: 'Register mock' }).click();
  await expect(page.getByTestId('register-success')).toBeVisible();

  // Move to the live Dashboard (log panel) WITHOUT a page reload, so the same
  // WebSocket connection stays open and we genuinely observe a live push. The
  // DashboardGrid renders two responsive layouts (one hidden by CSS), so scope
  // assertions to the VISIBLE copy.
  await page.evaluate(() => {
    window.location.hash = '#/dashboard';
  });
  // The LogPanel content is a role="log" ARIA live region. It starts empty.
  const logRegion = page.getByRole('log').filter({ visible: true });
  await expect(logRegion).toBeVisible();
  await expect(logRegion).toContainText('No log messages yet');

  // Fire a MATCHING request over the wire. MockServer serves the mock (201) and
  // logs the received request — which it pushes to the open page via the WS.
  const fired = await request.get(`${ORIGIN}${path}`);
  expect(fired.status(), 'the composer-created mock should have matched (201, not a 404)').toBe(201);

  // A new log entry appears in the live region purely because the WebSocket
  // delivered it — no reload, no polling. The matched request logs an
  // EXPECTATION_RESPONSE. Playwright auto-retries until it arrives, proving the
  // log panel streams live off the real WebSocket (it was empty a moment ago).
  await expect(logRegion.getByText('EXPECTATION_RESPONSE').first()).toBeVisible();

  // Bind that live push to OUR request: the same WebSocket feed renders the
  // received request in the dashboard as plain text. The path is uniquely
  // timestamped, so a visible occurrence proves the exact request we fired
  // streamed into the dashboard live (it cannot have come from anywhere else).
  await expect(page.getByText(path, { exact: false }).filter({ visible: true }).first()).toBeVisible();
});

// ---------------------------------------------------------------------------
// #17 — expectation CRUD through the dashboard against the real control plane
// ---------------------------------------------------------------------------
test('creates, updates and clears an expectation through the dashboard UI, changing the server expectation list over real REST', async ({
  page,
  request,
}) => {
  const stamp = Date.now();
  const id = `e2e-crud-${stamp}`;
  const path = `/e2e/crud-${stamp}`;

  await page.goto('./#/composer');
  await expect(page.getByText('connected', { exact: true })).toBeVisible();

  // Advanced mode exposes the Expectation ID field, so create + update are
  // deterministically the SAME expectation (upsert by id).
  await page.getByRole('button', { name: 'Advanced', exact: true }).click();

  // --- CREATE -------------------------------------------------------------
  await page.getByLabel('Expectation ID (optional)').fill(id);
  await page.getByLabel('Path', { exact: true }).fill(path);
  await page.getByLabel('Status code', { exact: true }).fill('201');
  await page.getByRole('button', { name: /Register expectation|Update expectation/ }).click();
  await expect(page.getByTestId('register-success')).toBeVisible();

  // The SERVER now holds exactly our expectation, with the 201 response.
  await expect
    .poll(async () => {
      const list = await activeExpectations(request);
      const ours = list.filter((e) => e['id'] === id);
      return ours.length === 1 ? responseStatusOf(ours[0]!) : `count=${ours.length}`;
    })
    .toBe(201);

  // --- UPDATE (in place, same id) ----------------------------------------
  await page.getByLabel('Status code', { exact: true }).fill('202');
  await page.getByRole('button', { name: /Register expectation|Update expectation/ }).click();
  await expect(page.getByTestId('register-success')).toBeVisible();

  // Still one expectation for our id, now serving 202 — updated in place, not duplicated.
  await expect
    .poll(async () => {
      const list = await activeExpectations(request);
      const ours = list.filter((e) => e['id'] === id);
      return ours.length === 1 ? responseStatusOf(ours[0]!) : `count=${ours.length}`;
    })
    .toBe(202);

  // --- CLEAR (through the AppBar clear menu) ------------------------------
  await page.getByRole('button', { name: 'Clear logs, expectations, or reset server' }).click();
  await page.getByRole('menuitem', { name: 'Clear Server Expectations' }).click();
  await page.getByRole('button', { name: 'Clear expectations' }).click();

  // The SERVER's list no longer contains our expectation.
  await expect
    .poll(async () => (await activeExpectations(request)).filter((e) => e['id'] === id).length)
    .toBe(0);
});

// ---------------------------------------------------------------------------
// #64 — the REAL Monaco editor authors the response body end to end
// ---------------------------------------------------------------------------
// The 178 jsdom/vitest specs globally replace @monaco-editor/react with a plain
// <textarea data-testid="monaco-textarea"> (Monaco cannot run without real
// layout + web workers), so NOTHING there exercises the actual editor: no
// syntax tokenisation, no JSON-language web-worker validation, no Monaco DOM.
// This drives the ACTUAL bundled Monaco in a real browser:
//   • it renders Monaco's own DOM (.monaco-editor / .view-lines) — proof it is
//     the real editor, not the vitest textarea stand-in (which has neither);
//   • breaking the JSON in the editor raises a live validation marker from
//     Monaco's JSON language web worker, which clears when the break is undone
//     (the mock never runs a worker, so this squiggle can only come from the
//     real editor);
//   • the body authored via real editor input round-trips to the server — it
//     lands in the registered expectation (PUT /mockserver/retrieve) and is
//     served back verbatim on the matching request.
test('authors the response body in the REAL Monaco editor, validates malformed JSON live, and round-trips the typed body to the server', async ({
  page,
  request,
}) => {
  const stamp = Date.now();
  const path = `/e2e/monaco-${stamp}`;
  const marker = `monaco-typed-${stamp}`;
  // A JSON string value is the whole response body.
  const body = `"${marker}"`;

  await page.goto('./#/composer');
  await expect(page.getByText('connected', { exact: true })).toBeVisible();

  const quick = page.getByTestId('quick-mock-form');
  await expect(quick).toBeVisible();
  await quick.getByLabel('Path', { exact: true }).fill(path);
  await quick.getByLabel('Status code', { exact: true }).fill('201');

  // The "Response body" editor is a REAL Monaco instance. Monaco lazy-loads, so
  // wait for its own DOM to mount. `.monaco-editor` + `.view-lines` exist ONLY
  // for the real editor — the vitest mock is a bare textarea with neither.
  const editorBox = quick.getByTestId('json-editor');
  await expect(editorBox).toBeVisible();
  const monacoEditor = editorBox.locator('.monaco-editor');
  await expect(monacoEditor).toBeVisible();
  const viewLines = editorBox.locator('.view-lines');
  await expect(viewLines).toBeVisible();

  // Author the body with real editor input. keyboard.insertText drives Monaco's
  // model, tokenizer, JSON web worker and onChange, written verbatim into the
  // empty editor (no auto-close/suggest-widget mangling).
  await monacoEditor.click();
  await page.keyboard.insertText(body);

  // Monaco rendered exactly the characters we entered into its own view (the
  // vitest mock is a bare textarea with no .view-lines), and — being well-formed
  // JSON — the editor shows no validation marker.
  const jsonErrors = quick.getByTestId('json-editor-errors');
  await expect(async () => {
    const rendered = (await viewLines.textContent()) ?? '';
    expect(rendered).toContain(marker);
  }).toPass();
  await expect(jsonErrors).toHaveCount(0);

  // --- LIVE VALIDATION (real Monaco JSON web worker) ----------------------
  // Append a stray character so the document is no longer well-formed JSON.
  // Monaco's JSON language web worker flags it and the editor surfaces the
  // marker beside the field (data-testid="json-editor-errors"). The vitest mock
  // runs no worker, so this squiggle can only come from the real editor. Then
  // Backspace removes exactly that character and the marker clears again,
  // leaving the well-formed body we submit.
  await page.keyboard.press('End');
  await page.keyboard.insertText('!');
  await expect(jsonErrors).toBeVisible();
  await page.keyboard.press('Backspace');
  await expect(jsonErrors).toHaveCount(0);
  await expect(async () => {
    const rendered = (await viewLines.textContent()) ?? '';
    expect(rendered).toContain(marker);
  }).toPass();

  // --- REGISTER + SERVER ROUND-TRIP ---------------------------------------
  await page.getByRole('button', { name: 'Register mock' }).click();
  await expect(page.getByTestId('register-success')).toBeVisible();

  // The body Monaco holds landed in the SERVER's registered expectation.
  await expect
    .poll(async () => {
      const ours = (await activeExpectations(request)).filter((e) => requestPathOf(e) === path);
      if (ours.length !== 1) return `count=${ours.length}`;
      const b = responseBodyOf(ours[0]!);
      return typeof b === 'string' ? b : JSON.stringify(b);
    })
    .toContain(marker);

  // And the Monaco-authored body is served verbatim on the matching request —
  // the round-trip the composer promises, driven entirely by real editor input.
  const served = await request.get(`${ORIGIN}${path}`);
  expect(served.status(), 'the Monaco-authored mock should match (201, not 404)').toBe(201);
  expect(await served.text()).toContain(marker);
});
