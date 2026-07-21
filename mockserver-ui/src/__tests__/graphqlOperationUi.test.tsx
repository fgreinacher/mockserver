import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import TrafficInspector from '../components/TrafficInspector';
import LogEntry from '../components/LogEntry';
import { useDashboardStore } from '../store';
import type { LogEntryValue } from '../types';

function renderTrafficInspector() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <TrafficInspector />
    </ThemeProvider>,
  );
}

function jsonBody(payload: unknown) {
  return { type: 'JSON', json: JSON.stringify(payload) };
}

function graphqlRequest(key: string, payload: unknown) {
  return {
    key,
    value: {
      httpRequest: {
        method: 'POST',
        path: '/graphql',
        headers: [
          { name: 'host', values: ['api.example.com'] },
          { name: 'content-type', values: ['application/json'] },
        ],
        body: jsonBody(payload),
      },
      httpResponse: { statusCode: 200 },
    },
  };
}

function seed(requests: { key: string; value: Record<string, unknown> }[]) {
  useDashboardStore.setState({
    proxiedRequests: requests,
    recordedRequests: [],
    activeExpectations: [],
    trafficSearch: '',
    selectedTrafficKey: null,
  });
}

// ---------------------------------------------------------------------------
// Traffic row badge
// ---------------------------------------------------------------------------

describe('TrafficInspector — GraphQL operation badge', () => {
  it('names the operation on a GraphQL row', () => {
    seed([graphqlRequest('g1', { query: 'query GetUser { user { id } }' })]);
    renderTrafficInspector();

    expect(screen.getByText('GQL GetUser')).toBeInTheDocument();
  });

  it('names an operation supplied only as an operationName member', () => {
    seed([graphqlRequest('g1', {
      query: 'query GetUser { user { id } } mutation CreateOrder { createOrder { id } }',
      operationName: 'CreateOrder',
    })]);
    renderTrafficInspector();

    expect(screen.getByText('GQL CreateOrder')).toBeInTheDocument();
  });

  it('falls back to the operation type for an anonymous operation', () => {
    seed([graphqlRequest('g1', { query: '{ user { id } }' })]);
    renderTrafficInspector();

    expect(screen.getByText('GQL query')).toBeInTheDocument();
  });

  it('shows no badge for ordinary JSON that merely has a query key', () => {
    seed([graphqlRequest('j1', { query: 'SELECT * FROM users', page: 1 })]);
    renderTrafficInspector();

    expect(screen.queryByText(/^GQL /)).not.toBeInTheDocument();
    // The row itself still renders — a non-GraphQL body must never blank it.
    expect(screen.getByText('api.example.com/graphql')).toBeInTheDocument();
  });

  it('renders the row normally when the body is binary/compressed', () => {
    seed([{
      key: 'b1',
      value: {
        httpRequest: {
          method: 'POST',
          path: '/graphql',
          headers: [{ name: 'host', values: ['api.example.com'] }],
          body: { type: 'BINARY', base64Bytes: 'H4sIAAAAAAAA/w==' },
        },
        httpResponse: { statusCode: 200 },
      },
    }]);
    renderTrafficInspector();

    expect(screen.getByText('api.example.com/graphql')).toBeInTheDocument();
    expect(screen.queryByText(/^GQL /)).not.toBeInTheDocument();
  });

  it('filters the traffic list with the operation: operator', async () => {
    const user = userEvent.setup();
    seed([
      graphqlRequest('g1', { query: 'query GetUser { user { id } }' }),
      graphqlRequest('g2', { query: 'mutation CreateOrder { createOrder { id } }' }),
    ]);
    renderTrafficInspector();

    expect(screen.getByText('GQL GetUser')).toBeInTheDocument();
    expect(screen.getByText('GQL CreateOrder')).toBeInTheDocument();

    await user.type(screen.getByRole('textbox', { name: 'Search' }), 'operation:GetUser');

    expect(screen.getByText('GQL GetUser')).toBeInTheDocument();
    expect(screen.queryByText('GQL CreateOrder')).not.toBeInTheDocument();
  });
});

// ---------------------------------------------------------------------------
// Log row badge
// ---------------------------------------------------------------------------

/** A collapsible log entry whose message part carries an HTTP request. */
function requestEntry(request: Record<string, unknown>): LogEntryValue {
  return {
    description: 'received request',
    messageParts: [{ key: 'msg_0', value: request, json: true, argument: true }],
  };
}

describe('LogEntry — GraphQL operation badge', () => {
  it('names the operation on a GraphQL log row', () => {
    render(
      <LogEntry
        collapsible
        entry={requestEntry({
          method: 'POST',
          path: '/graphql',
          body: jsonBody({ query: 'query GetUser { user { id } }' }),
        })}
      />,
    );

    expect(screen.getByText('GQL GetUser')).toBeInTheDocument();
  });

  it('names the operation when the request is nested under httpRequest', () => {
    render(
      <LogEntry
        collapsible
        entry={requestEntry({
          httpRequest: {
            method: 'POST',
            path: '/graphql',
            body: jsonBody({ query: 'mutation CreateOrder { createOrder { id } }' }),
          },
        })}
      />,
    );

    expect(screen.getByText('GQL CreateOrder')).toBeInTheDocument();
  });

  it('shows no badge on a non-GraphQL request row, and still renders it', () => {
    render(
      <LogEntry
        collapsible
        entry={requestEntry({
          method: 'POST',
          path: '/api/search',
          body: jsonBody({ query: 'widgets', page: 2 }),
        })}
      />,
    );

    expect(screen.getByText('received request')).toBeInTheDocument();
    expect(screen.queryByText(/^GQL /)).not.toBeInTheDocument();
  });

  it('shows no badge on a log row that carries no request at all', () => {
    render(<LogEntry entry={{ messageParts: [{ key: 'msg_0', value: 'server started' }] }} />);

    expect(screen.getByText('server started')).toBeInTheDocument();
    expect(screen.queryByText(/^GQL /)).not.toBeInTheDocument();
  });
});
