import { useState, useCallback, useEffect, useMemo } from 'react';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useTheme } from '@mui/material/styles';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import TextField from '@mui/material/TextField';
import Button from '@mui/material/Button';
import HumanErrorAlert from './HumanErrorAlert';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import {
  diffRequests,
  filterIgnoredHeaderDiffs,
  parseIgnoredHeaders,
  DEFAULT_IGNORED_DIFF_HEADERS,
  type DiffResult,
} from '../lib/diff';
import { humanizeError, type HumanError } from '../lib/errorMessage';
import { monospaceFontFamily } from '../theme';
import DiffPanel from './DiffPanel';

const PLACEHOLDER = '{\n  "method": "GET",\n  "path": "/api/users",\n  "headers": { "accept": ["application/json"] }\n}';

/**
 * Compare two captured requests field-by-field via PUT /mockserver/diff and render the result with
 * the shared DiffPanel. Paste each request as JSON (e.g. copied from the Traffic inspector), or open
 * the dialog pre-populated by picking two requests in the Traffic inspector ("Compare" mode).
 *
 * The textareas are seeded once from `initialExpected`/`initialActual` (lazy state initialisers).
 * To re-seed with a different pair of selected requests, the parent passes a changing `key` so this
 * component remounts with the new initial values rather than relying on a sync effect.
 */
export default function DiffRequestsDialog({
  open,
  onClose,
  connectionParams,
  initialExpected,
  initialActual,
}: {
  open: boolean;
  onClose: () => void;
  connectionParams: ConnectionParams;
  initialExpected?: string;
  initialActual?: string;
}) {
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  const [expected, setExpected] = useState(() => initialExpected ?? '');
  const [actual, setActual] = useState(() => initialActual ?? '');
  const [result, setResult] = useState<DiffResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<HumanError | null>(null);
  // Fiddler-style header ignore-list: drop noisy per-request headers (timestamps,
  // correlation ids, trace context, computed content length) from the rendered
  // diff. Applied purely on the client — the diff endpoint has no ignore-fields
  // parameter — so changing it re-filters the existing result without re-querying.
  const [ignoredHeadersInput, setIgnoredHeadersInput] = useState(() => DEFAULT_IGNORED_DIFF_HEADERS.join(', '));
  const ignoredHeaders = useMemo(() => parseIgnoredHeaders(ignoredHeadersInput), [ignoredHeadersInput]);
  const displayedResult = useMemo(
    () => (result ? filterIgnoredHeaderDiffs(result, ignoredHeaders) : null),
    [result, ignoredHeaders],
  );

  const submit = useCallback(async () => {
    let expectedObj: Record<string, unknown>;
    let actualObj: Record<string, unknown>;
    try {
      expectedObj = JSON.parse(expected) as Record<string, unknown>;
    } catch {
      setError({ message: '“Expected” request is not valid JSON.' });
      return;
    }
    try {
      actualObj = JSON.parse(actual) as Record<string, unknown>;
    } catch {
      setError({ message: '“Actual” request is not valid JSON.' });
      return;
    }
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      setResult(await diffRequests(connectionParams, expectedObj, actualObj));
    } catch (e) {
      setError(humanizeError(e));
    } finally {
      setLoading(false);
    }
  }, [connectionParams, expected, actual]);

  // When opened pre-populated from the Traffic inspector ("Compare" mode) with
  // both requests already filled, run the diff immediately so the user sees the
  // result without a second click. The parent remounts this component (via a
  // changing `key`) for each new pair, so a once-on-mount effect is correct.
  // submit() sets loading state synchronously on purpose so the spinner shows at once.
  useEffect(() => {
    if (initialExpected?.trim() && initialActual?.trim()) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      void submit();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth fullScreen={fullScreen} aria-labelledby="diff-requests-title">
      <DialogTitle id="diff-requests-title">Diff Two Requests</DialogTitle>
      <DialogContent>
        {/* Diff result is shown at the top so it is the most visible thing in the
            dialog (the editable request JSON is below for tweaking and re-running). */}
        {(loading || result) && (
          <Box sx={{ mb: 1.5 }}>
            <DiffPanel result={displayedResult} loading={loading} error={null} />
          </Box>
        )}
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          Paste two requests as JSON to compare them field-by-field. Copy a request from the Traffic
          inspector, or hand-author one.
        </Typography>
        <TextField
          label="Ignored Headers"
          value={ignoredHeadersInput}
          onChange={(e) => setIgnoredHeadersInput(e.target.value)}
          placeholder={DEFAULT_IGNORED_DIFF_HEADERS.join(', ')}
          helperText="Comma-separated header names hidden from the diff (timestamps, correlation ids, trace context)."
          size="small"
          fullWidth
          sx={{ mb: 1.5 }}
          slotProps={{ input: { sx: { fontFamily: monospaceFontFamily, fontSize: '0.78rem' } } }}
        />
        <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap' }}>
          <TextField
            label="Expected request (JSON)"
            multiline minRows={8} maxRows={20}
            value={expected} onChange={(e) => setExpected(e.target.value)}
            placeholder={PLACEHOLDER}
            sx={{ flex: 1, minWidth: { xs: '100%', sm: 280 } }}
            slotProps={{ input: { sx: { fontFamily: monospaceFontFamily, fontSize: '0.78rem' } } }}
          />
          <TextField
            label="Actual request (JSON)"
            multiline minRows={8} maxRows={20}
            value={actual} onChange={(e) => setActual(e.target.value)}
            placeholder={PLACEHOLDER}
            sx={{ flex: 1, minWidth: { xs: '100%', sm: 280 } }}
            slotProps={{ input: { sx: { fontFamily: monospaceFontFamily, fontSize: '0.78rem' } } }}
          />
        </Box>
        {error && <HumanErrorAlert error={error} sx={{ mt: 1.5 }} />}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        <Button variant="contained" disabled={loading || !expected.trim() || !actual.trim()} onClick={() => void submit()}>
          Compare
        </Button>
      </DialogActions>
    </Dialog>
  );
}
