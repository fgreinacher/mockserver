import { useCallback } from 'react';
import { useDashboardStore } from '../store';
import type { ConnectionParams } from './useConnectionParams';
import type { DebugMismatchResult } from '../types';
import { buildBaseUrl } from '../lib/mcpClient';

export function useDebugMismatch(params: ConnectionParams) {
  const openDebugMismatch = useDashboardStore((s) => s.openDebugMismatch);
  const setLoading = useDashboardStore((s) => s.setDebugMismatchLoading);
  const setError = useDashboardStore((s) => s.setDebugMismatchError);

  const debugMismatch = useCallback(
    async (request: Record<string, unknown>) => {
      setLoading(true);
      useDashboardStore.setState({ debugMismatchOpen: true });
      const base = buildBaseUrl(params);
      try {
        const response = await fetch(`${base}/mockserver/debugMismatch`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(request),
        });
        // Read the body as text first: a non-2xx response (e.g. 404/500) may
        // carry an empty or HTML body, and calling response.json() on that
        // throws — which would otherwise be caught below and misreported as a
        // connection failure even though the server clearly responded.
        const text = await response.text();
        let data: (DebugMismatchResult & { error?: string }) | null = null;
        if (text) {
          try {
            data = JSON.parse(text) as DebugMismatchResult & { error?: string };
          } catch {
            data = null;
          }
        }
        if (!response.ok) {
          const reason = data?.error?.trim() || text.trim();
          setError(
            reason
              ? `Request failed (${response.status}): ${reason}`
              : `Request failed: ${response.status}`,
          );
          return;
        }
        if (!data) {
          setError('Received an unexpected response from MockServer');
          return;
        }
        // Attach the original request so the dialog can offer "Create Expectation"
        data.unmatchedRequest = request;
        openDebugMismatch(data);
      } catch {
        setError('Failed to connect to MockServer');
      }
    },
    [params, openDebugMismatch, setLoading, setError],
  );

  return { debugMismatch };
}
