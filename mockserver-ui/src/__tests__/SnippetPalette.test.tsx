/**
 * Tests for the SnippetPalette component:
 * - The palette renders and opens on click
 * - All three snippet categories appear
 * - Clicking a snippet calls onInsert with the engine-appropriate syntax
 * - Engine-awareness: switching between Velocity / Mustache / JavaScript
 *   shows the correct syntax in the Chip and inserts the correct syntax
 *
 * Integration-level test: rendering the TemplatePanel (inside ComposerView)
 * and verifying that inserting a snippet updates the template body textarea.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import SnippetPalette from '../components/SnippetPalette';
import { SNIPPET_CATEGORIES, ALL_SNIPPETS } from '../lib/templateSnippets';
import type { TemplateEngine } from '../lib/templateSnippets';
import ComposerView from '../components/ComposerView';
import { useDashboardStore } from '../store';

vi.mock('../lib/mcpClient', () => ({
  buildBaseUrl: () => 'http://127.0.0.1:1080',
  callMcpTool: vi.fn().mockResolvedValue({ ok: true, result: { tools: [], count: 0 } }),
}));

vi.mock('../lib/conversationCodegen', () => ({
  listConversationScenarios: () => [],
}));

const theme = buildTheme('dark');
const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderPalette(engine: TemplateEngine = 'VELOCITY', onInsert = vi.fn()) {
  const result = render(
    <ThemeProvider theme={theme}>
      <SnippetPalette engine={engine} onInsert={onInsert} />
    </ThemeProvider>,
  );
  return { ...result, onInsert };
}

function renderComposer() {
  // The composer now defaults to "Quick mock"; this integration test exercises
  // the Advanced TemplatePanel, so seed the session preference to Advanced.
  try { globalThis.sessionStorage?.setItem('mockserver-composer-mode', 'advanced'); } catch { /* noop */ }
  return render(
    <ThemeProvider theme={theme}>
      <ComposerView connectionParams={params} />
    </ThemeProvider>,
  );
}

describe('SnippetPalette (unit)', () => {
  it('renders the "Insert snippet" button', () => {
    renderPalette();
    expect(screen.getByRole('button', { name: /insert snippet/i })).toBeInTheDocument();
  });

  it('opens the popover on click and shows all category headings', async () => {
    const user = userEvent.setup();
    renderPalette();
    await user.click(screen.getByRole('button', { name: /insert snippet/i }));

    for (const cat of SNIPPET_CATEGORIES) {
      expect(screen.getByText(cat.label)).toBeInTheDocument();
    }
  });

  it('shows all snippet labels when opened', async () => {
    const user = userEvent.setup();
    renderPalette();
    await user.click(screen.getByRole('button', { name: /insert snippet/i }));

    for (const snippet of ALL_SNIPPETS) {
      expect(screen.getByText(snippet.label)).toBeInTheDocument();
    }
  });

  it('calls onInsert with Velocity syntax when engine is VELOCITY', async () => {
    const user = userEvent.setup();
    const { onInsert } = renderPalette('VELOCITY');
    await user.click(screen.getByRole('button', { name: /insert snippet/i }));

    // Click the "Request path" snippet
    await user.click(screen.getByText('Request path'));

    expect(onInsert).toHaveBeenCalledTimes(1);
    expect(onInsert).toHaveBeenCalledWith('$request.path');
  });

  it('calls onInsert with Mustache syntax when engine is MUSTACHE', async () => {
    const user = userEvent.setup();
    const { onInsert } = renderPalette('MUSTACHE');
    await user.click(screen.getByRole('button', { name: /insert snippet/i }));

    await user.click(screen.getByText('Request path'));

    expect(onInsert).toHaveBeenCalledWith('{{ request.path }}');
  });

  it('calls onInsert with JavaScript syntax when engine is JAVASCRIPT', async () => {
    const user = userEvent.setup();
    const { onInsert } = renderPalette('JAVASCRIPT');
    await user.click(screen.getByRole('button', { name: /insert snippet/i }));

    await user.click(screen.getByText('Request path'));

    expect(onInsert).toHaveBeenCalledWith('request.path');
  });

  it('closes the popover after inserting a snippet', async () => {
    const user = userEvent.setup();
    renderPalette();
    await user.click(screen.getByRole('button', { name: /insert snippet/i }));
    expect(screen.getByText('Template Snippets')).toBeInTheDocument();

    await user.click(screen.getByText('UUID'));

    // The popover title should no longer be visible
    await waitFor(() => {
      expect(screen.queryByText('Template Snippets')).not.toBeInTheDocument();
    });
  });

  it('shows example output for snippets', async () => {
    const user = userEvent.setup();
    renderPalette();
    await user.click(screen.getByRole('button', { name: /insert snippet/i }));

    // The UUID snippet's example output should be visible
    const uuidSnippet = ALL_SNIPPETS.find((s) => s.id === 'uuid')!;
    expect(screen.getByText(uuidSnippet.exampleOutput)).toBeInTheDocument();
  });
});

describe('SnippetPalette integration with ComposerView', () => {
  beforeEach(() => {
    useDashboardStore.setState({ activeExpectations: [] });
  });

  it('inserts a Velocity snippet into the template body field via the Composer', async () => {
    const user = userEvent.setup();
    renderComposer();

    // Select "Response template" action type
    const templateRadio = screen.getByLabelText(/Response template/);
    await user.click(templateRadio);

    // The "Insert snippet" button should now be visible
    const insertBtn = screen.getByRole('button', { name: /insert snippet/i });
    expect(insertBtn).toBeInTheDocument();

    // Click to open the palette and select "UUID"
    await user.click(insertBtn);
    await user.click(screen.getByText('UUID'));

    // The template body textarea should now contain the Velocity UUID syntax
    const templateBody = screen.getByLabelText('Template body') as HTMLTextAreaElement;
    expect(templateBody.value).toContain('$uuid');
  });

  it('switches snippet syntax when the template engine changes', async () => {
    const user = userEvent.setup();
    renderComposer();

    // Select "Response template" action type
    const templateRadio = screen.getByLabelText(/Response template/);
    await user.click(templateRadio);

    // Change engine to Mustache — the engine select is labeled "Template engine"
    const engineSelect = screen.getByLabelText('Template engine');
    await user.click(engineSelect);
    const mustacheOption = await screen.findByRole('option', { name: 'Mustache' });
    await user.click(mustacheOption);

    // Open the snippet palette and insert "Request method"
    await user.click(screen.getByRole('button', { name: /insert snippet/i }));
    await user.click(screen.getByText('Request method'));

    // The textarea should contain Mustache syntax
    const templateBody = screen.getByLabelText('Template body') as HTMLTextAreaElement;
    expect(templateBody.value).toContain('{{ request.method }}');
    expect(templateBody.value).not.toContain('$request.method');
  });
});
