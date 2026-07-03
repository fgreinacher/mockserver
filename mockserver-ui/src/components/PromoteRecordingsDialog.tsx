import { useState, useCallback } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import Alert from '@mui/material/Alert';
import AlertTitle from '@mui/material/AlertTitle';
import CircularProgress from '@mui/material/CircularProgress';
import AutoAwesomeMotionIcon from '@mui/icons-material/AutoAwesomeMotion';
import { promoteRecordings, type PromoteRecordingsFilter } from '../lib/promoteRecordings';
import { humanizeError, type HumanError } from '../lib/errorMessage';
import HumanErrorAlert from './HumanErrorAlert';
import { useDashboardStore } from '../store';
import type { ConnectionParams } from '../hooks/useConnectionParams';

interface PromoteRecordingsDialogProps {
  open: boolean;
  onClose: () => void;
  connectionParams: ConnectionParams;
  /** Number of recorded (proxied) exchanges available to promote. */
  recordedCount: number;
  /** Method / path pre-filled from the current traffic search, where sensible. */
  initialFilter?: PromoteRecordingsFilter;
}

/**
 * Dialog wrapping `PUT /mockserver/recordings/promote`. Lets the operator
 * optionally narrow the recorded traffic by method / path, explains the
 * server-side redaction, runs the promotion, and — on success — reports how many
 * expectations were created with a shortcut to the Dashboard's expectations
 * panel. Errors are surfaced via the shared HumanErrorAlert.
 */
export default function PromoteRecordingsDialog({
  open,
  onClose,
  connectionParams,
  recordedCount,
  initialFilter,
}: PromoteRecordingsDialogProps) {
  const [method, setMethod] = useState(initialFilter?.method ?? '');
  const [path, setPath] = useState(initialFilter?.path ?? '');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<HumanError | null>(null);
  const [createdCount, setCreatedCount] = useState<number | null>(null);

  const handleRun = useCallback(async () => {
    setLoading(true);
    setError(null);
    setCreatedCount(null);
    try {
      const filter: PromoteRecordingsFilter = {};
      if (method.trim()) filter.method = method.trim();
      if (path.trim()) filter.path = path.trim();
      const result = await promoteRecordings(connectionParams, filter);
      setCreatedCount(result.count);
    } catch (err) {
      setError(humanizeError(err));
    } finally {
      setLoading(false);
    }
  }, [connectionParams, method, path]);

  const handleViewExpectations = useCallback(() => {
    useDashboardStore.getState().setView('dashboard');
    onClose();
  }, [onClose]);

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle sx={{ fontSize: '0.95rem' }}>Promote Recordings to Mocks</DialogTitle>
      <DialogContent dividers>
        <Typography variant="body2" sx={{ mb: 1.5 }}>
          Turn recorded (proxied) traffic into active mock expectations. MockServer consolidates
          duplicate exchanges into reusable mocks, generalises volatile values, and adds them to the
          active expectation set — so you can record against a real service, then serve the captured
          responses as mocks.
        </Typography>

        {createdCount === null && (
          <>
            <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600, display: 'block', mb: 0.5 }}>
              Filter (Optional)
            </Typography>
            <Box sx={{ display: 'flex', gap: 1, mb: 1.5 }}>
              <TextField
                label="Method"
                size="small"
                value={method}
                onChange={(e) => setMethod(e.target.value)}
                placeholder="Any"
                disabled={loading}
                sx={{ width: 120 }}
                slotProps={{ htmlInput: { 'aria-label': 'Method filter' } }}
              />
              <TextField
                label="Path"
                size="small"
                value={path}
                onChange={(e) => setPath(e.target.value)}
                placeholder="Any (e.g. /api/.*)"
                disabled={loading}
                fullWidth
                slotProps={{ htmlInput: { 'aria-label': 'Path filter' } }}
              />
            </Box>
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1.5 }}>
              Leave both blank to promote all {recordedCount} recorded request{recordedCount === 1 ? '' : 's'}.
            </Typography>

            <Alert severity="info" sx={{ mb: 1 }}>
              <AlertTitle sx={{ fontSize: '0.8rem', mb: 0.25 }}>Secrets Are Redacted</AlertTitle>
              <Typography variant="caption">
                Sensitive headers and values (Authorization, API keys, cookies, and similar) are
                redacted before promotion, so the generated mocks never carry captured credentials.
              </Typography>
            </Alert>
          </>
        )}

        {loading && (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, py: 2 }}>
            <CircularProgress size={20} />
            <Typography variant="body2">Promoting recordings…</Typography>
          </Box>
        )}

        {error && <HumanErrorAlert error={error} sx={{ mb: 1 }} />}

        {createdCount !== null && (
          <Alert severity={createdCount > 0 ? 'success' : 'warning'} sx={{ mb: 1 }}>
            {createdCount > 0 ? (
              <>
                Created {createdCount} expectation{createdCount === 1 ? '' : 's'} from recorded traffic.
                They are now active and will match new requests.
              </>
            ) : (
              <>
                No expectations were created — no recorded traffic matched the filter. Try widening or
                clearing the method / path filter.
              </>
            )}
          </Alert>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} size="small">Close</Button>
        {createdCount !== null && createdCount > 0 && (
          <Button onClick={handleViewExpectations} size="small" variant="outlined">
            View Expectations
          </Button>
        )}
        <Button
          onClick={() => { void handleRun(); }}
          disabled={loading || recordedCount === 0}
          variant="contained"
          size="small"
          startIcon={<AutoAwesomeMotionIcon sx={{ fontSize: '0.875rem' }} />}
        >
          {createdCount !== null ? 'Run Again' : 'Run'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
