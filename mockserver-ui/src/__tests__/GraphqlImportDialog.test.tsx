import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import GraphqlImportDialog from '../components/GraphqlImportDialog';

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

describe('GraphqlImportDialog', () => {
  it('PUTs the pasted SDL and the endpoint path, then reports the created count', async () => {
    const user = userEvent.setup();
    const calls = stubFetch(201, [{ id: 'graphql' }]);
    render(<GraphqlImportDialog open onClose={() => {}} connectionParams={connectionParams} />);

    await user.click(screen.getByLabelText('Endpoint path'));
    await user.paste('/api/graphql');
    await user.click(screen.getByLabelText('GraphQL SDL'));
    await user.paste('type Query { hello: String }');
    await user.click(screen.getByRole('button', { name: 'Import' }));

    await waitFor(() => expect(calls.length).toBe(1));
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/graphql?path=%2Fapi%2Fgraphql');
    expect(calls[0]?.init?.body).toBe('type Query { hello: String }');
    expect(await screen.findByText('Imported 1 expectation.')).toBeInTheDocument();
  });

  it('disables Import until a schema is entered', async () => {
    render(<GraphqlImportDialog open onClose={() => {}} connectionParams={connectionParams} />);
    expect(screen.getByRole('button', { name: 'Import' })).toBeDisabled();
  });

  it('renders the server error message on a bad schema', async () => {
    const user = userEvent.setup();
    stubFetch(400, 'could not parse GraphQL schema');
    render(<GraphqlImportDialog open onClose={() => {}} connectionParams={connectionParams} />);

    await user.click(screen.getByLabelText('GraphQL SDL'));
    await user.paste('not a schema');
    await user.click(screen.getByRole('button', { name: 'Import' }));

    expect(await screen.findByText(/could not parse GraphQL schema/i)).toBeInTheDocument();
  });
});
