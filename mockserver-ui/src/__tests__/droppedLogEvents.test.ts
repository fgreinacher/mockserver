import { describe, it, expect } from 'vitest';
import { parseDroppedLogEvents } from '../lib/droppedLogEvents';

describe('parseDroppedLogEvents', () => {
  it('returns the dropped-log-events counter value (real _total wire name)', () => {
    expect(parseDroppedLogEvents('mock_server_dropped_log_events_total 42.0')).toBe(42);
  });

  it('returns 0 when the counter is absent (metrics enabled, no drops)', () => {
    expect(parseDroppedLogEvents('requests_received_count 5.0\nother_metric 1.0')).toBe(0);
  });

  it('returns 0 for empty / non-metric input', () => {
    expect(parseDroppedLogEvents('')).toBe(0);
  });

  it('ignores HELP/TYPE comment lines around the counter', () => {
    const text = [
      '# HELP mock_server_dropped_log_events_total events dropped when the ring buffer is full',
      '# TYPE mock_server_dropped_log_events_total counter',
      'mock_server_dropped_log_events_total 3.0',
    ].join('\n');
    expect(parseDroppedLogEvents(text)).toBe(3);
  });

  // Pins the `_total` suffix contract: the server's Prometheus client only ever
  // emits the `_total` form, never the bare registered name. If the metric
  // constant regresses to the bare name this reads the wrong sample and fails.
  it('reads the _total sample, not the bare name, when both are present', () => {
    const text = [
      '# TYPE mock_server_dropped_log_events_total counter',
      'mock_server_dropped_log_events 7.0',
      'mock_server_dropped_log_events_total 99.0',
    ].join('\n');
    expect(parseDroppedLogEvents(text)).toBe(99);
  });
});
