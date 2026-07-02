import { useState } from 'react';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useTheme } from '@mui/material/styles';
import Button from '@mui/material/Button';
import TextField from '@mui/material/TextField';
import Alert from '@mui/material/Alert';
import Typography from '@mui/material/Typography';
import { importGraphql } from '../lib/graphqlImport';
import { humanizeError, type HumanError } from '../lib/errorMessage';
import HumanErrorAlert from './HumanErrorAlert';
import type { ConnectionParams } from '../hooks/useConnectionParams';

interface GraphqlImportDialogProps {
  open: boolean;
  onClose: () => void;
  connectionParams: ConnectionParams;
}

/**
 * Dialog to import a GraphQL SDL schema via PUT /mockserver/graphql, generating
 * an expectation that matches GraphQL queries against the schema and responds
 * with schema-shaped mock data.
 */
export default function GraphqlImportDialog({ open, onClose, connectionParams }: GraphqlImportDialogProps) {
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  const [schema, setSchema] = useState('');
  const [path, setPath] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<HumanError | null>(null);
  const [createdCount, setCreatedCount] = useState<number | null>(null);

  const handleImport = async () => {
    setBusy(true);
    setError(null);
    setCreatedCount(null);
    try {
      const created = await importGraphql(connectionParams, schema, path);
      setCreatedCount(created.length);
    } catch (e) {
      setError(humanizeError(e));
    } finally {
      setBusy(false);
    }
  };

  const handleClose = () => {
    setSchema('');
    setPath('');
    setError(null);
    setCreatedCount(null);
    onClose();
  };

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="md" fullWidth fullScreen={fullScreen} aria-labelledby="graphql-dialog-title">
      <DialogTitle id="graphql-dialog-title">Import GraphQL Schema</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
          Paste a GraphQL SDL schema. MockServer generates an expectation that matches GraphQL
          queries posted to the endpoint against the schema and responds with schema-shaped mock
          data.
        </Typography>
        <TextField
          value={path}
          onChange={(e) => setPath(e.target.value)}
          label="Endpoint path"
          placeholder="/graphql"
          fullWidth
          size="small"
          spellCheck={false}
          sx={{ mb: 1 }}
        />
        <TextField
          value={schema}
          onChange={(e) => setSchema(e.target.value)}
          label="GraphQL SDL"
          multiline
          minRows={10}
          fullWidth
          spellCheck={false}
        />
        {error !== null && (
          <HumanErrorAlert error={error} sx={{ mt: 1 }} />
        )}
        {createdCount !== null && (
          <Alert severity="success" sx={{ mt: 1 }}>
            Imported {createdCount} expectation{createdCount === 1 ? '' : 's'}.
          </Alert>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose}>Close</Button>
        <Button
          variant="contained"
          onClick={() => void handleImport()}
          disabled={busy || schema.trim().length === 0}
        >
          {busy ? 'Importing…' : 'Import'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
