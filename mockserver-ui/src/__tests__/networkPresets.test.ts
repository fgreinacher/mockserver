import { describe, it, expect } from 'vitest';
import {
  NETWORK_PRESETS,
  bandwidthThresholdBytes,
  emptyNetworkPresetFields,
  findNetworkPreset,
  formatBytesPerSecond,
  matchNetworkPresetId,
  networkPresetFields,
  summarizeNetworkPreset,
} from '../lib/networkPresets';

describe('networkPresets', () => {
  it('exposes unique, non-empty preset ids, labels and descriptions', () => {
    const ids = NETWORK_PRESETS.map((p) => p.id);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids).not.toContain('');
    for (const preset of NETWORK_PRESETS) {
      expect(preset.label.trim()).not.toBe('');
      expect(preset.description.trim()).not.toBe('');
    }
  });

  it('only sets fields the server TcpChaosProfileDTO actually accepts', () => {
    // Guard against re-introducing jitter / packet-loss, which no chaos DTO has.
    const allowedFaultKeys: Record<string, string> = {
      latency: 'latencyMs',
      bandwidth: 'bandwidthBytesPerSec',
      fragmentation: 'slicerChunkSize',
    };
    for (const preset of NETWORK_PRESETS) {
      expect(Object.keys(preset).sort()).toEqual(['description', 'fault', 'id', 'label']);
      const valueKey = allowedFaultKeys[preset.fault.kind];
      expect(valueKey, `unknown fault kind on "${preset.id}"`).toBeDefined();
      expect(Object.keys(preset.fault).sort()).toEqual(['kind', valueKey].sort());
    }
  });

  // THE priority-chain guard. TcpChaosHandler.channelRead (mockserver-netty
  // .../unification/TcpChaosHandler.java:31-108) evaluates faults FIRST-MATCH-WINS
  // with an early return per branch, in the documented order
  //   down > reset_peer > limit_data > slicer > bandwidth > latency
  // (TcpChaosProfile.java:22-23; chaos_testing.html:3600). Faults do NOT compose, so a
  // preset that populated two of slicer/bandwidth/latency would advertise a number the
  // engine silently discards. If the engine is ever changed to compose faults, this
  // test should fail and be revisited deliberately.
  it('never populates two fault fields, because the engine applies only the first match', () => {
    for (const preset of NETWORK_PRESETS) {
      const fields = networkPresetFields(preset);
      const populated = Object.entries(fields).filter(([, value]) => value !== '');
      expect(
        populated.map(([key]) => key),
        `"${preset.id}" must set exactly one fault field, got ${JSON.stringify(fields)}`,
      ).toHaveLength(1);
    }
  });

  it('advertises in its summary exactly the one fault the engine will apply', () => {
    for (const preset of NETWORK_PRESETS) {
      const fields = networkPresetFields(preset);
      const summary = summarizeNetworkPreset(preset);
      // The populated field's number must appear; the cleared ones cannot contribute.
      if (fields.latencyMs !== '') expect(summary).toContain(`${fields.latencyMs} ms`);
      if (fields.bandwidthBytesPerSec !== '') expect(summary).toMatch(/B\/s|KB\/s|MB\/s/);
      if (fields.slicerChunkSize !== '') expect(summary).toContain(`${fields.slicerChunkSize} B fragments`);
      // A latency-only preset must not imply a throughput number, and vice versa.
      if (fields.latencyMs === '') expect(summary).not.toMatch(/\bms\b/);
      if (fields.bandwidthBytesPerSec === '') expect(summary).not.toMatch(/B\/s/);
    }
  });

  it('pins the documented numbers for the named presets', () => {
    // 3G figures are Chrome DevTools' throttling profiles.
    expect(findNetworkPreset('slow-3g-throughput')?.fault).toEqual({ kind: 'bandwidth', bandwidthBytesPerSec: 50_000 });
    expect(findNetworkPreset('fast-3g-throughput')?.fault).toEqual({ kind: 'bandwidth', bandwidthBytesPerSec: 180_000 });
    expect(findNetworkPreset('slow-3g-latency')?.fault).toEqual({ kind: 'latency', latencyMs: 2_000 });
    expect(findNetworkPreset('fast-3g-latency')?.fault).toEqual({ kind: 'latency', latencyMs: 563 });
    expect(findNetworkPreset('dial-up-throughput')?.fault).toEqual({ kind: 'bandwidth', bandwidthBytesPerSec: 7_000 });
    expect(findNetworkPreset('satellite-latency')?.fault).toEqual({ kind: 'latency', latencyMs: 500 });
    expect(findNetworkPreset('fragmented-link')?.fault).toEqual({ kind: 'fragmentation', slicerChunkSize: 512 });
  });

  it('uses positive, whole numbers for every fault value', () => {
    for (const preset of NETWORK_PRESETS) {
      const value = Object.entries(preset.fault).find(([key]) => key !== 'kind')?.[1] as number;
      expect(Number.isInteger(value), `"${preset.id}" fault value must be a whole number`).toBe(true);
      expect(value).toBeGreaterThan(0);
    }
  });

  it('returns undefined for an unknown preset id', () => {
    expect(findNetworkPreset('does-not-exist')).toBeUndefined();
    expect(findNetworkPreset('')).toBeUndefined();
  });

  describe('bandwidthThresholdBytes', () => {
    // TcpChaosHandler: delayMs = readableBytes * 1000 / bandwidthBytesPerSec, and the
    // branch only returns early when delayMs > 0. Reads below this size are untouched.
    it('is the smallest read a bandwidth ceiling actually delays', () => {
      expect(bandwidthThresholdBytes(7_000)).toBe(7);
      expect(bandwidthThresholdBytes(50_000)).toBe(50);
      expect(bandwidthThresholdBytes(180_000)).toBe(180);
    });

    it('agrees with the handler formula at the boundary', () => {
      for (const bytesPerSec of [7_000, 50_000, 180_000]) {
        const threshold = bandwidthThresholdBytes(bytesPerSec);
        const delayAt = Math.floor((threshold * 1000) / bytesPerSec);
        const delayBelow = Math.floor(((threshold - 1) * 1000) / bytesPerSec);
        expect(delayAt).toBeGreaterThan(0);
        expect(delayBelow).toBe(0);
      }
    });
  });

  describe('formatBytesPerSecond', () => {
    it('uses decimal network units', () => {
      expect(formatBytesPerSecond(900)).toBe('900 B/s');
      expect(formatBytesPerSecond(7_000)).toBe('7 KB/s');
      expect(formatBytesPerSecond(50_000)).toBe('50 KB/s');
      expect(formatBytesPerSecond(1_500_000)).toBe('1.5 MB/s');
      expect(formatBytesPerSecond(2_000_000)).toBe('2 MB/s');
    });
  });

  describe('summarizeNetworkPreset', () => {
    it('states the concrete number behind each era-dependent name', () => {
      expect(summarizeNetworkPreset(findNetworkPreset('slow-3g-throughput')!)).toBe('50 KB/s inbound · reads >= 50 B');
      expect(summarizeNetworkPreset(findNetworkPreset('slow-3g-latency')!)).toBe('2000 ms per read');
      expect(summarizeNetworkPreset(findNetworkPreset('fragmented-link')!)).toBe('512 B fragments');
    });

    it('says "per read" for latency, since no round trip is modelled', () => {
      for (const preset of NETWORK_PRESETS.filter((p) => p.fault.kind === 'latency')) {
        expect(summarizeNetworkPreset(preset)).toContain('per read');
      }
    });
  });

  describe('networkPresetFields', () => {
    it('populates only the preset own fault field and clears the others', () => {
      expect(networkPresetFields(findNetworkPreset('satellite-latency')!)).toEqual({
        latencyMs: '500',
        bandwidthBytesPerSec: '',
        slicerChunkSize: '',
      });
      expect(networkPresetFields(findNetworkPreset('fragmented-link')!)).toEqual({
        latencyMs: '',
        bandwidthBytesPerSec: '',
        slicerChunkSize: '512',
      });
      expect(networkPresetFields(findNetworkPreset('slow-3g-throughput')!)).toEqual({
        latencyMs: '',
        bandwidthBytesPerSec: '50000',
        slicerChunkSize: '',
      });
    });

    it('emptyNetworkPresetFields clears every preset-controlled field', () => {
      expect(emptyNetworkPresetFields()).toEqual({ latencyMs: '', bandwidthBytesPerSec: '', slicerChunkSize: '' });
    });
  });

  describe('matchNetworkPresetId', () => {
    it('round-trips every preset through its own field values', () => {
      for (const preset of NETWORK_PRESETS) {
        expect(matchNetworkPresetId(networkPresetFields(preset))).toBe(preset.id);
      }
    });

    it('is empty for values that match no preset', () => {
      expect(matchNetworkPresetId(emptyNetworkPresetFields())).toBe('');
      expect(matchNetworkPresetId({ latencyMs: '7', bandwidthBytesPerSec: '', slicerChunkSize: '' })).toBe('');
      expect(matchNetworkPresetId({ latencyMs: '', bandwidthBytesPerSec: '60000', slicerChunkSize: '' })).toBe('');
    });

    it('does not claim a preset when a second, superseded fault was added by hand', () => {
      // 50 KB/s plus a hand-typed latency is NOT "Slow 3G (throughput)": the engine
      // would apply the bandwidth branch and discard the latency, so labelling it with
      // the preset name would hide a dead number in the form.
      expect(matchNetworkPresetId({ latencyMs: '100', bandwidthBytesPerSec: '50000', slicerChunkSize: '' })).toBe('');
      // Slicer outranks bandwidth, so this combination is not the throughput preset either.
      expect(matchNetworkPresetId({ latencyMs: '', bandwidthBytesPerSec: '50000', slicerChunkSize: '512' })).toBe('');
    });

    it('tolerates whitespace and equivalent numeric spellings', () => {
      expect(matchNetworkPresetId({ latencyMs: '', bandwidthBytesPerSec: ' 50000 ', slicerChunkSize: '' })).toBe('slow-3g-throughput');
      expect(matchNetworkPresetId({ latencyMs: '500.0', bandwidthBytesPerSec: '', slicerChunkSize: '' })).toBe('satellite-latency');
    });
  });
});
