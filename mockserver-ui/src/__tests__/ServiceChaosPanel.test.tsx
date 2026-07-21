import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ServiceChaosPanel from '../components/ServiceChaosPanel';
import { useDashboardStore } from '../store';

const params = { host: '127.0.0.1', port: '1080', secure: false };

interface PutCall {
  body: Record<string, unknown>;
}

/** Expand the HTTP Service Chaos section (collapsed by default). */
async function expandHttp(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Expand HTTP chaos' }));
}

/**
 * Stateful fetch stub: GET returns the current registry snapshot (mutable via
 * the returned `state`), PUT records the call into `puts`.
 *
 * Also serves empty-but-valid shapes for the gRPC-health, TCP-chaos, and
 * gRPC-chaos endpoints so their on-mount fetches succeed without polluting
 * the HTTP service-chaos assertions.
 */
function stubServiceChaos(initial: {
  services: Record<string, unknown>;
  ttlRemainingMillis?: Record<string, number>;
}, grpcHealthData: Record<string, string> = {}, grpcChaosData: { services: Record<string, unknown> } = { services: {} }) {
  const state = { ...initial };
  const puts: PutCall[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const u = String(url);
      if (u.includes('/chaosExperiment')) {
        if (init?.method === 'PUT') {
          puts.push({ body: JSON.parse(String(init.body)) as Record<string, unknown> });
          return { ok: true, status: 200, statusText: 'ok', json: async () => ({ status: 'started' }) };
        }
        if (init?.method === 'DELETE') {
          return { ok: true, status: 200, statusText: 'ok', json: async () => ({ status: 'stopped' }) };
        }
        return { ok: true, status: 200, statusText: 'ok', json: async () => ({ status: 'none' }) };
      }
      if (init?.method === 'PUT') {
        puts.push({ body: JSON.parse(String(init.body)) as Record<string, unknown> });
        return { ok: true, status: 200, statusText: 'ok', json: async () => ({ status: 'ok' }) };
      }
      if (init?.method === 'PATCH') {
        puts.push({ body: JSON.parse(String(init.body)) as Record<string, unknown> });
        return { ok: true, status: 200, statusText: 'ok', json: async () => ({ status: 'ok' }) };
      }
      if (u.includes('/grpc/health')) {
        return { ok: true, status: 200, statusText: 'ok', json: async () => grpcHealthData };
      }
      if (u.includes('/tcpChaos')) {
        return { ok: true, status: 200, statusText: 'ok', json: async () => ({ hosts: {} }) };
      }
      if (u.includes('/grpcChaos')) {
        return { ok: true, status: 200, statusText: 'ok', json: async () => grpcChaosData };
      }
      return { ok: true, status: 200, statusText: 'ok', json: async () => state };
    }),
  );
  return { state, puts };
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('ServiceChaosPanel', () => {
  it('lists registered hosts with profile summary chips', async () => {
    stubServiceChaos({
      services: { 'upstream.svc': { errorStatus: 503, errorProbability: 1.0, latency: { timeUnit: 'MILLISECONDS', value: 200 } } },
    });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('upstream.svc')).toBeInTheDocument());
    expect(screen.getByText('error 503 @ 100%')).toBeInTheDocument();
    expect(screen.getByText('+200ms latency')).toBeInTheDocument();
    expect(screen.getByText('1 active')).toBeInTheDocument();
  });

  it('shows a TTL countdown chip for a TTL-bearing registration', async () => {
    stubServiceChaos({
      services: { 'a.svc': { errorStatus: 500 } },
      ttlRemainingMillis: { 'a.svc': 65_000 },
    });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText(/auto-revert in/)).toBeInTheDocument());
    expect(screen.getByText(/auto-revert in 1m/)).toBeInTheDocument();
  });

  it('does NOT start the 1s TTL tick when no registration has a TTL', async () => {
    const setIntervalSpy = vi.spyOn(globalThis, 'setInterval');
    // A registration with a fault but no entry in ttlRemainingMillis.
    stubServiceChaos({ services: { 'a.svc': { errorStatus: 500 } } });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('a.svc')).toBeInTheDocument());

    // No 1s countdown interval should have been created — there is nothing to
    // count down, so the panel must not re-render every second.
    const oneSecondTicks = setIntervalSpy.mock.calls.filter(([, ms]) => ms === 1000);
    expect(oneSecondTicks).toHaveLength(0);
    // And no countdown chip is shown.
    expect(screen.queryByText(/auto-revert in/)).not.toBeInTheDocument();
  });

  it('starts the 1s TTL tick only when a TTL-bearing registration exists', async () => {
    const setIntervalSpy = vi.spyOn(globalThis, 'setInterval');
    stubServiceChaos({
      services: { 'a.svc': { errorStatus: 500 } },
      ttlRemainingMillis: { 'a.svc': 65_000 },
    });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText(/auto-revert in/)).toBeInTheDocument());

    // Exactly the TTL countdown interval should be created.
    const oneSecondTicks = setIntervalSpy.mock.calls.filter(([, ms]) => ms === 1000);
    expect(oneSecondTicks.length).toBeGreaterThanOrEqual(1);
  });

  it('shows an empty state when nothing is registered', async () => {
    stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());
    // Both HTTP and gRPC panels show "0 active" — assert at least one exists
    const activeChips = screen.getAllByText('0 active');
    expect(activeChips.length).toBeGreaterThanOrEqual(1);
  });

  it('registers a host from the form', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'pay.svc');
    await user.type(screen.getByLabelText('Error status'), '503');
    await user.type(screen.getByLabelText('Error prob (0–1)'), '0.5');
    await user.type(screen.getByLabelText('TTL ms'), '60000');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({
      host: 'pay.svc',
      chaos: { errorStatus: 503, errorProbability: 0.5 },
      ttlMillis: 60000,
    });
  });

  it('rejects a register with a host but no fault set', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'pay.svc');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(await screen.findByText(/Set at least one fault/)).toBeInTheDocument();
    expect(puts).toHaveLength(0);
  });

  it('rejects error probability without an error status', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'pay.svc');
    await user.type(screen.getByLabelText('Error prob (0–1)'), '0.3');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(await screen.findByText(/needs an error status/)).toBeInTheDocument();
    expect(puts).toHaveLength(0);
  });

  it('removes a single host', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({ services: { 'a.svc': { errorStatus: 503 } } });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('a.svc')).toBeInTheDocument());

    await expandHttp(user);
    await user.click(screen.getByRole('button', { name: 'Remove chaos for a.svc' }));
    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({ host: 'a.svc', remove: true });
  });

  it('clears all registrations after confirmation', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({ services: { 'a.svc': { errorStatus: 503 } } });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('a.svc')).toBeInTheDocument());

    // Click Clear HTTP — opens confirmation dialog
    await user.click(screen.getByRole('button', { name: /Clear HTTP/ }));

    // Confirm the destructive action
    await waitFor(() => expect(screen.getByText('Clear all HTTP chaos?')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: /Clear HTTP chaos/i }));

    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({ clear: true });
  });

  it('surfaces a load error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: false, status: 500, statusText: 'Server Error', json: async () => ({}) })),
    );
    render(<ServiceChaosPanel connectionParams={params} />);
    expect(await screen.findByText('Could not load service chaos')).toBeInTheDocument();
  });

  it('renders a remove button scoped to each host', async () => {
    const user = userEvent.setup({ delay: null });
    stubServiceChaos({ services: { 'a.svc': { errorStatus: 503 }, 'b.svc': { errorStatus: 500 } } });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('a.svc')).toBeInTheDocument());
    await expandHttp(user);
    const rowA = screen.getByText('a.svc').closest('div');
    expect(rowA).not.toBeNull();
    expect(within(rowA!).getByText('error 503')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove chaos for a.svc' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove chaos for b.svc' })).toBeInTheDocument();
  });

  // --- Merged gRPC Chaos panel tests ---

  it('renders a merged gRPC Chaos panel with combined active count', async () => {
    stubServiceChaos(
      { services: {} },
      { 'payments.v1.PaymentService': 'NOT_SERVING', 'catalog.v1.CatalogService': 'SERVING' },
      { services: { 'orders.v1.OrderService': { errorStatusCode: 'UNAVAILABLE' } } },
    );
    render(<ServiceChaosPanel connectionParams={params} />);
    // The merged panel should show "3 active" (2 health + 1 fault)
    await waitFor(() => expect(screen.getByText('3 active')).toBeInTheDocument());
    // The panel header should say "gRPC Chaos"
    expect(screen.getByText('gRPC Chaos')).toBeInTheDocument();
  });

  it('renders Health Status and Fault Injection sub-sections inside gRPC Chaos panel', async () => {
    stubServiceChaos({ services: {} });
    const user = userEvent.setup({ delay: null });
    render(<ServiceChaosPanel connectionParams={params} />);
    // Expand the gRPC Chaos panel
    await waitFor(() => expect(screen.getByText('gRPC Chaos')).toBeInTheDocument());
    await user.click(screen.getByText('gRPC Chaos'));
    // Sub-sections should be visible
    expect(await screen.findByText('Health Status')).toBeInTheDocument();
    expect(screen.getByText('Fault Injection')).toBeInTheDocument();
  });

  it('shows GraphQL error chip for HTTP chaos with graphqlErrors', async () => {
    stubServiceChaos({
      services: { 'graphql.svc': { errorStatus: 200, graphqlErrors: true, graphqlErrorCode: 'RATE_LIMITED' } },
    });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('graphql.svc')).toBeInTheDocument());
    expect(screen.getByText('GraphQL error (RATE_LIMITED)')).toBeInTheDocument();
  });

  it('registers HTTP chaos with GraphQL errors enabled', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'graphql.svc');
    await user.type(screen.getByLabelText('Error status'), '200');
    // Enable GraphQL errors
    await user.click(screen.getByLabelText('GraphQL errors'));
    await user.type(screen.getByLabelText('Error message'), 'Rate limit exceeded');
    await user.type(screen.getByLabelText('Error code'), 'RATE_LIMITED');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({
      host: 'graphql.svc',
      chaos: {
        errorStatus: 200,
        graphqlErrors: true,
        graphqlErrorMessage: 'Rate limit exceeded',
        graphqlErrorCode: 'RATE_LIMITED',
        graphqlNullifyData: true,
      },
    });
  });

  it('starts with all three sections collapsed (Expand icons visible)', async () => {
    stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    // All three expand buttons should be present (indicating collapsed state)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Expand HTTP chaos' })).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Expand gRPC chaos' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Expand TCP chaos' })).toBeInTheDocument();
    // None of the "Collapse" variants should be present
    expect(screen.queryByRole('button', { name: 'Collapse HTTP chaos' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Collapse gRPC chaos' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Collapse TCP chaos' })).not.toBeInTheDocument();
    // The HTTP register form's Host field should not be accessible while collapsed
    expect(screen.queryByRole('textbox', { name: 'Host' })).not.toBeInTheDocument();
  });

  it('has consistently named Clear buttons (Clear HTTP / Clear gRPC / Clear TCP)', async () => {
    stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('HTTP Service Chaos')).toBeInTheDocument());
    // All three clear buttons should be in the DOM with consistent naming
    expect(screen.getByRole('button', { name: /Clear HTTP/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Clear gRPC/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Clear TCP/ })).toBeInTheDocument();
  });

  it('shows omit grpc-status chip for gRPC chaos with omitGrpcStatus', async () => {
    stubServiceChaos(
      { services: {} },
      {},
      { services: { 'streaming.v1.StreamService': { errorStatusCode: 'INTERNAL', omitGrpcStatus: true } } },
    );
    const user = userEvent.setup({ delay: null });
    render(<ServiceChaosPanel connectionParams={params} />);
    // Expand gRPC Chaos panel, then Fault Injection sub-section
    await waitFor(() => expect(screen.getByText('gRPC Chaos')).toBeInTheDocument());
    await user.click(screen.getByText('gRPC Chaos'));
    await waitFor(() => expect(screen.getByText('Fault Injection')).toBeInTheDocument());
    await user.click(screen.getByText('Fault Injection'));
    await waitFor(() => expect(screen.getByText('streaming.v1.StreamService')).toBeInTheDocument());
    expect(screen.getByText('omit grpc-status')).toBeInTheDocument();
  });

  it('shows abort-after-messages chip for gRPC chaos with abortAfterMessages', async () => {
    stubServiceChaos(
      { services: {} },
      {},
      { services: { 'bidi.v1.BidiStream': { errorStatusCode: 'UNAVAILABLE', abortAfterMessages: 5 } } },
    );
    const user = userEvent.setup({ delay: null });
    render(<ServiceChaosPanel connectionParams={params} />);
    // Expand gRPC Chaos panel, then Fault Injection sub-section
    await waitFor(() => expect(screen.getByText('gRPC Chaos')).toBeInTheDocument());
    await user.click(screen.getByText('gRPC Chaos'));
    await waitFor(() => expect(screen.getByText('Fault Injection')).toBeInTheDocument());
    await user.click(screen.getByText('Fault Injection'));
    await waitFor(() => expect(screen.getByText('bidi.v1.BidiStream')).toBeInTheDocument());
    expect(screen.getByText('abort after 5 msgs')).toBeInTheDocument();
    // "UNAVAILABLE" appears in both the status code dropdown default and the chip
    const unavailableElements = screen.getAllByText('UNAVAILABLE');
    expect(unavailableElements.length).toBeGreaterThanOrEqual(1);
  });

  // --- Full chaos fault-type controls tests ---

  it('registers HTTP chaos with body corruption controls (truncate + malformed)', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'body.svc');
    await user.type(screen.getByLabelText('Truncate body (0–1)'), '0.5');
    await user.click(screen.getByLabelText('Malformed body'));
    await user.click(screen.getByRole('button', { name: 'Register' }));

    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({
      host: 'body.svc',
      chaos: { truncateBodyAtFraction: 0.5, malformedBody: true },
    });
  });

  it('registers HTTP chaos with slow response (chunk size + delay)', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'slow.svc');
    await user.type(screen.getByLabelText('Slow chunk bytes'), '64');
    await user.type(screen.getByLabelText('Slow chunk delay ms'), '500');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({
      host: 'slow.svc',
      chaos: {
        slowResponseChunkSize: 64,
        slowResponseChunkDelay: { timeUnit: 'MILLISECONDS', value: 500 },
      },
    });
  });

  it('registers HTTP chaos with quota (rate limit) controls', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'quota.svc');
    await user.type(screen.getByLabelText('Quota name'), 'api-quota');
    await user.type(screen.getByLabelText('Quota limit'), '100');
    await user.type(screen.getByLabelText('Quota window ms'), '60000');
    await user.type(screen.getByLabelText('Quota error status'), '429');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({
      host: 'quota.svc',
      chaos: {
        quotaName: 'api-quota',
        quotaLimit: 100,
        quotaWindowMillis: 60000,
        quotaErrorStatus: 429,
      },
    });
  });

  it('registers HTTP chaos with outage window and degradation ramp', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'outage.svc');
    await user.type(screen.getByLabelText('Error status'), '503');
    await user.type(screen.getByLabelText('Outage after ms'), '5000');
    await user.type(screen.getByLabelText('Outage duration ms'), '30000');
    await user.type(screen.getByLabelText('Degradation ramp ms'), '60000');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({
      host: 'outage.svc',
      chaos: {
        errorStatus: 503,
        outageAfterMillis: 5000,
        outageDurationMillis: 30000,
        degradationRampMillis: 60000,
      },
    });
  });

  it('registers HTTP chaos with retry-after header', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'retry.svc');
    await user.type(screen.getByLabelText('Error status'), '429');
    await user.type(screen.getByLabelText('Retry-After'), '120');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({
      host: 'retry.svc',
      chaos: { errorStatus: 429, retryAfter: '120' },
    });
  });

  it('registers a full chaos profile with all fault types', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'full.svc');
    await user.type(screen.getByLabelText('Error status'), '503');
    await user.type(screen.getByLabelText('Error prob (0–1)'), '0.8');
    await user.type(screen.getByLabelText('Retry-After'), '60');
    await user.type(screen.getByLabelText('Drop prob (0–1)'), '0.1');
    await user.type(screen.getByLabelText('Latency ms'), '200');
    await user.type(screen.getByLabelText('Truncate body (0–1)'), '0.75');
    await user.click(screen.getByLabelText('Malformed body'));
    await user.type(screen.getByLabelText('Slow chunk bytes'), '32');
    await user.type(screen.getByLabelText('Slow chunk delay ms'), '250');
    await user.type(screen.getByLabelText('Quota name'), 'test-quota');
    await user.type(screen.getByLabelText('Quota limit'), '50');
    await user.type(screen.getByLabelText('Quota window ms'), '30000');
    await user.type(screen.getByLabelText('Quota error status'), '429');
    await user.type(screen.getByLabelText('Seed'), '42');
    await user.type(screen.getByLabelText('Succeed first'), '3');
    await user.type(screen.getByLabelText('Fail count'), '10');
    await user.type(screen.getByLabelText('Outage after ms'), '1000');
    await user.type(screen.getByLabelText('Outage duration ms'), '5000');
    await user.type(screen.getByLabelText('Degradation ramp ms'), '10000');
    await user.type(screen.getByLabelText('TTL ms'), '120000');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({
      host: 'full.svc',
      chaos: {
        errorStatus: 503,
        errorProbability: 0.8,
        retryAfter: '60',
        dropConnectionProbability: 0.1,
        latency: { timeUnit: 'MILLISECONDS', value: 200 },
        truncateBodyAtFraction: 0.75,
        malformedBody: true,
        slowResponseChunkSize: 32,
        slowResponseChunkDelay: { timeUnit: 'MILLISECONDS', value: 250 },
        quotaName: 'test-quota',
        quotaLimit: 50,
        quotaWindowMillis: 30000,
        quotaErrorStatus: 429,
        seed: 42,
        succeedFirst: 3,
        failRequestCount: 10,
        outageAfterMillis: 1000,
        outageDurationMillis: 5000,
        degradationRampMillis: 10000,
      },
      ttlMillis: 120000,
    });
  }, 40000);

  it('shows summary chips for all new fault types from server', async () => {
    stubServiceChaos({
      services: {
        'all-faults.svc': {
          errorStatus: 503,
          errorProbability: 0.5,
          retryAfter: '120',
          dropConnectionProbability: 0.2,
          latency: { timeUnit: 'MILLISECONDS', value: 200 },
          truncateBodyAtFraction: 0.5,
          malformedBody: true,
          slowResponseChunkSize: 64,
          quotaName: 'test',
          quotaLimit: 100,
          quotaWindowMillis: 60000,
          degradationRampMillis: 30000,
          outageAfterMillis: 5000,
          outageDurationMillis: 10000,
          seed: 42,
        },
      },
    });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('all-faults.svc')).toBeInTheDocument());
    expect(screen.getByText('error 503 @ 50% retry-after=120')).toBeInTheDocument();
    expect(screen.getByText('drop @ 20%')).toBeInTheDocument();
    expect(screen.getByText('+200ms latency')).toBeInTheDocument();
    expect(screen.getByText('truncate to 50%')).toBeInTheDocument();
    expect(screen.getByText('malformed body')).toBeInTheDocument();
    expect(screen.getByText('slow response')).toBeInTheDocument();
    expect(screen.getByText('quota 100/60000ms')).toBeInTheDocument();
    expect(screen.getByText('ramp over 30000ms')).toBeInTheDocument();
    expect(screen.getByText('outage window')).toBeInTheDocument();
    expect(screen.getByText('seed 42')).toBeInTheDocument();
  });

  it('validates retry-after requires an error status', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'bad.svc');
    await user.type(screen.getByLabelText('Retry-After'), '120');
    // Add a valid fault so the "at least one fault" check passes
    await user.type(screen.getByLabelText('Drop prob (0–1)'), '0.5');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(await screen.findByText(/Retry-After needs an error status/)).toBeInTheDocument();
    expect(puts).toHaveLength(0);
  });

  // --- Chaos Experiments tests ---

  it('shows the Experiments section collapsed by default', async () => {
    stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Experiments')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Expand experiments' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Collapse experiments' })).not.toBeInTheDocument();
  });

  it('shows experiment idle chip when no experiment is running', async () => {
    stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Experiments')).toBeInTheDocument());
    expect(screen.getByText('idle')).toBeInTheDocument();
  });

  it('starts a multi-stage experiment with correct payload', async () => {
    const user = userEvent.setup({ delay: null });
    const experimentPuts: Array<{ body: Record<string, unknown> }> = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        const u = String(url);
        if (u.includes('/chaosExperiment') && init?.method === 'PUT') {
          experimentPuts.push({ body: JSON.parse(String(init.body)) as Record<string, unknown> });
          return { ok: true, status: 200, statusText: 'ok', json: async () => ({ status: 'started' }) };
        }
        if (u.includes('/chaosExperiment') && init?.method === 'DELETE') {
          return { ok: true, status: 200, statusText: 'ok', json: async () => ({ status: 'stopped' }) };
        }
        if (u.includes('/chaosExperiment')) {
          return { ok: true, status: 200, statusText: 'ok', json: async () => ({ status: 'none' }) };
        }
        if (u.includes('/grpc/health')) {
          return { ok: true, status: 200, statusText: 'ok', json: async () => ({}) };
        }
        if (u.includes('/tcpChaos')) {
          return { ok: true, status: 200, statusText: 'ok', json: async () => ({ hosts: {} }) };
        }
        if (u.includes('/grpcChaos')) {
          return { ok: true, status: 200, statusText: 'ok', json: async () => ({ services: {} }) };
        }
        return { ok: true, status: 200, statusText: 'ok', json: async () => ({ services: {} }) };
      }),
    );
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Experiments')).toBeInTheDocument());

    // Expand experiments section
    await user.click(screen.getByRole('button', { name: 'Expand experiments' }));
    await waitFor(() => expect(screen.getByLabelText('Experiment name')).toBeInTheDocument());

    // Fill in name
    await user.type(screen.getByLabelText('Experiment name'), 'error-then-latency');

    // Scope queries to the experiment editor section by finding the "Define
    // experiment" paper, then querying within it.
    const defineHeader = screen.getByText('Define experiment');
    const experimentEditor = defineHeader.closest('.MuiPaper-root') as HTMLElement;

    // Fill in stage 1 — use within() to scope to the experiment editor
    const stage1Duration = within(experimentEditor).getAllByLabelText('Duration ms');
    await user.clear(stage1Duration[0]!);
    await user.type(stage1Duration[0]!, '5000');
    const stage1Host = within(experimentEditor).getAllByLabelText(/^Host$/);
    await user.type(stage1Host[0]!, 'api.svc');
    const stage1Error = within(experimentEditor).getAllByLabelText('Error status');
    await user.type(stage1Error[0]!, '503');
    const stage1ErrorProb = within(experimentEditor).getAllByLabelText('Error prob (0-1)');
    await user.type(stage1ErrorProb[0]!, '1.0');

    // Add stage 2
    await user.click(within(experimentEditor).getByRole('button', { name: /Add Stage/ }));
    const durationFields = within(experimentEditor).getAllByLabelText('Duration ms');
    const hostFields = within(experimentEditor).getAllByLabelText(/^Host$/);
    const latencyFields = within(experimentEditor).getAllByLabelText('Latency ms');
    await user.clear(durationFields[1]!);
    await user.type(durationFields[1]!, '10000');
    await user.type(hostFields[1]!, 'api.svc');
    await user.type(latencyFields[1]!, '500');

    // Start experiment
    await user.click(screen.getByRole('button', { name: /Start Experiment/ }));

    await waitFor(() => expect(experimentPuts.length).toBeGreaterThan(0));
    const body = experimentPuts[0]?.body as { name: string; loop: boolean; stages: Array<{ durationMillis: number; profiles: Record<string, unknown> }> };
    expect(body.name).toBe('error-then-latency');
    expect(body.loop).toBe(false);
    expect(body.stages).toHaveLength(2);
    expect(body.stages[0]?.durationMillis).toBe(5000);
    expect(body.stages[0]?.profiles['api.svc']).toEqual({ errorStatus: 503, errorProbability: 1.0 });
    expect(body.stages[1]?.durationMillis).toBe(10000);
    expect(body.stages[1]?.profiles['api.svc']).toEqual({ latency: { timeUnit: 'MILLISECONDS', value: 500 } });
  });

  it('adds and removes stages in the experiment editor', async () => {
    const user = userEvent.setup({ delay: null });
    stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Experiments')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Expand experiments' }));
    await waitFor(() => expect(screen.getByText('Stage 1')).toBeInTheDocument());

    // Initially one stage
    expect(screen.getByText('Stage 1')).toBeInTheDocument();
    expect(screen.queryByText('Stage 2')).not.toBeInTheDocument();

    // Add a stage
    await user.click(screen.getByRole('button', { name: /Add Stage/ }));
    expect(screen.getByText('Stage 2')).toBeInTheDocument();

    // Add another
    await user.click(screen.getByRole('button', { name: /Add Stage/ }));
    expect(screen.getByText('Stage 3')).toBeInTheDocument();

    // Remove stage 2
    await user.click(screen.getByRole('button', { name: 'Remove stage 2' }));
    expect(screen.queryByText('Stage 3')).not.toBeInTheDocument();
    // Should now have stages 1 and 2 (originally 1 and 3)
    expect(screen.getByText('Stage 1')).toBeInTheDocument();
    expect(screen.getByText('Stage 2')).toBeInTheDocument();
  });

  it('shows experiment status with running state and progress', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        const u = String(url);
        if (u.includes('/chaosExperiment') && !init?.method) {
          return {
            ok: true, status: 200, statusText: 'ok',
            json: async () => ({
              name: 'my-experiment',
              status: 'running',
              currentStageIndex: 1,
              totalStages: 3,
              stageElapsedMillis: 4000,
              stageRemainingMillis: 6000,
              loopIteration: 0,
              totalElapsedMillis: 14000,
            }),
          };
        }
        if (u.includes('/grpc/health')) {
          return { ok: true, status: 200, statusText: 'ok', json: async () => ({}) };
        }
        if (u.includes('/tcpChaos')) {
          return { ok: true, status: 200, statusText: 'ok', json: async () => ({ hosts: {} }) };
        }
        if (u.includes('/grpcChaos')) {
          return { ok: true, status: 200, statusText: 'ok', json: async () => ({ services: {} }) };
        }
        return { ok: true, status: 200, statusText: 'ok', json: async () => ({ services: {} }) };
      }),
    );
    const user = userEvent.setup({ delay: null });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Experiments')).toBeInTheDocument());
    // The chip should show "running"
    await waitFor(() => expect(screen.getByText('running')).toBeInTheDocument());

    // Expand to see status detail
    await user.click(screen.getByRole('button', { name: 'Expand experiments' }));
    await waitFor(() => expect(screen.getByText('my-experiment')).toBeInTheDocument());
    expect(screen.getByText('Stage 2/3')).toBeInTheDocument();
    // Stop button should be visible in the header
    expect(screen.getByRole('button', { name: /Stop/ })).toBeInTheDocument();
  });

  it('renders the running experiment stages read-only (host + fault summary + duration)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        const u = String(url);
        if (u.includes('/chaosExperiment') && !init?.method) {
          return {
            ok: true, status: 200, statusText: 'ok',
            json: async () => ({
              name: 'staged-run',
              status: 'running',
              currentStageIndex: 1,
              totalStages: 2,
              stageElapsedMillis: 2000,
              stageRemainingMillis: 8000,
              loopIteration: 0,
              totalElapsedMillis: 7000,
              experiment: {
                name: 'staged-run',
                loop: false,
                stages: [
                  { durationMillis: 5000, profiles: { 'api.svc': { errorStatus: 503, errorProbability: 1.0 } } },
                  { durationMillis: 10000, profiles: { 'api.svc': { latency: { timeUnit: 'MILLISECONDS', value: 500 } } } },
                ],
              },
            }),
          };
        }
        if (u.includes('/grpc/health')) {
          return { ok: true, status: 200, statusText: 'ok', json: async () => ({}) };
        }
        if (u.includes('/tcpChaos')) {
          return { ok: true, status: 200, statusText: 'ok', json: async () => ({ hosts: {} }) };
        }
        if (u.includes('/grpcChaos')) {
          return { ok: true, status: 200, statusText: 'ok', json: async () => ({ services: {} }) };
        }
        return { ok: true, status: 200, statusText: 'ok', json: async () => ({ services: {} }) };
      }),
    );
    const user = userEvent.setup({ delay: null });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Experiments')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: 'Expand experiments' }));

    // Status card should render the read-only stage detail
    await waitFor(() => expect(screen.getByText('staged-run')).toBeInTheDocument());
    // Host appears in the read-only stage rows
    expect(screen.getAllByText('api.svc').length).toBeGreaterThanOrEqual(1);
    // Fault summaries from summarizeChaosProfile
    expect(screen.getByText('error 503 @ 100%')).toBeInTheDocument();
    expect(screen.getByText('+500ms latency')).toBeInTheDocument();
    // Durations from formatDuration
    expect(screen.getByText('5s')).toBeInTheDocument();
    expect(screen.getByText('10s')).toBeInTheDocument();
    // The currently active stage (index 1) is flagged
    expect(screen.getByText('active')).toBeInTheDocument();
  });

  it('loads the running experiment into the editor when Edit & restart is clicked', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        const u = String(url);
        if (u.includes('/chaosExperiment') && !init?.method) {
          return {
            ok: true, status: 200, statusText: 'ok',
            json: async () => ({
              name: 'editable-run',
              status: 'running',
              currentStageIndex: 0,
              totalStages: 2,
              stageElapsedMillis: 1000,
              stageRemainingMillis: 4000,
              loopIteration: 0,
              totalElapsedMillis: 1000,
              experiment: {
                name: 'editable-run',
                loop: true,
                stages: [
                  { durationMillis: 5000, profiles: { 'api.svc': { errorStatus: 503, errorProbability: 1.0 } } },
                  { durationMillis: 10000, profiles: { 'cache.svc': { latency: { timeUnit: 'MILLISECONDS', value: 500 }, dropConnectionProbability: 0.2 } } },
                ],
              },
            }),
          };
        }
        if (u.includes('/grpc/health')) {
          return { ok: true, status: 200, statusText: 'ok', json: async () => ({}) };
        }
        if (u.includes('/tcpChaos')) {
          return { ok: true, status: 200, statusText: 'ok', json: async () => ({ hosts: {} }) };
        }
        if (u.includes('/grpcChaos')) {
          return { ok: true, status: 200, statusText: 'ok', json: async () => ({ services: {} }) };
        }
        return { ok: true, status: 200, statusText: 'ok', json: async () => ({ services: {} }) };
      }),
    );
    const user = userEvent.setup({ delay: null });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Experiments')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: 'Expand experiments' }));
    await waitFor(() => expect(screen.getByText('editable-run')).toBeInTheDocument());

    // Click Edit & restart
    await user.click(screen.getByRole('button', { name: /Edit & restart/i }));

    // The experiment name input is populated from the definition
    await waitFor(() => expect(screen.getByLabelText('Experiment name')).toHaveValue('editable-run'));

    // The stage editor now has two stages populated from the definition
    const defineHeader = screen.getByText('Define experiment');
    const experimentEditor = defineHeader.closest('.MuiPaper-root') as HTMLElement;
    expect(within(experimentEditor).getByText('Stage 1')).toBeInTheDocument();
    expect(within(experimentEditor).getByText('Stage 2')).toBeInTheDocument();

    const durationFields = within(experimentEditor).getAllByLabelText('Duration ms');
    expect(durationFields[0]).toHaveValue('5000');
    expect(durationFields[1]).toHaveValue('10000');

    const hostFields = within(experimentEditor).getAllByLabelText(/^Host$/);
    expect(hostFields[0]).toHaveValue('api.svc');
    expect(hostFields[1]).toHaveValue('cache.svc');

    const errorStatusFields = within(experimentEditor).getAllByLabelText('Error status');
    expect(errorStatusFields[0]).toHaveValue('503');

    const latencyFields = within(experimentEditor).getAllByLabelText('Latency ms');
    expect(latencyFields[1]).toHaveValue('500');

    const dropFields = within(experimentEditor).getAllByLabelText('Drop prob (0-1)');
    expect(dropFields[1]).toHaveValue('0.2');
  });

  it('sends DELETE when Stop is clicked on a running experiment', async () => {
    const deleteCalls: string[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        const u = String(url);
        if (u.includes('/chaosExperiment') && init?.method === 'DELETE') {
          deleteCalls.push(u);
          return { ok: true, status: 200, statusText: 'ok', json: async () => ({ status: 'stopped' }) };
        }
        if (u.includes('/chaosExperiment')) {
          return {
            ok: true, status: 200, statusText: 'ok',
            json: async () => ({ name: 'active', status: 'running', currentStageIndex: 0, totalStages: 1, stageElapsedMillis: 1000, stageRemainingMillis: 4000, loopIteration: 0, totalElapsedMillis: 1000 }),
          };
        }
        if (u.includes('/grpc/health')) {
          return { ok: true, status: 200, statusText: 'ok', json: async () => ({}) };
        }
        if (u.includes('/tcpChaos')) {
          return { ok: true, status: 200, statusText: 'ok', json: async () => ({ hosts: {} }) };
        }
        if (u.includes('/grpcChaos')) {
          return { ok: true, status: 200, statusText: 'ok', json: async () => ({ services: {} }) };
        }
        return { ok: true, status: 200, statusText: 'ok', json: async () => ({ services: {} }) };
      }),
    );
    const user = userEvent.setup({ delay: null });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('running')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /Stop/ }));
    await waitFor(() => expect(deleteCalls.length).toBeGreaterThan(0));
    expect(deleteCalls[0]).toContain('/chaosExperiment');
  });

  // --- SLO verdict / halted_by_slo_breach tests (A1/A2) ---

  /**
   * Stub the experiment-status GET to return a fixed terminal status, with the
   * other chaos on-mount fetches served as empty-but-valid.
   */
  function stubExperimentStatus(status: Record<string, unknown>) {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        const u = String(url);
        if (u.includes('/chaosExperiment') && !init?.method) {
          return { ok: true, status: 200, statusText: 'ok', json: async () => status };
        }
        if (u.includes('/grpc/health')) {
          return { ok: true, status: 200, statusText: 'ok', json: async () => ({}) };
        }
        if (u.includes('/tcpChaos')) {
          return { ok: true, status: 200, statusText: 'ok', json: async () => ({ hosts: {} }) };
        }
        if (u.includes('/grpcChaos')) {
          return { ok: true, status: 200, statusText: 'ok', json: async () => ({ services: {} }) };
        }
        return { ok: true, status: 200, statusText: 'ok', json: async () => ({ services: {} }) };
      }),
    );
  }

  it('renders the terminal SLO verdict chip and per-objective detail when present', async () => {
    stubExperimentStatus({
      name: 'slo-run',
      status: 'completed',
      currentStageIndex: 0,
      totalStages: 1,
      stageElapsedMillis: 5000,
      stageRemainingMillis: 0,
      loopIteration: 0,
      totalElapsedMillis: 5000,
      experimentVerdict: {
        name: 'checkout-slo',
        result: 'FAIL',
        windowFromEpochMillis: 1000,
        windowToEpochMillis: 6000,
        sampleCount: 42,
        objectiveResults: [
          { sli: 'LATENCY_P95', comparator: 'LESS_THAN_OR_EQUAL', threshold: 200, observedValue: 312.5, result: 'FAIL' },
          { sli: 'ERROR_RATE', comparator: 'LESS_THAN', threshold: 0.05, observedValue: 0.01, result: 'PASS' },
        ],
      },
    });
    const user = userEvent.setup({ delay: null });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Experiments')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: 'Expand experiments' }));
    await waitFor(() => expect(screen.getByText('slo-run')).toBeInTheDocument());

    // Verdict header + the overall FAIL chip
    expect(screen.getByText('SLO Verdict')).toBeInTheDocument();
    expect(screen.getByText('checkout-slo')).toBeInTheDocument();
    expect(screen.getByText('42 samples')).toBeInTheDocument();
    // Per-objective observed-vs-threshold detail
    expect(screen.getByText('p95 latency 312.5 ≤ 200')).toBeInTheDocument();
    expect(screen.getByText('error rate 0.01 < 0.05')).toBeInTheDocument();
    // Both PASS and FAIL result chips are present (overall FAIL + per-objective)
    expect(screen.getAllByText('FAIL').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('PASS')).toBeInTheDocument();
  });

  it('renders halted_by_slo_breach as a halted/error status with the FAIL verdict', async () => {
    stubExperimentStatus({
      name: 'breached-run',
      status: 'halted_by_slo_breach',
      currentStageIndex: 0,
      totalStages: 1,
      stageElapsedMillis: 3000,
      stageRemainingMillis: 0,
      loopIteration: 0,
      totalElapsedMillis: 3000,
      experimentVerdict: {
        result: 'FAIL',
        windowFromEpochMillis: 0,
        windowToEpochMillis: 3000,
        sampleCount: 10,
        objectiveResults: [
          { sli: 'ERROR_RATE', comparator: 'LESS_THAN_OR_EQUAL', threshold: 0.1, observedValue: 0.4, result: 'FAIL' },
        ],
      },
    });
    const user = userEvent.setup({ delay: null });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Experiments')).toBeInTheDocument());
    // Header summary chip shows "halted" (not "idle") for a slo-breach termination
    expect(screen.getByText('halted')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Expand experiments' }));
    await waitFor(() => expect(screen.getByText('breached-run')).toBeInTheDocument());

    // The status chip renders the humanized status label with error colour
    const statusChip = screen.getByText('halted by slo breach').closest('.MuiChip-root');
    expect(statusChip).not.toBeNull();
    expect(statusChip!.className).toContain('MuiChip-colorError');
    // And the SLO verdict block is shown
    expect(screen.getByText('SLO Verdict')).toBeInTheDocument();
    expect(screen.getByText('error rate 0.4 ≤ 0.1')).toBeInTheDocument();
  });

  it('renders no SLO verdict block when the status carries no verdict', async () => {
    stubExperimentStatus({
      name: 'no-slo-run',
      status: 'completed',
      currentStageIndex: 0,
      totalStages: 1,
      stageElapsedMillis: 5000,
      stageRemainingMillis: 0,
      loopIteration: 0,
      totalElapsedMillis: 5000,
    });
    const user = userEvent.setup({ delay: null });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Experiments')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: 'Expand experiments' }));
    await waitFor(() => expect(screen.getByText('no-slo-run')).toBeInTheDocument());

    // No verdict → no SLO Verdict block at all
    expect(screen.queryByText('SLO Verdict')).not.toBeInTheDocument();
  });

  // --- Preemption simulation + experiment history tests ---

  function okJson(value: unknown) {
    return { ok: true, status: 200, statusText: 'ok', json: async () => value };
  }

  interface FetchCall {
    url: string;
    method: string;
    body?: Record<string, unknown>;
  }

  /**
   * Stub every chaos-panel endpoint with the preemption state, experiment
   * history, and experiment status the test needs; all other on-mount fetches
   * are served empty-but-valid. Returns the recorded fetch calls for assertion.
   */
  function stubChaos(opts: {
    preemption?: Record<string, unknown>;
    history?: unknown[];
    experimentStatus?: Record<string, unknown>;
  } = {}) {
    const calls: FetchCall[] = [];
    const preemption = opts.preemption ?? { state: 'inactive', inFlight: 0, drainRemainingMillis: 0 };
    const history = opts.history ?? [];
    const experimentStatus = opts.experimentStatus ?? { status: 'none' };
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        const u = String(url);
        const method = init?.method ?? 'GET';
        const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : undefined;
        calls.push({ url: u, method, body });
        if (u.includes('/preemption')) {
          if (method === 'DELETE') return okJson({ state: 'inactive' });
          return okJson(preemption);
        }
        if (u.includes('/chaosExperiment/history')) {
          return okJson({ count: history.length, history });
        }
        if (u.includes('/chaosExperiment')) {
          if (method === 'PUT') return okJson({ status: 'started' });
          if (method === 'DELETE') return okJson({ status: 'stopped' });
          return okJson(experimentStatus);
        }
        if (u.includes('/grpc/health')) return okJson({});
        if (u.includes('/tcpChaos')) return okJson({ hosts: {} });
        if (u.includes('/grpcChaos')) return okJson({ services: {} });
        return okJson({ services: {} });
      }),
    );
    return { calls };
  }

  it('shows the Preemption card collapsed with an inactive badge by default', async () => {
    stubChaos();
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Preemption Simulation')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Expand preemption' })).toBeInTheDocument();
    // The header badge reflects the GET state.
    expect(screen.getByText('inactive')).toBeInTheDocument();
    // Clear is disabled while inactive.
    expect(screen.getByRole('button', { name: /Clear Preemption/ })).toBeDisabled();
  });

  it('renders the live preemption state from the GET response', async () => {
    stubChaos({ preemption: { state: 'draining', inFlight: 3, drainRemainingMillis: 15000, mode: 'both' } });
    const user = userEvent.setup({ delay: null });
    render(<ServiceChaosPanel connectionParams={params} />);
    // Header badge shows the cordoned state.
    await waitFor(() => expect(screen.getByText('draining')).toBeInTheDocument());
    // Clear is enabled while cordoned.
    expect(screen.getByRole('button', { name: /Clear Preemption/ })).toBeEnabled();

    await user.click(screen.getByRole('button', { name: 'Expand preemption' }));
    // Expanded current-state detail: in-flight count, mode and drain remaining.
    await waitFor(() => expect(screen.getByText('3 in-flight')).toBeInTheDocument());
    expect(screen.getByText('mode both')).toBeInTheDocument();
    expect(screen.getByText(/drain remaining/)).toBeInTheDocument();
  });

  it('starts a preemption with the right payload (mode default both + drain + ttl)', async () => {
    const { calls } = stubChaos();
    const user = userEvent.setup({ delay: null });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Preemption Simulation')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Expand preemption' }));
    await waitFor(() => expect(screen.getByText('Start a preemption simulation')).toBeInTheDocument());

    // Scope to the preemption start form ("TTL ms" also exists in the HTTP form).
    const startForm = screen.getByText('Start a preemption simulation').closest('.MuiPaper-root') as HTMLElement;
    await user.type(within(startForm).getByLabelText('Drain ms'), '30000');
    await user.type(within(startForm).getByLabelText('TTL ms'), '60000');
    await user.click(screen.getByRole('button', { name: /Start Preemption/ }));

    await waitFor(() => expect(calls.some((c) => c.url.includes('/preemption') && c.method === 'PUT')).toBe(true));
    const put = calls.find((c) => c.url.includes('/preemption') && c.method === 'PUT');
    expect(put?.body).toEqual({ mode: 'both', drainMillis: 30000, ttlMillis: 60000 });
  });

  it('sends DELETE to /preemption when Clear Preemption is clicked', async () => {
    const { calls } = stubChaos({ preemption: { state: 'draining', inFlight: 1, drainRemainingMillis: 5000, mode: 'reject503' } });
    const user = userEvent.setup({ delay: null });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByRole('button', { name: /Clear Preemption/ })).toBeEnabled());

    await user.click(screen.getByRole('button', { name: /Clear Preemption/ }));
    await waitFor(() => expect(calls.some((c) => c.url.includes('/preemption') && c.method === 'DELETE')).toBe(true));
  });

  it('surfaces the error envelope when starting a preemption fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        const u = String(url);
        const method = init?.method ?? 'GET';
        if (u.includes('/preemption') && method === 'PUT') {
          return { ok: false, status: 400, statusText: 'Bad Request', json: async () => ({ error: 'invalid preemption request: bad mode' }) };
        }
        if (u.includes('/preemption')) return okJson({ state: 'inactive', inFlight: 0, drainRemainingMillis: 0 });
        if (u.includes('/chaosExperiment/history')) return okJson({ count: 0, history: [] });
        if (u.includes('/chaosExperiment')) return okJson({ status: 'none' });
        if (u.includes('/grpc/health')) return okJson({});
        if (u.includes('/tcpChaos')) return okJson({ hosts: {} });
        if (u.includes('/grpcChaos')) return okJson({ services: {} });
        return okJson({ services: {} });
      }),
    );
    const user = userEvent.setup({ delay: null });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Preemption Simulation')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Expand preemption' }));
    await waitFor(() => expect(screen.getByText('Start a preemption simulation')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: /Start Preemption/ }));

    expect(await screen.findByText(/invalid preemption request/)).toBeInTheDocument();
  });

  it('validates drain must be a whole number >= 1 before starting a preemption', async () => {
    const { calls } = stubChaos();
    const user = userEvent.setup({ delay: null });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Preemption Simulation')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Expand preemption' }));
    await waitFor(() => expect(screen.getByText('Start a preemption simulation')).toBeInTheDocument());
    const startForm = screen.getByText('Start a preemption simulation').closest('.MuiPaper-root') as HTMLElement;
    await user.type(within(startForm).getByLabelText('Drain ms'), '0');
    await user.click(screen.getByRole('button', { name: /Start Preemption/ }));

    expect(await screen.findByText(/Drain must be a whole number/)).toBeInTheDocument();
    expect(calls.some((c) => c.url.includes('/preemption') && c.method === 'PUT')).toBe(false);
  });

  it('fetches and renders experiment history when the History section expands', async () => {
    const { calls } = stubChaos({
      history: [
        { name: 'nightly-error-storm', status: 'completed', terminatedAtMillis: 1_700_000_000_000, verdict: { result: 'PASS', windowFromEpochMillis: 0, windowToEpochMillis: 1, sampleCount: 5, objectiveResults: [] } },
        { name: 'latency-probe', status: 'halted_by_slo_breach', terminatedAtMillis: 1_700_000_100_000 },
      ],
    });
    const user = userEvent.setup({ delay: null });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Experiments')).toBeInTheDocument());

    // No history GET until the section is opened.
    expect(calls.some((c) => c.url.includes('/chaosExperiment/history'))).toBe(false);

    await user.click(screen.getByRole('button', { name: 'Expand experiments' }));
    await waitFor(() => expect(screen.getByText('History')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: 'Expand history' }));

    // History GET fires on expand, and the rows render from the fixture.
    await waitFor(() => expect(screen.getByText('nightly-error-storm')).toBeInTheDocument());
    expect(calls.some((c) => c.url.includes('/chaosExperiment/history'))).toBe(true);
    expect(screen.getByText('latency-probe')).toBeInTheDocument();
    expect(screen.getByText('completed')).toBeInTheDocument();
    expect(screen.getByText('halted by slo breach')).toBeInTheDocument();
    // The verdict chip from the first entry.
    expect(screen.getByText('PASS')).toBeInTheDocument();
  });

  it('refetches history when the Refresh history button is clicked', async () => {
    const { calls } = stubChaos({
      history: [{ name: 'run-a', status: 'stopped', terminatedAtMillis: 1_700_000_000_000 }],
    });
    const user = userEvent.setup({ delay: null });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Experiments')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Expand experiments' }));
    await user.click(screen.getByRole('button', { name: 'Expand history' }));
    await waitFor(() => expect(screen.getByText('run-a')).toBeInTheDocument());

    const before = calls.filter((c) => c.url.includes('/chaosExperiment/history')).length;
    await user.click(screen.getByRole('button', { name: 'Refresh history' }));
    await waitFor(() => expect(calls.filter((c) => c.url.includes('/chaosExperiment/history')).length).toBeGreaterThan(before));
  });

  it('shows an empty state when there is no experiment history', async () => {
    stubChaos({ history: [] });
    const user = userEvent.setup({ delay: null });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Experiments')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Expand experiments' }));
    await user.click(screen.getByRole('button', { name: 'Expand history' }));
    await waitFor(() => expect(screen.getByText('No past experiment runs.')).toBeInTheDocument());
  });
});

describe('ServiceChaosPanel — Quick Chaos strip', () => {
  it('enabling Quick Chaos registers exactly the tagged rule for the target host', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await screen.findByText('Quick Chaos');

    await user.type(screen.getByLabelText('Target Host'), 'api.example.com');
    // default modes = ['errors'], default percent = 10
    await user.click(screen.getByRole('switch', { name: 'Enable Chaos' }));

    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({
      host: 'api.example.com',
      chaos: { errorStatus: 500, errorProbability: 0.1 },
    });
  });

  it('does not PUT on load but reflects an existing tagged rule as enabled', async () => {
    const { puts } = stubServiceChaos({
      services: { 'api.example.com': { errorStatus: 500, errorProbability: 0.2, dropConnectionProbability: 0.2 } },
    });
    render(<ServiceChaosPanel connectionParams={params} />);
    await screen.findByText('Quick Chaos');

    // The switch reflects server state...
    await waitFor(() =>
      expect(screen.getByRole('switch', { name: 'Enable Chaos' })).toBeChecked(),
    );
    // ...the host is shown and locked...
    expect(screen.getByLabelText('Target Host')).toHaveValue('api.example.com');
    expect(screen.getByLabelText('Target Host')).toBeDisabled();
    // ...and no PUT was issued just from deriving state.
    expect(puts.length).toBe(0);
  });

  it('turning Quick Chaos off deletes exactly its tagged host', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({
      services: { 'api.example.com': { errorStatus: 500, errorProbability: 0.2 } },
    });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() =>
      expect(screen.getByRole('switch', { name: 'Enable Chaos' })).toBeChecked(),
    );

    await user.click(screen.getByRole('switch', { name: 'Enable Chaos' }));

    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({ host: 'api.example.com', remove: true });
  });

  it('adding a fault mode while enabled re-registers the host with both faults', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({
      services: { 'api.example.com': { errorStatus: 500, errorProbability: 0.2 } },
    });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() =>
      expect(screen.getByRole('switch', { name: 'Enable Chaos' })).toBeChecked(),
    );

    await user.click(screen.getByRole('button', { name: 'Connection Reset' }));

    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({
      host: 'api.example.com',
      chaos: { errorStatus: 500, errorProbability: 0.2, dropConnectionProbability: 0.2 },
    });
  });

  it('the slider updates the tagged rule with a new percentage', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({
      services: { 'api.example.com': { errorStatus: 500, errorProbability: 0.2 } },
    });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() =>
      expect(screen.getByRole('switch', { name: 'Enable Chaos' })).toBeChecked(),
    );

    const slider = screen.getByRole('slider', { name: 'Percentage of requests affected' });
    slider.focus();
    await user.keyboard('{ArrowRight}'); // 20 -> 21

    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({
      host: 'api.example.com',
      chaos: { errorStatus: 500, errorProbability: 0.21 },
    });
  });
});

describe('ServiceChaosPanel — launchpad chaos draft consumption', () => {
  afterEach(() => {
    useDashboardStore.setState({ pendingChaosDraft: null });
  });

  it('prefills the HTTP register host, expands the card, and clears the draft', async () => {
    stubServiceChaos({ services: {} });
    useDashboardStore.setState({ pendingChaosDraft: { host: 'api.example.com', path: '/api/orders' } });
    render(<ServiceChaosPanel connectionParams={params} />);

    // The HTTP Service Chaos card is expanded (its register form is visible) and
    // the host scope is prefilled from the draft.
    await waitFor(() => expect(screen.getByText('Register chaos for a host')).toBeInTheDocument());
    expect(screen.getByDisplayValue('api.example.com')).toBeInTheDocument();
    // The one-shot draft is consumed exactly once.
    expect(useDashboardStore.getState().pendingChaosDraft).toBeNull();
  });

  it('expands the card even when the draft carries only a path (no host)', async () => {
    stubServiceChaos({ services: {} });
    useDashboardStore.setState({ pendingChaosDraft: { path: '/api/orders' } });
    render(<ServiceChaosPanel connectionParams={params} />);

    await waitFor(() => expect(screen.getByText('Register chaos for a host')).toBeInTheDocument());
    expect(useDashboardStore.getState().pendingChaosDraft).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// Exact-host targeting
// ---------------------------------------------------------------------------
//
// Chaos is not a filter. The server keys ServiceChaosRegistry / TcpChaosRegistry by
// host in a plain map and looks the request's Host header up by exact (lower-cased,
// port-stripped) key — there is no pattern matching. A wildcard accepted here would
// be stored as the literal key `*.example.com` and silently never fire, which is why
// this panel deliberately does not use the shared filter-DSL search field (whose
// `host:` operator is a glob advertised as `host:*.example.com`).

describe('ServiceChaosPanel — exact-host targeting', () => {
  it('refuses a wildcard host on the HTTP register form', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), '*.example.com');
    await user.type(screen.getByLabelText('Error status'), '503');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(await screen.findByText(/wildcards such as \*\.example\.com are not supported/)).toBeInTheDocument();
    expect(puts).toHaveLength(0);
  });

  it('refuses a pasted host: search operator, pointing at the bare host', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'host:api.example.com');
    await user.type(screen.getByLabelText('Error status'), '503');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(await screen.findByText(/the host: search operator is not used here/)).toBeInTheDocument();
    expect(puts).toHaveLength(0);
  });

  it('still registers an ordinary host unchanged', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'pay.svc:8080');
    await user.type(screen.getByLabelText('Error status'), '503');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({ host: 'pay.svc:8080', chaos: { errorStatus: 503 } });
  });

  it('refuses a wildcard host on the Quick Chaos strip, which uses the same registry', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await screen.findByText('Quick Chaos');

    await user.type(screen.getByLabelText('Target Host'), '*.example.com');
    await user.click(screen.getByRole('switch', { name: 'Enable Chaos' }));

    expect(await screen.findByText(/wildcards such as \*\.example\.com are not supported/)).toBeInTheDocument();
    expect(puts).toHaveLength(0);
  });

  it('refuses a wildcard stage host in the experiment editor, blocking the run', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Experiments')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Expand experiments' }));
    await waitFor(() => expect(screen.getByLabelText('Experiment name')).toBeInTheDocument());
    await user.type(screen.getByLabelText('Experiment name'), 'wildcard-run');

    const editor = screen.getByText('Define experiment').closest('.MuiPaper-root') as HTMLElement;
    await user.type(within(editor).getAllByLabelText(/^Host$/)[0]!, '*.example.com');
    await user.type(within(editor).getAllByLabelText('Error status')[0]!, '503');

    await user.click(screen.getByRole('button', { name: /Start Experiment/ }));

    // A stage host is applied verbatim as a registry key, so a wildcard would run an
    // experiment that faults nothing while still reporting progress and a verdict.
    expect(await screen.findByText(/Stage 1: Chaos targets one exact host/)).toBeInTheDocument();
    expect(puts).toHaveLength(0);
  });

  it('refuses a wildcard stage host on Save Profile too, not just Start', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Experiments')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Expand experiments' }));
    await waitFor(() => expect(screen.getByLabelText('Experiment name')).toBeInTheDocument());
    await user.type(screen.getByLabelText('Experiment name'), 'wildcard-profile');

    const editor = screen.getByText('Define experiment').closest('.MuiPaper-root') as HTMLElement;
    await user.type(within(editor).getAllByLabelText(/^Host$/)[0]!, '*.example.com');
    await user.type(within(editor).getAllByLabelText('Error status')[0]!, '503');

    await user.click(screen.getByRole('button', { name: /Save as Profile/ }));

    expect(await screen.findByText(/Stage 1: Chaos targets one exact host/)).toBeInTheDocument();
    expect(puts).toHaveLength(0);
  });

  it('still starts an experiment whose stage hosts are ordinary', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Experiments')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Expand experiments' }));
    await waitFor(() => expect(screen.getByLabelText('Experiment name')).toBeInTheDocument());
    await user.type(screen.getByLabelText('Experiment name'), 'ordinary-run');

    const editor = screen.getByText('Define experiment').closest('.MuiPaper-root') as HTMLElement;
    await user.type(within(editor).getAllByLabelText(/^Host$/)[0]!, 'api.svc');
    await user.type(within(editor).getAllByLabelText('Error status')[0]!, '503');

    await user.click(screen.getByRole('button', { name: /Start Experiment/ }));

    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    const body = puts[0]?.body as { stages: Array<{ profiles: Record<string, unknown> }> };
    expect(body.stages[0]?.profiles['api.svc']).toEqual({ errorStatus: 503 });
  });

  it('refuses a scheme-prefixed host, which is also a dead registry key', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'https://api.example.com');
    await user.type(screen.getByLabelText('Error status'), '503');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(await screen.findByText(/a URL scheme such as https:\/\/ is not part of the host key/)).toBeInTheDocument();
    expect(puts).toHaveLength(0);
  });

  it('refuses a path-suffixed host, which normalizeHost never produces', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    // Copied out of a URL bar with the scheme already trimmed. normalizeHost
    // splits host from port but never strips a path, so this key is unhittable.
    await user.type(screen.getByLabelText('Host'), 'api.example.com/v1/orders');
    await user.type(screen.getByLabelText('Error status'), '503');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(await screen.findByText(/a path is not part of the host key/)).toBeInTheDocument();
    expect(puts).toHaveLength(0);
  });

  it('refuses a wildcard host on the TCP register form', async () => {
    const user = userEvent.setup({ delay: null });
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Expand TCP chaos' })).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Expand TCP chaos' }));
    // The collapsed HTTP form keeps its own "Host" field mounted, so scope the
    // lookup to the TCP register card.
    const tcpForm = (await screen.findByText('Register TCP chaos for a host')).parentElement!;
    await user.type(within(tcpForm).getByLabelText('Host'), '*.example.com');
    await user.click(within(tcpForm).getByRole('button', { name: 'Register' }));

    expect(await screen.findByText(/wildcards such as \*\.example\.com are not supported/)).toBeInTheDocument();
    expect(puts).toHaveLength(0);
  });
});
