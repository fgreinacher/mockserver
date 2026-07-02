import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ScimDialog from '../components/ScimDialog';

const connectionParams = { host: '127.0.0.1', port: '1080', secure: false };

interface FetchCall {
  url: string;
  init?: RequestInit;
}

function stubFetch(status: number, body: unknown): FetchCall[] {
  const calls: FetchCall[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      return {
        ok: status >= 200 && status < 300,
        status,
        statusText: 'stub',
        json: async () => body,
        text: async () => (typeof body === 'string' ? body : JSON.stringify(body)),
      };
    }),
  );
  return calls;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('ScimDialog', () => {
  it('PUTs an empty config for a fully-defaulted provider and reports the created count', async () => {
    const user = userEvent.setup();
    const calls = stubFetch(201, [{ id: '1' }, { id: '2' }]);
    render(<ScimDialog open onClose={() => {}} connectionParams={connectionParams} />);

    await user.click(screen.getByRole('button', { name: 'Create provider' }));

    await waitFor(() => expect(calls.length).toBe(1));
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/scim');
    expect(calls[0]?.init?.method).toBe('PUT');
    expect(JSON.parse(String(calls[0]?.init?.body))).toEqual({});
    expect(await screen.findByText('Created 2 expectations for the mock SCIM provider.')).toBeInTheDocument();
  });

  it('sends only the non-default fields the user set, including a pinned bearer token', async () => {
    const user = userEvent.setup();
    const calls = stubFetch(201, [{ id: '1' }]);
    render(<ScimDialog open onClose={() => {}} connectionParams={connectionParams} />);

    await user.click(screen.getByLabelText('Base path'));
    await user.paste('/scim/custom');
    // Turn on the bearer-token requirement, which reveals the token field.
    // The switch input lives inside its <label>, so clicking the label text toggles it.
    await user.click(screen.getByText('Require bearer token'));
    await user.click(screen.getByLabelText('Expected bearer token'));
    await user.paste('secret-token');
    // Turn Enforce filter off so the false override is sent.
    await user.click(screen.getByText('Enforce filter'));

    await user.click(screen.getByRole('button', { name: 'Create provider' }));

    await waitFor(() => expect(calls.length).toBe(1));
    expect(JSON.parse(String(calls[0]?.init?.body))).toEqual({
      basePath: '/scim/custom',
      enforceFilter: false,
      requireBearerToken: true,
      expectedBearerToken: 'secret-token',
    });
  });

  it('renders a humanized error message on failure', async () => {
    const user = userEvent.setup();
    stubFetch(400, 'invalid base path');
    render(<ScimDialog open onClose={() => {}} connectionParams={connectionParams} />);

    await user.click(screen.getByRole('button', { name: 'Create provider' }));

    expect(await screen.findByText(/rejected as invalid/i)).toBeInTheDocument();
  });
});
