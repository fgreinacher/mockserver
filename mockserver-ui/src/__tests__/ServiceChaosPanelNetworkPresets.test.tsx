import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ServiceChaosPanel from '../components/ServiceChaosPanel';

const params = { host: '127.0.0.1', port: '1080', secure: false };

interface PutCall {
  url: string;
  body: Record<string, unknown>;
}

/**
 * Minimal stateless fetch stub: every GET returns an empty-but-valid shape for
 * whichever control-plane endpoint is asked for, and every PUT is recorded.
 */
function stubChaosEndpoints(): { puts: PutCall[] } {
  const puts: PutCall[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const u = String(url);
      const ok = (body: unknown) => ({ ok: true, status: 200, statusText: 'ok', json: async () => body });
      if (init?.method === 'PUT' || init?.method === 'PATCH') {
        puts.push({ url: u, body: JSON.parse(String(init.body)) as Record<string, unknown> });
        return ok({ status: 'ok' });
      }
      if (u.includes('/tcpChaos')) return ok({ hosts: {} });
      if (u.includes('/grpcChaos')) return ok({ services: {} });
      if (u.includes('/grpc/health')) return ok({});
      if (u.includes('/chaosExperiment')) return ok({ status: 'none' });
      return ok({ services: {} });
    }),
  );
  return { puts };
}

/** Expand the TCP-Layer Chaos section (collapsed by default). */
async function expandTcp(user: ReturnType<typeof userEvent.setup>) {
  await waitFor(() => expect(screen.getByRole('button', { name: 'Expand TCP chaos' })).toBeInTheDocument());
  await user.click(screen.getByRole('button', { name: 'Expand TCP chaos' }));
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Network condition preset' })).toBeInTheDocument());
}

/** Choose an option from the network-condition preset picker by its visible text. */
async function choosePreset(user: ReturnType<typeof userEvent.setup>, optionName: RegExp | string) {
  await user.click(screen.getByRole('combobox', { name: 'Network condition preset' }));
  await user.click(await screen.findByRole('option', { name: optionName }));
}

const latencyField = () => screen.getByRole('textbox', { name: 'Latency ms' }) as HTMLInputElement;
const bandwidthField = () => screen.getByRole('textbox', { name: 'Bandwidth B/s' }) as HTMLInputElement;
const slicerField = () => screen.getByRole('textbox', { name: 'Slicer bytes' }) as HTMLInputElement;

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('ServiceChaosPanel network-condition presets', () => {
  it('shows each preset with its concrete number, not just its name', async () => {
    const user = userEvent.setup();
    stubChaosEndpoints();
    render(<ServiceChaosPanel connectionParams={params} />);
    await expandTcp(user);

    await user.click(screen.getByRole('combobox', { name: 'Network condition preset' }));
    // Era-dependent names must carry their values so "3G" is never ambiguous.
    expect(await screen.findByRole('option', { name: 'Slow 3G (throughput) — 50 KB/s inbound · reads >= 50 B' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Slow 3G (latency) — 2000 ms per read' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Fragmented link — 512 B fragments' })).toBeInTheDocument();
  });

  /**
   * The core guarantee, stated as behaviour: TcpChaosHandler applies only the
   * highest-priority fault configured (down > reset_peer > limit_data > slicer >
   * bandwidth > latency) and discards the rest, so no option may advertise two
   * numbers and no option may fill two fault fields.
   */
  it('never advertises or fills a second fault the engine would discard', async () => {
    const user = userEvent.setup();
    stubChaosEndpoints();
    render(<ServiceChaosPanel connectionParams={params} />);
    await expandTcp(user);

    await user.click(screen.getByRole('combobox', { name: 'Network condition preset' }));
    const options = (await screen.findAllByRole('option'))
      .map((o) => o.textContent ?? '')
      .filter((text) => !text.startsWith('Custom'));
    expect(options.length).toBeGreaterThan(0);
    // Close the menu: while it is open MUI aria-hides the rest of the panel, so the
    // combobox and the form fields are unreachable.
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryAllByRole('option')).toHaveLength(0));
    for (const text of options) {
      // A throughput option must not also quote a millisecond figure, and a latency
      // option must not also quote a rate — either would be a dead number.
      const quotesRate = /B\/s/.test(text);
      const quotesMillis = /\d+\s*ms\b/.test(text);
      expect(quotesRate && quotesMillis, `option "${text}" advertises two faults`).toBe(false);
    }

    // And selecting any of them leaves exactly one fault field populated.
    for (const text of options) {
      await user.click(screen.getByRole('combobox', { name: 'Network condition preset' }));
      await user.click(await screen.findByRole('option', { name: text }));
      await waitFor(() => {
        const populated = [latencyField().value, bandwidthField().value, slicerField().value].filter((v) => v !== '');
        expect(populated, `"${text}" populated ${JSON.stringify(populated)}`).toHaveLength(1);
      });
    }
  });

  it('populates the bandwidth field alone for a throughput preset', async () => {
    const user = userEvent.setup();
    stubChaosEndpoints();
    render(<ServiceChaosPanel connectionParams={params} />);
    await expandTcp(user);

    expect(latencyField().value).toBe('');
    expect(bandwidthField().value).toBe('');

    await choosePreset(user, /^Slow 3G \(throughput\)/);

    await waitFor(() => expect(bandwidthField().value).toBe('50000'));
    // Latency must stay empty: the bandwidth branch returns before latency is read.
    expect(latencyField().value).toBe('');
    expect(slicerField().value).toBe('');
  });

  it('populates the latency field alone for a latency preset', async () => {
    const user = userEvent.setup();
    stubChaosEndpoints();
    render(<ServiceChaosPanel connectionParams={params} />);
    await expandTcp(user);

    await choosePreset(user, /^Slow 3G \(latency\)/);

    await waitFor(() => expect(latencyField().value).toBe('2000'));
    expect(bandwidthField().value).toBe('');
    expect(slicerField().value).toBe('');
  });

  it('replaces, never blends, when switching between presets', async () => {
    const user = userEvent.setup();
    stubChaosEndpoints();
    render(<ServiceChaosPanel connectionParams={params} />);
    await expandTcp(user);

    await choosePreset(user, /^Fragmented link/);
    await waitFor(() => expect(slicerField().value).toBe('512'));

    // Switching to a throughput preset must clear the fragmentation value — slicer
    // outranks bandwidth, so leaving it behind would make the bandwidth a no-op.
    await choosePreset(user, /^Slow 3G \(throughput\)/);
    await waitFor(() => expect(bandwidthField().value).toBe('50000'));
    expect(slicerField().value).toBe('');
    expect(latencyField().value).toBe('');
  });

  it('clears the preset-controlled fields when Custom is selected, leaving host and TTL alone', async () => {
    const user = userEvent.setup();
    stubChaosEndpoints();
    render(<ServiceChaosPanel connectionParams={params} />);
    await expandTcp(user);

    await user.type(screen.getByRole('textbox', { name: 'Host' }), 'upstream.svc');
    await user.type(screen.getByRole('textbox', { name: 'TTL ms' }), '60000');
    await choosePreset(user, /^Slow 3G \(throughput\)/);
    await waitFor(() => expect(bandwidthField().value).toBe('50000'));

    await choosePreset(user, 'Custom (no preset)');

    await waitFor(() => expect(bandwidthField().value).toBe(''));
    expect(latencyField().value).toBe('');
    expect((screen.getByRole('textbox', { name: 'Host' }) as HTMLInputElement).value).toBe('upstream.svc');
    expect((screen.getByRole('textbox', { name: 'TTL ms' }) as HTMLInputElement).value).toBe('60000');
  });

  it('registers exactly the one fault the picker advertised', async () => {
    const user = userEvent.setup();
    const { puts } = stubChaosEndpoints();
    render(<ServiceChaosPanel connectionParams={params} />);
    await expandTcp(user);

    await user.type(screen.getByRole('textbox', { name: 'Host' }), 'upstream.svc');
    await choosePreset(user, /^Slow 3G \(throughput\)/);
    await waitFor(() => expect(bandwidthField().value).toBe('50000'));
    await user.click(screen.getByRole('button', { name: 'Register' }));

    await waitFor(() => expect(puts.filter((p) => p.url.includes('/tcpChaos'))).toHaveLength(1));
    // No latencyMs in the payload — it would be silently discarded by the handler.
    expect(puts.find((p) => p.url.includes('/tcpChaos'))!.body).toEqual({
      host: 'upstream.svc',
      chaos: { bandwidthBytesPerSec: 50_000 },
    });
  });

  it('drops back to Custom when a preset field is hand-edited', async () => {
    const user = userEvent.setup();
    stubChaosEndpoints();
    render(<ServiceChaosPanel connectionParams={params} />);
    await expandTcp(user);

    await choosePreset(user, /^Slow 3G \(throughput\)/);
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Network condition preset' })).toHaveTextContent(/^Slow 3G \(throughput\)/));

    await user.type(bandwidthField(), '0');

    // The label must not keep claiming "Slow 3G" once the number no longer is.
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Network condition preset' })).toHaveTextContent('Custom (no preset)'));
    expect(bandwidthField().value).toBe('500000');
  });

  it('drops back to Custom when a superseded second fault is added by hand', async () => {
    const user = userEvent.setup();
    stubChaosEndpoints();
    render(<ServiceChaosPanel connectionParams={params} />);
    await expandTcp(user);

    await choosePreset(user, /^Slow 3G \(throughput\)/);
    await waitFor(() => expect(bandwidthField().value).toBe('50000'));

    // Typing a latency alongside the bandwidth creates a profile whose latency the
    // engine discards; the picker must stop calling it "Slow 3G (throughput)".
    await user.type(latencyField(), '100');

    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Network condition preset' })).toHaveTextContent('Custom (no preset)'));
  });

  it('states the engine caveats that make the numbers meaningful', async () => {
    const user = userEvent.setup();
    stubChaosEndpoints();
    render(<ServiceChaosPanel connectionParams={params} />);
    await expandTcp(user);

    // Priority chain — why each preset is a single fault.
    expect(screen.getByText(/only the highest-priority fault configured is\s+applied/i)).toBeInTheDocument();
    // Direction and charging model — TcpChaosHandler has no write override.
    expect(screen.getByText(/inbound request bytes only/i)).toBeInTheDocument();
    expect(screen.getByText(/per read, not per round trip/i)).toBeInTheDocument();
    // No loss/jitter fault exists.
    expect(screen.getByText(/no packet-loss or jitter fault/i)).toBeInTheDocument();
  });
});
