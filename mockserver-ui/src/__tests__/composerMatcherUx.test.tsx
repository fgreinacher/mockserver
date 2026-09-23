/**
 * Composer matcher-UX improvements:
 * - "Test Matcher" button opens the matcher playground seeded with the current
 *   draft matcher (built via the shared buildExpectationJson codegen path, so
 *   the tested matcher is exactly what would be registered).
 * - Line-format validation: malformed lines in the Headers/Query/Cookies/Path
 *   matcher textareas surface a non-blocking warning, but are still dropped from
 *   the registered payload (behaviour unchanged — the warning is presentational).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import ComposerView from '../components/ComposerView';
import { useDashboardStore } from '../store';

vi.mock('../lib/mcpClient', () => ({
  buildBaseUrl: () => 'http://127.0.0.1:1080',
  callMcpTool: vi.fn().mockResolvedValue({ ok: true, result: { tools: [], count: 0 } }),
}));

vi.mock('../lib/conversationCodegen', () => ({
  listConversationScenarios: () => [],
}));

// These render the WHOLE Composer, which is a large component, and then drive it
// through real user-event interactions. Two things follow, both of which have
// failed in CI while passing in isolation locally:
//
//   TIME. The global 20s testTimeout (vitest.config.ts) is sized for ordinary
//   tests; a full Composer mount competing with ~200 other files for workers can
//   exceed it. What is under test is matcher UX, not how fast the Composer
//   renders, so a clock is the wrong constraint.
//
//   IDENTITY. `getByLabelText` resolves synchronously against whatever is mounted
//   at that instant. While the Composer is still settling the textarea can be
//   replaced, and user-event then fails with "The element to be cleared could not
//   be focused" -- acting on a node that has already left the document. Re-query
//   immediately before interacting rather than holding a reference across awaits.
const HEAVY_COMPOSER_TIMEOUT_MS = 60000;

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderComposer() {
  // Seed the session preference to Advanced (read by getInitialMode on mount)
  // so the full matcher form — and the Test Matcher button — is shown.
  try { globalThis.sessionStorage?.setItem('mockserver-composer-mode', 'advanced'); } catch { /* noop */ }
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <ComposerView connectionParams={params} />
    </ThemeProvider>,
  );
}

afterEach(cleanup);

describe('Composer — Test Matcher button', { timeout: HEAVY_COMPOSER_TIMEOUT_MS }, () => {
  beforeEach(() => {
    useDashboardStore.setState({ activeExpectations: [] });
  });

  it('opens the playground seeded with the current draft method/path', async () => {
    const user = userEvent.setup();
    renderComposer();

    const pathField = screen.getByLabelText('Path');
    await user.clear(pathField);
    await user.type(pathField, '/seeded/path');

    await user.click(screen.getByRole('button', { name: 'Test Matcher' }));

    // The playground dialog is open...
    expect(screen.getByText('Matcher Test Playground')).toBeTruthy();
    // ...seeded with the exact expectation JSON that would be registered.
    const candidate = screen.getByLabelText('Candidate expectation JSON') as HTMLTextAreaElement;
    expect(candidate.value).toContain('/seeded/path');
    expect(candidate.value).toContain('"method": "GET"');
  });

  it('the seeded expectation drops malformed matcher lines (registered payload unchanged)', async () => {
    const user = userEvent.setup();
    renderComposer();

    await user.type(screen.getByLabelText('Path'), '/inv');
    const headers = screen.getByLabelText('Headers (Name: value per line)');
    await user.type(headers, 'garbage-no-colon{enter}Accept: application/json');

    await user.click(screen.getByRole('button', { name: 'Test Matcher' }));
    const candidate = screen.getByLabelText('Candidate expectation JSON') as HTMLTextAreaElement;
    // The valid header is present in the payload that would register...
    expect(candidate.value).toContain('Accept');
    // ...and the malformed line is dropped (buildExpectationJson unchanged).
    expect(candidate.value).not.toContain('garbage-no-colon');
  });
});

describe('Composer — matcher line-format validation', { timeout: HEAVY_COMPOSER_TIMEOUT_MS }, () => {
  beforeEach(() => {
    useDashboardStore.setState({ activeExpectations: [] });
  });

  it('warns about a malformed header line and clears the warning when fixed', async () => {
    const user = userEvent.setup();
    renderComposer();

    const headers = await screen.findByLabelText('Headers (Name: value per line)');
    await user.type(headers, 'no-separator-here');
    expect(await screen.findByText(/1 line ignored/)).toBeTruthy();

    // Correct the line — the warning disappears. Re-query rather than reusing the
    // reference above: the Composer may have re-rendered during the awaits, and
    // clearing a detached node fails with an unfocusable-element error.
    await user.clear(await screen.findByLabelText('Headers (Name: value per line)'));
    await user.type(
      await screen.findByLabelText('Headers (Name: value per line)'),
      'Accept: application/json',
    );
    expect(screen.queryByText(/ignored/)).toBeNull();
  });

  it('warns about a malformed query-param line with the key=value format hint', async () => {
    const user = userEvent.setup();
    renderComposer();

    const query = screen.getByLabelText('Query params (key=value per line)');
    await user.type(query, 'limit 50');
    const warning = screen.getByText(/1 line ignored/);
    expect(warning.textContent).toContain('key=value');
  });
});
