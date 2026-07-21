/**
 * Named network-condition presets for the TCP-layer chaos form — the MockServer
 * analogue of Charles Proxy's Throttle Presets.
 *
 * A preset is a named value for ONE field the server's `TcpChaosProfileDTO`
 * accepts. Choosing one fills the register form; the user still picks the host
 * and presses Register, so a preset never registers anything by itself and
 * never carries hidden state.
 *
 * WHY EXACTLY ONE FAULT PER PRESET
 * --------------------------------
 * Verified in `mockserver-netty/.../unification/TcpChaosHandler.java` (`channelRead`,
 * lines 31-108): the faults are evaluated FIRST-MATCH-WINS with an early `return`
 * on each branch — they do NOT compose. The documented priority is
 * `down > reset_peer > limit_data > slicer > bandwidth > latency`
 * (`model/TcpChaosProfile.java:22-23`, and the published consumer doc
 * `jekyll-www.mock-server.com/mock_server/chaos_testing.html:3600`).
 *
 * So a profile of `{ latencyMs: 100, bandwidthBytesPerSec: 50000 }` does NOT give
 * 100 ms on a 50 KB/s link — the bandwidth branch returns first and the 100 ms is
 * silently discarded. A preset that displayed both numbers would be confidently
 * wrong, which is worse for someone debugging a timeout than showing nothing.
 * Every preset therefore sets a single fault, and the picker shows that fault's
 * number and nothing else. The union type below makes a two-fault preset
 * unrepresentable rather than merely discouraged.
 *
 * TWO MORE ENGINE FACTS THE NUMBERS DEPEND ON
 * -------------------------------------------
 *   * `TcpChaosHandler` overrides only `channelRead` and `close` — there is no
 *     `write` override — so latency and bandwidth shape INBOUND (client->server)
 *     request bytes only. They do nothing to the response. And `latencyMs` is
 *     charged PER READ, not per round trip; no RTT is modelled.
 *   * Bandwidth is applied as `delayMs = readableBytes * 1000 / bandwidthBytesPerSec`
 *     and only returns early when `delayMs > 0`. A read smaller than
 *     `bandwidthBytesPerSec / 1000` bytes therefore passes through completely
 *     untouched — hence {@link bandwidthThresholdBytes}, surfaced in the summary.
 *
 * NO JITTER, NO PACKET LOSS: neither `TcpChaosProfileDTO` nor `HttpChaosProfileDTO`
 * has such a field, and no TCP fault is probability-gated (every configured fault
 * applies to every byte of every connection). Loss-like behaviour that IS supported
 * lives at the HTTP layer as `dropConnectionProbability` (a true per-request
 * probability) in the HTTP Service Chaos section — a different registry keyed by
 * host. Presets deliberately do not reach across into it, because that would
 * register a second rule the user never asked for.
 *
 * NUMBERS ARE ANCHORED, NOT INVENTED: the 3G figures are Chrome DevTools' network
 * throttling profiles (Slow 3G: 500 kbit/s x 0.8 = 50 000 B/s, 2000 ms; Fast 3G:
 * 1.6 Mbit/s x 0.9 = 180 000 B/s, 562.5 ms). Chrome models latency as an added
 * per-request round trip whereas MockServer adds it per inbound read, so the
 * numbers are borrowed as recognisable anchors, not as an equivalence claim.
 * Names like "3G" mean different things in different eras, so every preset renders
 * its concrete value ({@link summarizeNetworkPreset}) beside its name.
 */

/** The single engine-effective fault a preset configures. */
export type NetworkPresetFault =
  | { kind: 'latency'; latencyMs: number }
  | { kind: 'bandwidth'; bandwidthBytesPerSec: number }
  | { kind: 'fragmentation'; slicerChunkSize: number };

export interface NetworkPreset {
  /** Stable identifier — also the `<Select>` option value. */
  id: string;
  /** Human-readable name shown in the picker. */
  label: string;
  /** What real-world link this approximates, and the caveat that applies to it. */
  description: string;
  /** Exactly one fault — see the file header for why combinations are unrepresentable. */
  fault: NetworkPresetFault;
}

/**
 * The smallest inbound read a bandwidth ceiling actually delays. Reads below this
 * produce `delayMs == 0` in `TcpChaosHandler` and pass through untouched.
 */
export function bandwidthThresholdBytes(bytesPerSec: number): number {
  return Math.ceil(bytesPerSec / 1000);
}

/**
 * The canned presets. Each is one fault, so what the picker shows is what the
 * engine does.
 */
export const NETWORK_PRESETS: readonly NetworkPreset[] = [
  {
    id: 'dial-up-throughput',
    label: 'Dial-up (throughput)',
    description: '56k modem — the slowest realistic upload, for timeout handling',
    fault: { kind: 'bandwidth', bandwidthBytesPerSec: 7_000 },
  },
  {
    id: 'slow-3g-throughput',
    label: 'Slow 3G (throughput)',
    description: "Chrome DevTools' Slow 3G download rate, applied to inbound request bytes",
    fault: { kind: 'bandwidth', bandwidthBytesPerSec: 50_000 },
  },
  {
    id: 'fast-3g-throughput',
    label: 'Fast 3G (throughput)',
    description: "Chrome DevTools' Fast 3G download rate, applied to inbound request bytes",
    fault: { kind: 'bandwidth', bandwidthBytesPerSec: 180_000 },
  },
  {
    id: 'slow-3g-latency',
    label: 'Slow 3G (latency)',
    description: "Chrome DevTools' Slow 3G latency, charged per inbound read rather than per round trip",
    fault: { kind: 'latency', latencyMs: 2_000 },
  },
  {
    id: 'fast-3g-latency',
    label: 'Fast 3G (latency)',
    description: "Chrome DevTools' Fast 3G latency (562.5 ms, rounded), charged per inbound read",
    fault: { kind: 'latency', latencyMs: 563 },
  },
  {
    id: 'satellite-latency',
    label: 'Satellite (latency)',
    description: 'Geostationary-satellite hop — a long one-way delay on every inbound read',
    fault: { kind: 'latency', latencyMs: 500 },
  },
  {
    id: 'fragmented-link',
    label: 'Fragmented link',
    description: 'Congested wifi / small MTU — inbound data arrives in small slices, exercising partial-read handling (fragmentation only; the engine has no packet-loss fault)',
    fault: { kind: 'fragmentation', slicerChunkSize: 512 },
  },
];

/** Format a bytes-per-second rate using decimal network units (1 KB/s = 1000 B/s). */
export function formatBytesPerSecond(bytesPerSec: number): string {
  if (bytesPerSec >= 1_000_000) {
    const mb = bytesPerSec / 1_000_000;
    return `${Number.isInteger(mb) ? mb : mb.toFixed(1)} MB/s`;
  }
  if (bytesPerSec >= 1_000) {
    const kb = bytesPerSec / 1_000;
    return `${Number.isInteger(kb) ? kb : kb.toFixed(1)} KB/s`;
  }
  return `${bytesPerSec} B/s`;
}

/**
 * The preset's one concrete number as a short line, e.g. `50 KB/s inbound · reads
 * >= 50 B`. Only the fault the engine will actually apply is ever shown, and the
 * bandwidth threshold is included because reads below it are untouched.
 */
export function summarizeNetworkPreset(preset: NetworkPreset): string {
  const { fault } = preset;
  switch (fault.kind) {
    case 'latency':
      return `${fault.latencyMs} ms per read`;
    case 'bandwidth':
      return `${formatBytesPerSecond(fault.bandwidthBytesPerSec)} inbound · reads >= ${bandwidthThresholdBytes(fault.bandwidthBytesPerSec)} B`;
    case 'fragmentation':
      return `${fault.slicerChunkSize} B fragments`;
  }
}

/** The subset of the TCP register form a preset controls (form fields are strings). */
export interface NetworkPresetFields {
  latencyMs: string;
  bandwidthBytesPerSec: string;
  slicerChunkSize: string;
}

const NO_PRESET_FIELDS: NetworkPresetFields = {
  latencyMs: '',
  bandwidthBytesPerSec: '',
  slicerChunkSize: '',
};

/**
 * The form field values a preset produces — exactly one populated, the rest
 * cleared. Applying a preset always yields exactly the preset: never a blend with
 * whatever was typed before, and never a second fault that the priority chain
 * would silently discard.
 */
export function networkPresetFields(preset: NetworkPreset): NetworkPresetFields {
  const { fault } = preset;
  switch (fault.kind) {
    case 'latency':
      return { ...NO_PRESET_FIELDS, latencyMs: String(fault.latencyMs) };
    case 'bandwidth':
      return { ...NO_PRESET_FIELDS, bandwidthBytesPerSec: String(fault.bandwidthBytesPerSec) };
    case 'fragmentation':
      return { ...NO_PRESET_FIELDS, slicerChunkSize: String(fault.slicerChunkSize) };
  }
}

/** The cleared form fields used when the picker returns to "Custom". */
export function emptyNetworkPresetFields(): NetworkPresetFields {
  return { ...NO_PRESET_FIELDS };
}

/** Look up a preset by id. */
export function findNetworkPreset(id: string): NetworkPreset | undefined {
  return NETWORK_PRESETS.find((preset) => preset.id === id);
}

/**
 * The id of the preset the given form fields currently express, or `''` when they
 * express none. The picker's selection is derived from the form rather than stored
 * separately, so hand-editing a field after choosing a preset drops the selection
 * back to "Custom" instead of leaving a stale label claiming a value the form no
 * longer holds. Adding a second fault by hand also drops to "Custom", which is
 * correct: the combination is no longer the preset the engine would apply.
 */
export function matchNetworkPresetId(fields: NetworkPresetFields): string {
  const normalise = (value: string): string => {
    const trimmed = value.trim();
    if (trimmed === '') return '';
    const parsed = Number(trimmed);
    return Number.isFinite(parsed) ? String(parsed) : trimmed;
  };
  const actual: NetworkPresetFields = {
    latencyMs: normalise(fields.latencyMs),
    bandwidthBytesPerSec: normalise(fields.bandwidthBytesPerSec),
    slicerChunkSize: normalise(fields.slicerChunkSize),
  };
  const match = NETWORK_PRESETS.find((preset) => {
    const expected = networkPresetFields(preset);
    return expected.latencyMs === actual.latencyMs
      && expected.bandwidthBytesPerSec === actual.bandwidthBytesPerSec
      && expected.slicerChunkSize === actual.slicerChunkSize;
  });
  return match?.id ?? '';
}
