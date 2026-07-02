import { useState } from 'react';
import Alert from '@mui/material/Alert';
import AlertTitle from '@mui/material/AlertTitle';
import Box from '@mui/material/Box';
import Link from '@mui/material/Link';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import { useDroppedLogEvents } from '../hooks/useDroppedLogEvents';
import { monospaceFontFamily } from '../theme';

/**
 * Dismissible warning shown in the traffic/log views when MockServer's log ring
 * buffer has evicted events. Silent eviction is the most common cause of
 * "verification intermittently fails" and "the dashboard is missing requests",
 * so surfacing the dropped-event count — with the fix (raise `maxLogEntries`) —
 * turns an invisible cliff into an actionable warning.
 *
 * The count comes from the Prometheus metrics endpoint (see
 * {@link useDroppedLogEvents}); the banner stays hidden unless drops are
 * non-zero, so it never appears on a healthy server or when metrics are disabled.
 *
 * Dismissal is remembered at the count seen at dismiss time: the banner
 * re-appears only if *more* events are subsequently evicted, so the user is
 * re-warned about ongoing loss without being nagged about a total they have
 * already acknowledged.
 */
interface LogPressureBannerProps {
  connectionParams: ConnectionParams;
}

export default function LogPressureBanner({ connectionParams }: LogPressureBannerProps) {
  const dropped = useDroppedLogEvents(connectionParams);
  // The drop-count at the moment the user last dismissed the banner (null =
  // never dismissed). Only drops beyond this re-show the warning.
  const [dismissedAt, setDismissedAt] = useState<number | null>(null);

  // A server restart resets the dropped-events counter to 0, so the current
  // count can regress below the value the user dismissed at. When that happens
  // the acknowledged total no longer applies — clear it so fresh drops re-show
  // the banner instead of staying hidden until they exceed the pre-restart
  // total. (React's "adjust state while rendering" pattern — see
  // useDroppedLogEvents.) `effectiveDismissedAt` reflects the reset in this
  // same render so the banner appears immediately.
  let effectiveDismissedAt = dismissedAt;
  if (dismissedAt != null && dropped != null && dropped < dismissedAt) {
    effectiveDismissedAt = null;
    setDismissedAt(null);
  }

  if (dropped == null || dropped <= 0) return null;
  if (effectiveDismissedAt != null && dropped <= effectiveDismissedAt) return null;

  return (
    <Alert
      severity="warning"
      role="alert"
      aria-live="polite"
      onClose={() => setDismissedAt(dropped)}
      sx={{ mx: 1, mt: 1, flexShrink: 0 }}
      data-testid="log-pressure-banner"
    >
      <AlertTitle>Log events evicted</AlertTitle>
      The log ring buffer is full, so the oldest events have been dropped
      {' '}({dropped.toLocaleString()} so far). Verification and the dashboard may be
      missing requests. Increase{' '}
      <Box component="code" sx={{ fontFamily: monospaceFontFamily }}>maxLogEntries</Box>
      {' '}(and{' '}
      <Box component="code" sx={{ fontFamily: monospaceFontFamily }}>ringBufferSize</Box>) so
      events are retained.{' '}
      <Link
        href="/mock_server/performance.html"
        target="_blank"
        rel="noopener"
        color="inherit"
        underline="always"
        data-testid="log-pressure-banner-learn-more"
      >
        Learn more
      </Link>
      .
    </Alert>
  );
}
