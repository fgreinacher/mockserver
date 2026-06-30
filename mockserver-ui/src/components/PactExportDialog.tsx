import { useState } from 'react';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useTheme } from '@mui/material/styles';
import Button from '@mui/material/Button';
import TextField from '@mui/material/TextField';
import Stack from '@mui/material/Stack';
import Alert from '@mui/material/Alert';
import Typography from '@mui/material/Typography';
import Divider from '@mui/material/Divider';
import Box from '@mui/material/Box';
import { exportPact, verifyPact } from '../lib/pactExport';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import { humanizeError, type HumanError } from '../lib/errorMessage';
import { monospaceFontFamily } from '../theme';
import HumanErrorAlert from './HumanErrorAlert';
import { trackFeature } from '../lib/analytics';

interface PactExportDialogProps {
  open: boolean;
  onClose: () => void;
  connectionParams: ConnectionParams;
}

/**
 * Dialog to export the active response expectations as a Pact v3 consumer
 * contract via PUT /mockserver/pact, with copy / download of the result.
 */
export default function PactExportDialog({ open, onClose, connectionParams }: PactExportDialogProps) {
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  const [consumer, setConsumer] = useState('');
  const [provider, setProvider] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<HumanError | null>(null);
  const [pactText, setPactText] = useState<string | null>(null);

  // Verify section
  const [verifyText, setVerifyText] = useState('');
  const [verifyBusy, setVerifyBusy] = useState(false);
  const [verifyError, setVerifyError] = useState<HumanError | null>(null);
  const [verifyResult, setVerifyResult] = useState<{ verified: boolean; result: unknown } | null>(null);

  const handleVerify = async () => {
    if (!verifyText.trim()) return;
    setVerifyBusy(true);
    setVerifyError(null);
    setVerifyResult(null);
    try {
      setVerifyResult(await verifyPact(connectionParams, verifyText));
    } catch (e) {
      setVerifyError(humanizeError(e));
    } finally {
      setVerifyBusy(false);
    }
  };

  const handleExport = async () => {
    setBusy(true);
    setError(null);
    setPactText(null);
    try {
      const pact = await exportPact(connectionParams, consumer, provider);
      trackFeature('export_performed');
      setPactText(JSON.stringify(pact, null, 2));
    } catch (e) {
      setError(humanizeError(e));
    } finally {
      setBusy(false);
    }
  };

  const handleCopy = () => {
    if (pactText !== null && typeof navigator !== 'undefined' && navigator.clipboard) {
      void navigator.clipboard.writeText(pactText);
    }
  };

  const handleDownload = () => {
    if (pactText === null) return;
    const blob = new Blob([pactText], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `${(consumer || 'consumer').trim()}-${(provider || 'provider').trim()}.pact.json`;
    anchor.click();
    URL.revokeObjectURL(url);
  };

  const handleClose = () => {
    setError(null);
    setPactText(null);
    setVerifyError(null);
    setVerifyResult(null);
    onClose();
  };

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="md" fullWidth fullScreen={fullScreen} aria-labelledby="pact-dialog-title">
      <DialogTitle id="pact-dialog-title">Pact Contract</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
          Export the active response expectations as a Pact v3 consumer contract for publishing to
          a Pact Broker / PactFlow.
        </Typography>
        <Stack direction="row" spacing={1} sx={{ mb: 1 }}>
          <TextField
            value={consumer}
            onChange={(e) => setConsumer(e.target.value)}
            label="Consumer"
            placeholder="consumer"
            size="small"
            fullWidth
          />
          <TextField
            value={provider}
            onChange={(e) => setProvider(e.target.value)}
            label="Provider"
            placeholder="provider"
            size="small"
            fullWidth
          />
        </Stack>
        {error !== null && <HumanErrorAlert error={error} sx={{ mb: 1 }} />}
        {pactText !== null && (
          <TextField
            value={pactText}
            label="Pact contract (JSON)"
            multiline
            minRows={10}
            fullWidth
            slotProps={{ input: { readOnly: true } }}
          />
        )}

        <Divider sx={{ my: 2 }} />

        <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 0.5 }}>Verify a Contract</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
          Paste a Pact contract to check it against the registered expectations.
        </Typography>
        <TextField
          value={verifyText}
          onChange={(e) => setVerifyText(e.target.value)}
          label="Pact contract to verify (JSON)"
          placeholder={'{\n  "consumer": { "name": "..." },\n  "provider": { "name": "..." },\n  "interactions": [ ... ]\n}'}
          multiline
          minRows={6}
          maxRows={16}
          fullWidth
          slotProps={{ input: { sx: { typography: 'body2', fontFamily: monospaceFontFamily } } }}
        />
        {verifyError !== null && <HumanErrorAlert error={verifyError} sx={{ mt: 1 }} />}
        {verifyResult !== null && (
          <Alert severity={verifyResult.verified ? 'success' : 'warning'} sx={{ mt: 1 }}>
            {verifyResult.verified ? 'Contract verified — all interactions are satisfied.' : 'Not verified — some interactions are not satisfied.'}
            <Box component="pre" sx={{ whiteSpace: 'pre-wrap', typography: 'caption', fontFamily: monospaceFontFamily, m: 0, mt: 0.5, maxHeight: 220, overflow: 'auto' }}>
              {JSON.stringify(verifyResult.result, null, 2)}
            </Box>
          </Alert>
        )}
        <Box sx={{ mt: 1 }}>
          <Button variant="outlined" size="small" onClick={() => void handleVerify()} disabled={verifyBusy || !verifyText.trim()}>
            {verifyBusy ? 'Verifying…' : 'Verify contract'}
          </Button>
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose}>Close</Button>
        {pactText !== null && <Button onClick={handleCopy}>Copy</Button>}
        {pactText !== null && <Button onClick={handleDownload}>Download</Button>}
        <Button variant="contained" onClick={() => void handleExport()} disabled={busy}>
          {busy ? 'Exporting…' : 'Export'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
