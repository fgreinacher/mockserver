import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import StandardReview from '../components/StandardReview';
import { standardToJson, type StandardMatcher, type StandardActionPayload } from '../lib/standardCodegen';

const matcher: StandardMatcher = {
  id: 'abc-123',
  method: 'GET',
  path: '/api/widgets',
  headers: '',
  queryString: '',
  cookies: '',
  pathParams: '',
  body: '',
  bodyBinary: false,
  bodyMatcherType: 'string',
  priority: 0,
  times: 0,
};

const action: StandardActionPayload = {
  type: 'static',
  static: { statusCode: 200, body: '{"ok":true}', contentType: 'application/json' },
};

function renderReview(originalJson?: string) {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <StandardReview matcher={matcher} action={action} baseUrl="http://localhost:1080" originalJson={originalJson} />
    </ThemeProvider>,
  );
}

describe('StandardReview preview diff', () => {
  it('does not render a diff when no originalJson is supplied (creating)', () => {
    renderReview(undefined);
    expect(screen.queryByTestId('standard-review-diff')).not.toBeInTheDocument();
  });

  it('renders a before→after diff when editing an existing expectation', async () => {
    const original = JSON.stringify({ id: 'abc-123', httpRequest: { path: '/api/widgets' } }, null, 2);
    renderReview(original);

    expect(screen.getByTestId('standard-review-diff')).toBeInTheDocument();
    // The diff is loaded via a lazy/Suspense split point (JsonDiffViewerLazy keeps
    // monaco out of the main bundle), so its panes resolve asynchronously.
    // "before" pane is the loaded expectation, verbatim.
    expect(await screen.findByTestId('monaco-diff-original')).toHaveValue(original);
    // "after" pane is the outgoing JSON that will be PUT — identical to the JSON
    // codegen the Copy tab shows, so the preview is faithful.
    const modified = screen.getByTestId('monaco-diff-modified') as HTMLTextAreaElement;
    expect(modified.value).toBe(standardToJson(matcher, action));
  });
});
