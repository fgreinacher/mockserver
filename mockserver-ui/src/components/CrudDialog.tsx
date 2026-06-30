import { useState, useCallback } from 'react';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import TextField from '@mui/material/TextField';
import MenuItem from '@mui/material/MenuItem';
import Button from '@mui/material/Button';
import Alert from '@mui/material/Alert';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useTheme } from '@mui/material/styles';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import { registerCrudResource, type CrudConfig, type CrudResult } from '../lib/crud';
import { humanizeError, type HumanError } from '../lib/errorMessage';
import HumanErrorAlert from './HumanErrorAlert';
import { monospaceFontFamily } from '../theme';

export default function CrudDialog({
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

  const [basePath, setBasePath] = useState('');
  const [idField, setIdField] = useState('');
  const [idStrategy, setIdStrategy] = useState<'AUTO_INCREMENT' | 'UUID'>('AUTO_INCREMENT');
  const [initialData, setInitialData] = useState('');

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<HumanError | null>(null);
  const [result, setResult] = useState<CrudResult | null>(null);

  const submit = useCallback(async () => {
    setError(null);
    setResult(null);

    const trimmedBasePath = basePath.trim();
    if (!trimmedBasePath) {
      setError({ message: 'basePath is required.' });
      return;
    }

    // Parse initialData if provided.
    let parsedInitialData: unknown[] | undefined;
    const trimmedData = initialData.trim();
    if (trimmedData) {
      try {
        const parsed: unknown = JSON.parse(trimmedData);
        if (!Array.isArray(parsed)) {
          setError({ message: 'initialData must be a JSON array.' });
          return;
        }
        parsedInitialData = parsed;
      } catch {
        setError({ message: 'initialData is not valid JSON.' });
        return;
      }
    }

    // Only send fields the user set; blanks fall back to the server defaults.
    const config: CrudConfig = { basePath: trimmedBasePath };
    if (idField.trim()) config.idField = idField.trim();
    if (idStrategy !== 'AUTO_INCREMENT') config.idStrategy = idStrategy;
    if (parsedInitialData) config.initialData = parsedInitialData;

    setBusy(true);
    try {
      setResult(await registerCrudResource(connectionParams, config));
    } catch (e) {
      setError(humanizeError(e));
    } finally {
      setBusy(false);
    }
  }, [connectionParams, basePath, idField, idStrategy, initialData]);

  const handleClose = useCallback(() => {
    setBasePath('');
    setIdField('');
    setIdStrategy('AUTO_INCREMENT');
    setInitialData('');
    setError(null);
    setResult(null);
    onClose();
  }, [onClose]);

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="sm" fullWidth fullScreen={fullScreen} aria-labelledby="crud-dialog-title">
      <DialogTitle id="crud-dialog-title">Register CRUD Resource</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          Register auto-CRUD expectations for a resource path — GET, POST, PUT, and DELETE
          endpoints with automatic ID management. Leave optional fields blank to use server
          defaults.
        </Typography>
        {error && <HumanErrorAlert error={error} sx={{ mb: 1.5 }} />}
        {result && (
          <Alert severity="success" sx={{ mb: 1.5 }}>
            Registered CRUD resource at <strong>{result.basePath}</strong> ({result.idStrategy}, {result.itemCount} item{result.itemCount === 1 ? '' : 's'}).
          </Alert>
        )}
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
          <TextField
            size="small"
            label="Resource path"
            required
            placeholder="/api/users"
            helperText="The base path the CRUD endpoints are mounted under."
            value={basePath}
            onChange={(e) => setBasePath(e.target.value)}
          />
          <Box sx={{ display: 'flex', gap: 1, alignItems: 'flex-start', flexWrap: 'wrap' }}>
            <TextField
              size="small"
              label="ID field name"
              placeholder="id"
              helperText="Property used as each item's identifier (default: id)."
              value={idField}
              onChange={(e) => setIdField(e.target.value)}
              sx={{ flex: 1, minWidth: { xs: '100%', sm: 140 } }}
            />
            <TextField
              size="small"
              label="ID strategy"
              select
              helperText="How new item IDs are generated."
              value={idStrategy}
              onChange={(e) => setIdStrategy(e.target.value as 'AUTO_INCREMENT' | 'UUID')}
              sx={{ width: { xs: '100%', sm: 180 } }}
            >
              <MenuItem value="AUTO_INCREMENT">AUTO_INCREMENT</MenuItem>
              <MenuItem value="UUID">UUID</MenuItem>
            </TextField>
          </Box>
          <TextField
            size="small"
            label="Seed data (JSON array)"
            multiline
            minRows={3}
            maxRows={10}
            placeholder={'[\n  { "name": "Alice" },\n  { "name": "Bob" }\n]'}
            helperText="Optional JSON array of items to pre-populate the resource."
            value={initialData}
            onChange={(e) => setInitialData(e.target.value)}
            slotProps={{ input: { sx: { fontFamily: monospaceFontFamily, typography: 'body2' } } }}
          />
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose}>Close</Button>
        <Button variant="contained" disabled={busy} onClick={() => void submit()}>Register</Button>
      </DialogActions>
    </Dialog>
  );
}
