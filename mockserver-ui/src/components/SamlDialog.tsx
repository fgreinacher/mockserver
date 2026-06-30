import { useState, useCallback } from 'react';
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
import type { ConnectionParams } from '../hooks/useConnectionParams';
import { createSamlProvider, type SamlConfig } from '../lib/saml';
import { humanizeError, type HumanError } from '../lib/errorMessage';
import HumanErrorAlert from './HumanErrorAlert';

/** Parse the "key=value" lines of the attributes textarea into a map. Blank lines ignored. */
function parseAttributes(raw: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const line of raw.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    const eq = trimmed.indexOf('=');
    if (eq <= 0) continue;
    const key = trimmed.slice(0, eq).trim();
    const value = trimmed.slice(eq + 1).trim();
    if (key) out[key] = value;
  }
  return out;
}

export default function SamlDialog({
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
  const [idpEntityId, setIdpEntityId] = useState('');
  const [spEntityId, setSpEntityId] = useState('');
  const [acsUrl, setAcsUrl] = useState('');
  const [subjectNameId, setSubjectNameId] = useState('');
  const [nameIdFormat, setNameIdFormat] = useState('');
  const [sessionDuration, setSessionDuration] = useState('');
  const [attributes, setAttributes] = useState('');

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<HumanError | null>(null);
  const [created, setCreated] = useState<number | null>(null);

  const submit = useCallback(async () => {
    setBusy(true);
    setError(null);
    setCreated(null);
    // Only send fields the user set; blanks fall back to the server defaults.
    const config: SamlConfig = {};
    if (idpEntityId.trim()) config.idpEntityId = idpEntityId.trim();
    if (spEntityId.trim()) config.spEntityId = spEntityId.trim();
    if (acsUrl.trim()) config.assertionConsumerServiceUrl = acsUrl.trim();
    if (subjectNameId.trim()) config.subjectNameId = subjectNameId.trim();
    if (nameIdFormat.trim()) config.nameIdFormat = nameIdFormat.trim();
    const duration = parseInt(sessionDuration, 10);
    if (!isNaN(duration) && duration > 0) config.sessionDurationSeconds = duration;
    const attrs = parseAttributes(attributes);
    if (Object.keys(attrs).length > 0) config.attributes = attrs;
    try {
      setCreated(await createSamlProvider(connectionParams, config));
    } catch (e) {
      setError(humanizeError(e));
    } finally {
      setBusy(false);
    }
  }, [connectionParams, idpEntityId, spEntityId, acsUrl, subjectNameId, nameIdFormat, sessionDuration, attributes]);

  const handleClose = useCallback(() => {
    setIdpEntityId('');
    setSpEntityId('');
    setAcsUrl('');
    setSubjectNameId('');
    setNameIdFormat('');
    setSessionDuration('');
    setAttributes('');
    setError(null);
    setCreated(null);
    onClose();
  }, [onClose]);

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="sm" fullWidth fullScreen={fullScreen} aria-labelledby="saml-dialog-title">
      <DialogTitle id="saml-dialog-title">Mock SAML Provider</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          Register a mock SAML 2.0 identity provider — IdP metadata plus an SSO endpoint
          implementing the SP-initiated Web-Browser-SSO POST profile — as expectations.
          Leave a field blank to use the server default.
        </Typography>
        {error && <HumanErrorAlert error={error} sx={{ mb: 1.5 }} />}
        {created !== null && (
          <Alert severity="success" sx={{ mb: 1.5 }}>Created {created} expectation{created === 1 ? '' : 's'} for the mock SAML provider.</Alert>
        )}
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
          <TextField size="small" label="IdP entity ID (issuer)" placeholder="http://localhost:1080/saml/idp" value={idpEntityId} onChange={(e) => setIdpEntityId(e.target.value)} />
          <TextField size="small" label="SP entity ID" placeholder="http://localhost:8080/saml/sp" value={spEntityId} onChange={(e) => setSpEntityId(e.target.value)} />
          <TextField size="small" label="Assertion Consumer Service (ACS) URL" placeholder="http://localhost:8080/saml/acs" value={acsUrl} onChange={(e) => setAcsUrl(e.target.value)} />
          <Box sx={{ display: 'flex', gap: 1 }}>
            <TextField size="small" label="Subject NameID" placeholder="mock-user@example.com" value={subjectNameId} onChange={(e) => setSubjectNameId(e.target.value)} sx={{ flex: 1 }} />
            <TextField size="small" label="Session (s)" type="number" placeholder="3600" value={sessionDuration} onChange={(e) => setSessionDuration(e.target.value)} sx={{ width: { xs: '100%', sm: 130 } }} />
          </Box>
          <TextField size="small" label="NameID format" placeholder="urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress" value={nameIdFormat} onChange={(e) => setNameIdFormat(e.target.value)} />
          <TextField
            size="small"
            label="Attributes (one key=value per line)"
            placeholder={'email=mock-user@example.com\nrole=admin'}
            value={attributes}
            onChange={(e) => setAttributes(e.target.value)}
            multiline
            minRows={2}
          />
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose}>Close</Button>
        <Button variant="contained" disabled={busy} onClick={() => void submit()}>Create provider</Button>
      </DialogActions>
    </Dialog>
  );
}
