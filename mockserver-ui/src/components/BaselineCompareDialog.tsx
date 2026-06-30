import { useCallback, useState } from 'react';
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
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import { compareBaseline, type BaselineDiffReport } from '../lib/baseline';
import { humanizeError, type HumanError } from '../lib/errorMessage';
import { monospaceFontFamily } from '../theme';
import HumanErrorAlert from './HumanErrorAlert';
import JsonViewer from './JsonViewer';

interface BaselineCompareDialogProps {
  open: boolean;
  onClose: () => void;
  connectionParams: ConnectionParams;
}

/**
 * Runs `PUT /mockserver/baseline/compare`: the user pastes a baseline array of
 * expectations (and optionally a current array; omit to diff against the live
 * server) and the structured drift report is rendered. Full-screen below `sm`.
 */
export default function BaselineCompareDialog({ open, onClose, connectionParams }: BaselineCompareDialogProps) {
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));

  const [baselineText, setBaselineText] = useState('');
  const [currentText, setCurrentText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<HumanError | null>(null);
  const [report, setReport] = useState<BaselineDiffReport | null>(null);

  const run = useCallback(async () => {
    setError(null);
    setReport(null);

    let baseline: Record<string, unknown>[];
    try {
      const parsed = JSON.parse(baselineText) as unknown;
      baseline = Array.isArray(parsed) ? (parsed as Record<string, unknown>[]) : [parsed as Record<string, unknown>];
    } catch {
      setError({ message: 'The baseline must be valid JSON — paste an array of expectations.' });
      return;
    }

    let current: Record<string, unknown>[] | undefined;
    if (currentText.trim()) {
      try {
        const parsed = JSON.parse(currentText) as unknown;
        current = Array.isArray(parsed) ? (parsed as Record<string, unknown>[]) : [parsed as Record<string, unknown>];
      } catch {
        setError({ message: 'The current expectations must be valid JSON — paste an array, or leave it blank to diff against the live server.' });
        return;
      }
    }

    setBusy(true);
    try {
      const result = await compareBaseline(connectionParams, { baseline, ...(current ? { current } : {}) });
      setReport(result);
    } catch (e) {
      setError(humanizeError(e));
    } finally {
      setBusy(false);
    }
  }, [baselineText, currentText, connectionParams]);

  const handleClose = useCallback(() => {
    if (busy) return;
    onClose();
  }, [busy, onClose]);

  return (
    <Dialog open={open} onClose={handleClose} fullScreen={fullScreen} maxWidth="md" fullWidth>
      <DialogTitle>Compare Against Baseline</DialogTitle>
      <DialogContent dividers>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          Paste a known-good <strong>baseline</strong> array of expectations to
          detect drift. Leave <strong>current</strong> blank to compare the
          baseline against the live server&apos;s recorded expectations, or paste
          a second array to compare two snapshots directly.
        </Typography>

        <TextField
          label="Baseline expectations (JSON array)"
          placeholder='[ { "httpRequest": { "path": "/hello" }, "httpResponse": { "body": "hi" } } ]'
          multiline
          minRows={4}
          maxRows={10}
          fullWidth
          value={baselineText}
          onChange={(e) => setBaselineText(e.target.value)}
          sx={{ mb: 1.5, '& textarea': { fontFamily: monospaceFontFamily, fontSize: '0.8rem' } }}
        />

        <TextField
          label="Current expectations (optional — blank diffs against the live server)"
          placeholder="[ … ]"
          multiline
          minRows={3}
          maxRows={10}
          fullWidth
          value={currentText}
          onChange={(e) => setCurrentText(e.target.value)}
          sx={{ mb: 1.5, '& textarea': { fontFamily: monospaceFontFamily, fontSize: '0.8rem' } }}
        />

        {error && <HumanErrorAlert error={error} sx={{ mb: 1.5 }} />}

        {report && (
          <Box sx={{ mt: 1 }}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1, flexWrap: 'wrap' }}>
              <Chip
                size="small"
                label={report.hasDrift ? 'drift detected' : 'no drift'}
                color={report.hasDrift ? 'warning' : 'success'}
                variant="outlined"
              />
              <Chip size="small" label={`${report.added.length} added`} variant="outlined" />
              <Chip size="small" label={`${report.removed.length} removed`} variant="outlined" />
              <Chip size="small" label={`${report.changed.length} changed`} variant="outlined" />
            </Box>
            <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 0.5 }}>
              Diff Report
            </Typography>
            <Box sx={{ border: 1, borderColor: 'divider', borderRadius: 1, p: 1, maxHeight: 360, overflow: 'auto' }}>
              <JsonViewer data={report as unknown as Record<string, unknown>} collapsed={2} />
            </Box>
          </Box>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose} disabled={busy}>Close</Button>
        <Button
          variant="contained"
          onClick={() => { void run(); }}
          disabled={busy || !baselineText.trim()}
          startIcon={busy ? <CircularProgress size={16} color="inherit" /> : undefined}
        >
          {busy ? 'Comparing…' : 'Compare'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
