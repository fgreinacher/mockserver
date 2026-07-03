import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import Button from '@mui/material/Button';
import TextField from '@mui/material/TextField';
import Box from '@mui/material/Box';
import Alert from '@mui/material/Alert';
import Typography from '@mui/material/Typography';
import LinearProgress from '@mui/material/LinearProgress';
import RepeatIcon from '@mui/icons-material/Repeat';
import { replayRequests } from '../lib/replay';
import {
  runRepeatReplay,
  MAX_REPEAT_CONCURRENCY,
  MAX_REPEAT_ITERATIONS,
  type RepeatProgress,
  type RepeatSummary,
} from '../lib/repeatReplay';
import { humanizeError, type HumanError } from '../lib/errorMessage';
import HumanErrorAlert from './HumanErrorAlert';
import type { JsonListItem } from '../types';
import type { ConnectionParams } from '../hooks/useConnectionParams';

// ---------------------------------------------------------------------------
// RepeatAdvancedDialog — Charles-style "Repeat Advanced".
//
// Re-sends a captured request N times with a bounded concurrency and an
// inter-request delay. MockServer's replay endpoint is single-shot, so the
// fan-out is driven client-side by `runRepeatReplay` (a bounded worker pool);
// each iteration only fans out a control-plane call, and the request itself is
// re-issued by the server. Cancellable mid-run via an AbortController.
// ---------------------------------------------------------------------------

interface RepeatAdvancedDialogProps {
  open: boolean;
  onClose: () => void;
  item: JsonListItem;
  connectionParams: ConnectionParams;
  /**
   * Called when the user asks to view the resulting traffic — passes the
   * request's path so the caller can seed the Traffic search filter.
   */
  onViewResults?: (path: string) => void;
}

/** Parse a numeric field, clamping to `[min, max]`; falls back to `fallback`. */
function clampField(raw: string, min: number, max: number, fallback: number): number {
  const n = parseInt(raw, 10);
  if (isNaN(n)) return fallback;
  return Math.max(min, Math.min(n, max));
}

export default function RepeatAdvancedDialog({
  open,
  onClose,
  item,
  connectionParams,
  onViewResults,
}: RepeatAdvancedDialogProps) {
  const [iterations, setIterations] = useState('10');
  const [concurrency, setConcurrency] = useState('1');
  const [delayMs, setDelayMs] = useState('0');
  const [running, setRunning] = useState(false);
  const [progress, setProgress] = useState<RepeatProgress | null>(null);
  const [summary, setSummary] = useState<RepeatSummary | null>(null);
  const [error, setError] = useState<HumanError | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  // Abort on any unmount — the dialog can unmount involuntarily if the selected
  // entry is evicted from the traffic list mid-run (possibly by this run's own
  // generated traffic), and the run must not continue with no cancel affordance.
  useEffect(() => () => abortRef.current?.abort(), []);

  const httpRequest = useMemo(() => {
    const req = item.value['httpRequest'];
    return req && typeof req === 'object' && !Array.isArray(req)
      ? (req as Record<string, unknown>)
      : {};
  }, [item.value]);

  const method = typeof httpRequest['method'] === 'string' ? httpRequest['method'] : 'GET';
  const path = typeof httpRequest['path'] === 'string' ? httpRequest['path'] : '';
  const isNonGet = method !== 'GET';

  const iterationsError = iterations.trim() !== '' && (isNaN(parseInt(iterations, 10)) || parseInt(iterations, 10) < 1);
  const concurrencyError = concurrency.trim() !== '' && (isNaN(parseInt(concurrency, 10)) || parseInt(concurrency, 10) < 1);
  const delayError = delayMs.trim() !== '' && (isNaN(parseInt(delayMs, 10)) || parseInt(delayMs, 10) < 0);
  const inputsInvalid = iterationsError || concurrencyError || delayError;

  const handleStart = useCallback(async () => {
    const total = clampField(iterations, 1, MAX_REPEAT_ITERATIONS, 10);
    const conc = clampField(concurrency, 1, MAX_REPEAT_CONCURRENCY, 1);
    const delay = clampField(delayMs, 0, Number.MAX_SAFE_INTEGER, 0);

    const controller = new AbortController();
    abortRef.current = controller;
    setRunning(true);
    setError(null);
    setSummary(null);
    setProgress({ done: 0, succeeded: 0, failed: 0, total });

    try {
      const result = await runRepeatReplay({
        iterations: total,
        concurrency: conc,
        delayMs: delay,
        signal: controller.signal,
        send: () => replayRequests(connectionParams, httpRequest),
        onProgress: setProgress,
      });
      setSummary(result);
    } catch (err) {
      // runRepeatReplay never rejects on per-call failures (they are counted),
      // so this only fires on an unexpected driver error.
      setError(humanizeError(err));
    } finally {
      setRunning(false);
      abortRef.current = null;
    }
  }, [iterations, concurrency, delayMs, connectionParams, httpRequest]);

  const handleCancel = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  const handleClose = useCallback(() => {
    // Abort any in-flight run so no further calls are issued after the dialog closes.
    abortRef.current?.abort();
    onClose();
  }, [onClose]);

  const pct = progress && progress.total > 0 ? (progress.done / progress.total) * 100 : 0;

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="sm" fullWidth>
      <DialogTitle sx={{ fontSize: '0.95rem' }}>Repeat Request</DialogTitle>
      <DialogContent dividers>
        <Alert severity="warning" sx={{ mb: 1.5 }}>
          This re-sends <strong>{method} {path || 'the request'}</strong> to its original upstream
          target, once per iteration.
          {isNonGet && ` The method is ${method}, which may mutate state or incur costs (e.g. LLM API charges).`}
          {' '}A high iteration count amplifies any cost or side effect.
        </Alert>

        <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap', mb: 1 }}>
          <TextField
            size="small"
            label="Iterations"
            type="number"
            value={iterations}
            disabled={running}
            error={iterationsError}
            helperText={iterationsError ? `1 – ${MAX_REPEAT_ITERATIONS}` : `Max ${MAX_REPEAT_ITERATIONS}`}
            onChange={(e) => setIterations(e.target.value)}
            sx={{ width: 130 }}
            slotProps={{ htmlInput: { min: 1, max: MAX_REPEAT_ITERATIONS } }}
          />
          <TextField
            size="small"
            label="Concurrency"
            type="number"
            value={concurrency}
            disabled={running}
            error={concurrencyError}
            helperText={concurrencyError ? `1 – ${MAX_REPEAT_CONCURRENCY}` : `Max ${MAX_REPEAT_CONCURRENCY}`}
            onChange={(e) => setConcurrency(e.target.value)}
            sx={{ width: 130 }}
            slotProps={{ htmlInput: { min: 1, max: MAX_REPEAT_CONCURRENCY } }}
          />
          <TextField
            size="small"
            label="Delay (ms)"
            type="number"
            value={delayMs}
            disabled={running}
            error={delayError}
            helperText={delayError ? '≥ 0' : 'Between requests'}
            onChange={(e) => setDelayMs(e.target.value)}
            sx={{ width: 130 }}
            slotProps={{ htmlInput: { min: 0 } }}
          />
        </Box>

        {error && <HumanErrorAlert error={error} sx={{ mb: 1 }} />}

        {progress && (running || !summary) && (
          <Box sx={{ mt: 1.5 }}>
            <LinearProgress variant="determinate" value={pct} sx={{ mb: 0.5 }} />
            <Typography variant="caption" color="text.secondary">
              {`${progress.done} / ${progress.total} sent${progress.failed > 0 ? ` — ${progress.failed} failed` : ''}`}
            </Typography>
          </Box>
        )}

        {summary && (
          <Alert
            severity={summary.failed > 0 ? 'warning' : 'success'}
            sx={{ mt: 1.5 }}
            action={
              onViewResults && path ? (
                <Button
                  size="small"
                  color="inherit"
                  onClick={() => {
                    onViewResults(path);
                    onClose();
                  }}
                >
                  View Them
                </Button>
              ) : undefined
            }
          >
            {`${summary.aborted ? 'Cancelled' : 'Done'} — ${summary.succeeded} succeeded, ${summary.failed} failed${summary.aborted ? ` (of ${summary.total})` : ''}. New traffic entries appear in the list.`}
          </Alert>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose} size="small">Close</Button>
        {running ? (
          <Button onClick={handleCancel} color="warning" variant="outlined" size="small">
            Cancel
          </Button>
        ) : (
          <Button
            onClick={() => { void handleStart(); }}
            disabled={inputsInvalid}
            variant="contained"
            size="small"
            startIcon={<RepeatIcon sx={{ fontSize: '0.875rem' }} />}
          >
            {summary ? 'Repeat Again' : 'Start'}
          </Button>
        )}
      </DialogActions>
    </Dialog>
  );
}
