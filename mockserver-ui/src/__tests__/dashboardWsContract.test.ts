/// <reference types="vite/client" />
// Cross-boundary contract test for the dashboard WebSocket frame.
//
// The dashboard store/hook (useWebSocket + store.applyMessage) is otherwise tested with
// hand-authored `MockWebSocket` payloads (see useWebSocket.test.ts / store.test.ts). Those
// payloads are invented by the UI test, NOT derived from what the server actually emits, so the
// two sides of the contract can drift apart while both stay green — the #2419 "both sides of a
// contract mocked independently" pattern.
//
// This test instead consumes a GOLDEN frame captured from the real Java
// `DashboardWebSocketHandler` (mockserver/.../DashboardWebSocketHandler.java), pinned by
// DashboardWebSocketFrameContractTest at
// mockserver-ui/src/__tests__/fixtures/dashboard-ws-frame.golden.json. Both the Java test and this
// test read the SAME file, so if the server changes the frame shape (renames a field, drops a
// panel, restructures a value) the Java golden test and these UI assertions break together — the
// contract is enforced across the boundary.
//
// Dynamic tokens (ids, timestamps) are normalised to <ID_n> / <TIMESTAMP> placeholders in the
// golden; their concrete values are opaque to the UI, so the placeholders exercise the store's
// key-based reconciliation exactly as real ids would.

import { describe, it, expect, beforeEach } from 'vitest';
import { useDashboardStore } from '../store';
import { isLogGroup, type WebSocketMessage, type LogEntry, type JsonListItem } from '../types';

// Loaded via import.meta.glob (like fixtureCoverage.test.ts) so it needs no resolveJsonModule and
// works identically under `vitest run` and the `tsc --noEmit` typecheck gate.
const goldenModules = import.meta.glob('./fixtures/dashboard-ws-frame.golden.json', {
  eager: true,
  import: 'default',
}) as Record<string, WebSocketMessage>;

const golden = Object.values(goldenModules)[0]!;

/** A plain (non-group) log entry from the golden — the golden has no correlated groups. */
function logEntries(): LogEntry[] {
  return golden.logMessages.map((message) => {
    expect(isLogGroup(message), `unexpected log group for key ${message.key}`).toBe(false);
    return message as LogEntry;
  });
}

function resetPanels(): void {
  useDashboardStore.setState({
    logMessages: [],
    activeExpectations: [],
    recordedRequests: [],
    proxiedRequests: [],
    error: null,
    errorSource: null,
  });
}

describe('dashboard WebSocket frame contract (server-derived golden)', () => {
  beforeEach(resetPanels);

  it('the golden is the real server frame with all four panels populated', () => {
    expect(golden, 'golden fixture not loaded').toBeTruthy();
    // Every top-level panel key the UI reads must be present in the server frame.
    expect(golden.logMessages.length).toBe(3);
    expect(golden.activeExpectations.length).toBe(1);
    expect(golden.recordedRequests.length).toBe(1);
    expect(golden.proxiedRequests.length).toBe(1);
    // The golden really came from the normalising capture (proves it is server-derived, not a
    // hand-authored payload): ids and timestamps are placeholders.
    expect(golden.activeExpectations[0]!.key).toBe('<ID_3>');
    expect((logEntries()[0]!.value.timestamp)).toBe('<TIMESTAMP>');
  });

  it('every log message carries the fields LogPanel / LogEntry render', () => {
    const entries = logEntries();
    expect(entries).toHaveLength(3);
    for (const entry of entries) {
      expect(typeof entry.key).toBe('string');
      // LogEntry.tsx reads value.messageParts, value.description, value.style, value.timestamp.
      const value = entry.value;
      expect(value, `log entry ${entry.key} has no value`).toBeTruthy();
      expect(Array.isArray(value.messageParts)).toBe(true);
      expect(value.messageParts!.length).toBeGreaterThan(0);
      for (const part of value.messageParts!) {
        expect(typeof part.key).toBe('string');
        expect(part.value).toBeDefined();
      }
      expect(typeof value.description).toBe('string');
      expect(value.style).toBeTruthy();
      expect(value.timestamp).toBeDefined();
    }
    // The received-request log and the recorded-requests row share the server-assigned id (the UI
    // relies on this correlation), and the forwarded log shares its id with the proxied row.
    const receivedLogKey = entries.find((e) => e.value.description?.toString().includes('RECEIVED_REQUEST'))!.key;
    const forwardedLogKey = entries.find((e) => e.value.description?.toString().includes('FORWARDED_REQUEST'))!.key;
    expect(golden.recordedRequests[0]!.key).toBe(receivedLogKey.replace('_log', '_request'));
    expect(golden.proxiedRequests[0]!.key).toBe(forwardedLogKey.replace('_log', '_proxied'));
  });

  it('the active expectation carries key/description/value the ExpectationPanel renders', () => {
    const expectation: JsonListItem = golden.activeExpectations[0]!;
    expect(typeof expectation.key).toBe('string');
    expect(expectation.description).toBeDefined();
    // JsonListItem renders value.httpRequest / value.httpResponse and compares description to the
    // value id — every one of these must be present in the server frame.
    const value = expectation.value;
    expect((value.httpRequest as { path?: string }).path).toBe('/expected-path');
    expect((value.httpResponse as { statusCode?: number }).statusCode).toBe(200);
    expect((value.httpResponse as { body?: string }).body).toBe('expected-body');
    expect(value.id).toBe(expectation.key);
  });

  it('recorded and proxied rows carry the value the request panels render', () => {
    const recorded: JsonListItem = golden.recordedRequests[0]!;
    expect((recorded.value.httpRequest as { path?: string }).path).toBe('/recorded-path');
    expect(recorded.description).toBeDefined();

    const proxied: JsonListItem = golden.proxiedRequests[0]!;
    expect((proxied.value.httpRequest as { path?: string }).path).toBe('/proxied-path');
    expect((proxied.value.httpResponse as { body?: string }).body).toBe('proxied-body');
    expect(proxied.description).toBeDefined();
  });

  it('applyMessage applies the server-derived frame into all four panels', () => {
    useDashboardStore.getState().applyMessage(golden);
    const state = useDashboardStore.getState();

    // Reconciled by the SERVER-assigned keys, in order.
    expect(state.logMessages.map((m) => m.key)).toEqual(golden.logMessages.map((m) => m.key));
    expect(state.activeExpectations).toHaveLength(1);
    expect(state.recordedRequests).toHaveLength(1);
    expect(state.proxiedRequests).toHaveLength(1);

    // The fields the panels read survive the store's reconcile untouched.
    const appliedExpectation = state.activeExpectations[0]! as JsonListItem;
    expect((appliedExpectation.value.httpRequest as { path?: string }).path).toBe('/expected-path');
    const appliedRecorded = state.recordedRequests[0]! as JsonListItem;
    expect((appliedRecorded.value.httpRequest as { path?: string }).path).toBe('/recorded-path');
    const appliedLog = state.logMessages[0]! as LogEntry;
    expect(Array.isArray(appliedLog.value.messageParts)).toBe(true);

    // A clean frame (no `error`) must leave the error banner clear.
    expect(state.error).toBeNull();
  });
});
