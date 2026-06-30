import { useState, useEffect, useCallback } from 'react';
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
import Alert from '@mui/material/Alert';
import Divider from '@mui/material/Divider';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import { loadAsyncApi, getAsyncApiStatus, verifyAsyncApi, AsyncApiUnavailableError } from '../lib/asyncApi';
import { humanizeError } from '../lib/errorMessage';
import { monospaceFontFamily } from '../theme';

export default function AsyncApiDialog({
  open,
  onClose,
  connectionParams,
}: {
  open: boolean;
  onClose: () => void;
  connectionParams: ConnectionParams;
}) {
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  const [spec, setSpec] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [unavailable, setUnavailable] = useState(false);
  const [result, setResult] = useState<Record<string, unknown> | null>(null);
  const [status, setStatus] = useState<Record<string, unknown> | null>(null);
  const [refreshTick, setRefreshTick] = useState(0);
  const [verifyBody, setVerifyBody] = useState('');
  const [verifyBusy, setVerifyBusy] = useState(false);
  const [verifyResult, setVerifyResult] = useState<{ verified: boolean; message: string } | null>(null);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    async function load(): Promise<void> {
      try {
        const next = await getAsyncApiStatus(connectionParams);
        if (cancelled) return;
        setUnavailable(next === null);
        setStatus(next);
      } catch (e) {
        if (!cancelled) setError(humanizeError(e).message);
      }
    }
    void load();
    return () => { cancelled = true; };
  }, [open, connectionParams, refreshTick]);

  const submit = useCallback(async () => {
    if (!spec.trim()) { setError('Paste an AsyncAPI spec (JSON or YAML).'); return; }
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      setResult(await loadAsyncApi(connectionParams, spec));
      setRefreshTick((t) => t + 1);
    } catch (e) {
      if (e instanceof AsyncApiUnavailableError) { setUnavailable(true); setError(e.message); }
      else setError(humanizeError(e).message);
    } finally {
      setBusy(false);
    }
  }, [connectionParams, spec]);

  const verify = useCallback(async () => {
    if (!verifyBody.trim()) { setError('Paste a verification request (JSON).'); return; }
    setVerifyBusy(true);
    setError(null);
    setVerifyResult(null);
    try {
      setVerifyResult(await verifyAsyncApi(connectionParams, verifyBody));
    } catch (e) {
      if (e instanceof AsyncApiUnavailableError) { setUnavailable(true); setError(e.message); }
      else setError(humanizeError(e).message);
    } finally {
      setVerifyBusy(false);
    }
  }, [connectionParams, verifyBody]);

  const handleClose = useCallback(() => {
    setSpec('');
    setVerifyBody('');
    setResult(null);
    setVerifyResult(null);
    setError(null);
    onClose();
  }, [onClose]);

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="md" fullWidth fullScreen={fullScreen} aria-labelledby="asyncapi-dialog-title">
      <DialogTitle id="asyncapi-dialog-title">AsyncAPI Broker Mock</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          Register an AsyncAPI spec to mock a message broker — paste the spec (JSON or YAML), or a
          {' '}<code>{'{ spec, brokerConfig }'}</code> JSON object.
        </Typography>
        {unavailable && (
          <Alert severity="warning" sx={{ mb: 1.5 }}>
            The AsyncAPI module (mockserver-async) is not on this server's classpath, so broker
            mocking is unavailable.
          </Alert>
        )}
        {error && !unavailable && <Alert severity="error" sx={{ mb: 1.5 }}>{error}</Alert>}
        {result && (
          <Alert severity="success" sx={{ mb: 1.5 }}>
            AsyncAPI spec loaded.
            <Box component="pre" sx={{ whiteSpace: 'pre-wrap', typography: 'caption', fontFamily: monospaceFontFamily, m: 0, mt: 0.5 }}>
              {JSON.stringify(result, null, 2)}
            </Box>
          </Alert>
        )}
        <TextField
          label="AsyncAPI spec (JSON / YAML)"
          multiline minRows={10} maxRows={24} fullWidth disabled={unavailable}
          value={spec} onChange={(e) => setSpec(e.target.value)}
          placeholder={'asyncapi: 3.0.0\ninfo:\n  title: Orders\n  version: 1.0.0\nchannels:\n  orders:\n    address: orders'}
          slotProps={{ input: { sx: { typography: 'body2', fontFamily: monospaceFontFamily } } }}
        />
        {status && Object.keys(status).length > 0 && (
          <Box sx={{ mt: 1.5 }}>
            <Typography variant="caption" color="text.secondary">Current status</Typography>
            <Box component="pre" sx={{ whiteSpace: 'pre-wrap', typography: 'caption', fontFamily: monospaceFontFamily, m: 0, mt: 0.5, maxHeight: 180, overflow: 'auto' }}>
              {JSON.stringify(status, null, 2)}
            </Box>
          </Box>
        )}

        <Divider sx={{ my: 2 }} />
        <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 0.5 }}>Verify Messages</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
          Check observed broker messages against a verification request.
        </Typography>
        <TextField
          label="Verification request (JSON)"
          multiline minRows={5} maxRows={16} fullWidth disabled={unavailable}
          value={verifyBody} onChange={(e) => setVerifyBody(e.target.value)}
          placeholder={'{\n  "channel": "orders",\n  "atLeast": 1\n}'}
          slotProps={{ input: { sx: { typography: 'body2', fontFamily: monospaceFontFamily } } }}
        />
        {verifyResult && (
          <Alert severity={verifyResult.verified ? 'success' : 'warning'} sx={{ mt: 1 }}>
            {verifyResult.verified ? 'Verified — the observed messages satisfy the request.' : (verifyResult.message || 'Not verified.')}
          </Alert>
        )}
        <Box sx={{ mt: 1 }}>
          <Button variant="outlined" size="small" disabled={verifyBusy || unavailable || !verifyBody.trim()} onClick={() => void verify()}>
            {verifyBusy ? 'Verifying…' : 'Verify messages'}
          </Button>
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose}>Close</Button>
        <Button variant="contained" disabled={busy || unavailable} onClick={() => void submit()}>Load spec</Button>
      </DialogActions>
    </Dialog>
  );
}
