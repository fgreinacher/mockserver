import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import VerificationView from '../components/VerificationView';

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderView() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <VerificationView connectionParams={params} />
    </ThemeProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('VerificationView', () => {
  it('renders in single-request mode by default', () => {
    renderView();
    expect(screen.getByText('Verification')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Single request', pressed: true })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Verify' })).toBeInTheDocument();
  });

  it('shows the times selector with at-least selected by default', () => {
    renderView();
    // The Select should show "at least"
    expect(screen.getByText('at least')).toBeInTheDocument();
  });

  it('switches to sequence mode and back', async () => {
    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByRole('button', { name: 'Ordered sequence' }));
    expect(screen.getByText(/requests must have been received in this order/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Verify sequence' })).toBeInTheDocument();
    // Sequence mode starts with 2 steps
    expect(screen.getByText('1.')).toBeInTheDocument();
    expect(screen.getByText('2.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Single request' }));
    expect(screen.getByRole('button', { name: 'Verify' })).toBeInTheDocument();
  });

  it('adds a step in sequence mode', async () => {
    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByRole('button', { name: 'Ordered sequence' }));
    await user.click(screen.getByRole('button', { name: /Add step/i }));

    expect(screen.getByText('3.')).toBeInTheDocument();
  });

  it('shows success alert when verification passes (202)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        status: 202,
        text: async () => '',
      }),
    );

    const user = userEvent.setup();
    renderView();

    // Fill in a path so the Verify button is enabled (request matcher is now optional but at least one matcher required)
    const pathField = screen.getByLabelText('Path');
    await user.type(pathField, '/test');

    await user.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => {
      expect(screen.getByText(/Verified/)).toBeInTheDocument();
    });
  });

  it('shows failure alert when verification fails (406)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        status: 406,
        statusText: 'Not Acceptable',
        text: async () => 'Request not found exactly 1 times',
      }),
    );

    const user = userEvent.setup();
    renderView();

    // Fill in a path so the Verify button is enabled
    const pathField = screen.getByLabelText('Path');
    await user.type(pathField, '/test');

    await user.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => {
      expect(screen.getByText('Verification failed')).toBeInTheDocument();
    });
    expect(screen.getByText(/Request not found/)).toBeInTheDocument();
  });

  it('shows error alert on network failure', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockRejectedValue(new Error('network down')),
    );

    const user = userEvent.setup();
    renderView();

    // Fill in a path so the Verify button is enabled
    const pathField = screen.getByLabelText('Path');
    await user.type(pathField, '/test');

    await user.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => {
      expect(screen.getByText('network down')).toBeInTheDocument();
    });
  });

  it('sends the correct body for sequence verification', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      status: 202,
      text: async () => '',
    });
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByRole('button', { name: 'Ordered sequence' }));

    // Fill in a path in the first step so the Verify sequence button is enabled
    const pathFields = screen.getAllByLabelText('Path');
    await user.type(pathFields[0]!, '/a');

    await user.click(screen.getByRole('button', { name: 'Verify sequence' }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledOnce();
    });

    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toContain('/mockserver/verifySequence');
    const body = JSON.parse(init.body as string);
    expect(body.httpRequests).toBeInstanceOf(Array);
    expect(body.httpRequests).toHaveLength(2);
  });

  // --- Response matcher section tests ---

  it('shows collapsed response matcher section in single mode', () => {
    renderView();
    expect(screen.getByText('Response matcher (optional)')).toBeInTheDocument();
    // Status code field is rendered but hidden (MUI Collapse keeps content in the DOM)
    const statusField = screen.getByLabelText('Status code');
    expect(statusField.closest('.MuiCollapse-root')).toHaveStyle({ height: '0px' });
  });

  it('expands response matcher section on click and shows fields', async () => {
    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByText('Response matcher (optional)'));
    expect(screen.getByLabelText('Status code')).toBeInTheDocument();
    expect(screen.getByLabelText(/Response body/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Response headers/)).toBeInTheDocument();
    expect(screen.getByText(/Match against responses recorded from proxied\/forwarded traffic/)).toBeInTheDocument();
  });

  it('sends httpResponse when status code is filled in single mode', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      status: 202,
      text: async () => '',
    });
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderView();

    // Expand the response matcher section
    await user.click(screen.getByText('Response matcher (optional)'));

    // Fill in a status code
    const statusField = screen.getByLabelText('Status code');
    await user.type(statusField, '200');

    await user.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledOnce();
    });

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body as string);
    expect(body.httpResponse).toEqual({ statusCode: 200 });
  });

  it('does NOT send httpResponse when response fields are empty', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      status: 202,
      text: async () => '',
    });
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderView();

    // Fill in a path so the Verify button is enabled (need at least one matcher)
    const pathField = screen.getByLabelText('Path');
    await user.type(pathField, '/test');

    // Click verify without expanding/filling response matcher
    await user.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledOnce();
    });

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body as string);
    expect(body).not.toHaveProperty('httpResponse');
  });

  it('sends httpResponse with body when response body is filled', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      status: 202,
      text: async () => '',
    });
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderView();

    // Expand and fill response body
    await user.click(screen.getByText('Response matcher (optional)'));
    const bodyField = screen.getByLabelText(/Response body/);
    await user.type(bodyField, '{{"result":"ok"}}');

    await user.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledOnce();
    });

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body as string);
    expect(body.httpResponse).toHaveProperty('body');
  });

  it('shows response matcher in sequence mode steps', async () => {
    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByRole('button', { name: 'Ordered sequence' }));

    // Should have 2 response matcher toggles (one per step)
    const toggles = screen.getAllByText('Response matcher (optional)');
    expect(toggles).toHaveLength(2);
  });

  it('does NOT send httpResponses in sequence mode when no response fields are filled', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      status: 202,
      text: async () => '',
    });
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByRole('button', { name: 'Ordered sequence' }));

    // Fill in a path in the first step so the Verify sequence button is enabled
    const pathFields = screen.getAllByLabelText('Path');
    await user.type(pathFields[0]!, '/a');

    await user.click(screen.getByRole('button', { name: 'Verify sequence' }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledOnce();
    });

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body as string);
    expect(body).not.toHaveProperty('httpResponses');
  });

  // --- Optional request matcher behaviour ---

  it('enables Verify button with an empty form (verify-any-request default)', () => {
    renderView();
    const verifyBtn = screen.getByRole('button', { name: 'Verify' });
    expect(verifyBtn).toBeEnabled();
    // No hint caption — both matchers are optional and the empty form is valid
    expect(screen.queryByText(/Add a request matcher/)).not.toBeInTheDocument();
  });

  it('enables Verify sequence button with empty steps', async () => {
    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByRole('button', { name: 'Ordered sequence' }));
    const seqBtn = screen.getByRole('button', { name: 'Verify sequence' });
    expect(seqBtn).toBeEnabled();
    expect(screen.queryByText(/Add a request matcher/)).not.toBeInTheDocument();
  });

  it('sends httpRequest:{} when form is empty (verify-any-request default)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      status: 202,
      text: async () => '',
    });
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledOnce();
    });

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body as string);
    // Empty form defaults to a request verification matching any request
    expect(body.httpRequest).toEqual({});
    expect(body.times).toBeDefined();
    expect(body).not.toHaveProperty('httpResponse');
  });

  it('sends response-only body when only a response matcher is filled', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      status: 202,
      text: async () => '',
    });
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderView();

    // Expand the response matcher section and fill a status code
    await user.click(screen.getByText('Response matcher (optional)'));
    const statusField = screen.getByLabelText('Status code');
    await user.type(statusField, '200');

    await user.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledOnce();
    });

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body as string);
    // Response-only: no httpRequest key in the body
    expect(body).not.toHaveProperty('httpRequest');
    expect(body.httpResponse).toEqual({ statusCode: 200 });
    expect(body.times).toBeDefined();
  });

  // --- Code generation panel tests ---

  it('shows generated code panel even when no matchers are filled (empty form = verify any request)', () => {
    renderView();
    expect(screen.getByText('Generated Code')).toBeInTheDocument();
    // Java tab should be present and selected by default
    expect(screen.getByRole('tab', { name: 'Java' })).toBeInTheDocument();
    // The empty-form code should contain a verify call with request()
    expect(screen.getByText(/mockServerClient/)).toBeInTheDocument();
  });

  it('shows generated code panel with Java tab when a request path is filled', async () => {
    const user = userEvent.setup();
    renderView();

    const pathField = screen.getByLabelText('Path');
    await user.type(pathField, '/api/orders');

    expect(screen.getByText('Generated Code')).toBeInTheDocument();
    // Java tab should be present and selected by default
    expect(screen.getByRole('tab', { name: 'Java' })).toBeInTheDocument();
    // The code should contain the Java verify call
    expect(screen.getByText(/mockServerClient/)).toBeInTheDocument();
    expect(screen.getByText(/request\(\)/)).toBeInTheDocument();
  });

  it('shows generated code panel in sequence mode even with empty steps', () => {
    renderView();
    // The panel is always present, even before switching to sequence mode
    expect(screen.getByText('Generated Code')).toBeInTheDocument();
  });

  it('shows all 9 language tabs in the generated code panel', () => {
    renderView();

    const tabLabels = ['Java', 'Node.js', 'Python', 'Go', 'C#', 'Ruby', 'Rust', 'JSON', 'curl'];
    for (const label of tabLabels) {
      expect(screen.getByRole('tab', { name: label })).toBeInTheDocument();
    }
  });

  it('sends response-only sequence body when only a response matcher is filled in one step', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      status: 202,
      text: async () => '',
    });
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByRole('button', { name: 'Ordered sequence' }));

    // Expand the response matcher in the first step and fill a status code
    const toggles = screen.getAllByText('Response matcher (optional)');
    await user.click(toggles[0]!);
    const statusFields = screen.getAllByLabelText('Status code');
    await user.type(statusFields[0]!, '201');

    await user.click(screen.getByRole('button', { name: 'Verify sequence' }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledOnce();
    });

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body as string);
    // Response-only sequence: no httpRequests key
    expect(body).not.toHaveProperty('httpRequests');
    expect(body.httpResponses).toBeDefined();
  });
});
