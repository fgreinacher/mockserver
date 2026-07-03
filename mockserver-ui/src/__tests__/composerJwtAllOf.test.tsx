/**
 * Composer coverage for the two request-matcher features added so the JAVA (and
 * every other) codegen tab can represent them: the optional JWT matcher section
 * and the `allOf` composite body matcher. The assertions observe the built
 * expectation JSON via the "Test Matcher" playground, which is seeded from the
 * exact buildExpectationJson output that would be registered.
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

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderComposer() {
  try { globalThis.sessionStorage?.setItem('mockserver-composer-mode', 'advanced'); } catch { /* noop */ }
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <ComposerView connectionParams={params} />
    </ThemeProvider>,
  );
}

afterEach(cleanup);

async function candidateJson(user: ReturnType<typeof userEvent.setup>): Promise<string> {
  await user.click(screen.getByRole('button', { name: 'Test Matcher' }));
  const candidate = screen.getByLabelText('Candidate expectation JSON') as HTMLTextAreaElement;
  return candidate.value;
}

describe('Composer — JWT matcher section', () => {
  beforeEach(() => {
    useDashboardStore.setState({ activeExpectations: [] });
  });

  it('is off by default (no jwt in the built JSON)', async () => {
    const user = userEvent.setup();
    renderComposer();
    await user.type(screen.getByLabelText('Path'), '/secure');
    expect(await candidateJson(user)).not.toContain('"jwt"');
  });

  it('emits a jwt criterion once enabled and claims are entered', async () => {
    const user = userEvent.setup();
    renderComposer();
    await user.type(screen.getByLabelText('Path'), '/secure');

    await user.click(screen.getByTestId('jwt-enable'));
    await user.type(screen.getByTestId('jwt-claims'), 'sub=user-1');
    await user.type(screen.getByTestId('jwt-issuer'), 'https://issuer');

    const json = await candidateJson(user);
    expect(json).toContain('"jwt"');
    expect(json).toContain('"sub": "user-1"');
    expect(json).toContain('"issuer": "https://issuer"');
  });
});

describe('Composer — allOf composite body matcher', () => {
  beforeEach(() => {
    useDashboardStore.setState({ activeExpectations: [] });
  });

  it('emits an ALL_OF body composed of the sub-matcher rows', async () => {
    const user = userEvent.setup();
    renderComposer();
    await user.type(screen.getByLabelText('Path'), '/orders');

    const bodyType = screen.getByLabelText('Body type');
    await user.click(bodyType);
    await user.click(await screen.findByRole('option', { name: 'All of (compose matchers)' }));

    // One sub-matcher row appears via "Add sub-matcher"; default type is JSON.
    await user.click(screen.getByTestId('add-allof-row'));
    // `{{` escapes userEvent's special `{`, so the literal typed is `{"a":1}`.
    await user.type(screen.getByTestId('allof-value-0'), '{{"a":1}');

    const json = await candidateJson(user);
    expect(json).toContain('"type": "ALL_OF"');
    expect(json).toContain('"bodyAllOf"');
    expect(json).toContain('"type": "JSON"');
  });
});
