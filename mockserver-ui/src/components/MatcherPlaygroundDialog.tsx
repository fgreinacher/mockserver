import { useState, useMemo, useCallback } from 'react';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useTheme } from '@mui/material/styles';
import Button from '@mui/material/Button';
import Box from '@mui/material/Box';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Alert from '@mui/material/Alert';
import IconButton from '@mui/material/IconButton';
import CloseIcon from '@mui/icons-material/Close';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import CancelIcon from '@mui/icons-material/Cancel';
import HelpOutlineIcon from '@mui/icons-material/HelpOutlineOutlined';
import { previewMatch, type PreviewResult, type SampleRequest, type FieldResult } from '../lib/matcherPreview';
import { monospaceFontFamily } from '../theme';

const DEFAULT_EXPECTATION = `{
  "httpRequest": {
    "method": "GET",
    "path": "/api/.*",
    "queryStringParameters": { "page": ["[0-9]+"] }
  }
}`;

const DEFAULT_SAMPLE: SampleRequest = {
  method: 'GET',
  path: '/api/users',
  headers: 'Accept: application/json',
  queryString: 'page=1',
  body: '',
};

function verdictIcon(verdict: FieldResult['verdict']) {
  if (verdict === 'match') return <CheckCircleIcon sx={{ fontSize: '1rem', color: 'success.main' }} />;
  if (verdict === 'mismatch') return <CancelIcon sx={{ fontSize: '1rem', color: 'error.main' }} />;
  return <HelpOutlineIcon sx={{ fontSize: '1rem', color: 'warning.main' }} />;
}

function FieldRow({ r }: { r: FieldResult }) {
  return (
    <Box sx={{ display: 'flex', gap: 1, py: 0.5, borderBottom: 1, borderColor: 'divider', '&:last-child': { borderBottom: 0 } }}>
      <Box sx={{ pt: '2px' }}>{verdictIcon(r.verdict)}</Box>
      <Box sx={{ minWidth: 0, flex: 1 }}>
        <Typography variant="caption" sx={{ fontFamily: monospaceFontFamily, fontWeight: 600, display: 'block' }}>
          {r.field}
        </Typography>
        <Typography variant="caption" color="text.secondary" sx={{ fontFamily: monospaceFontFamily, display: 'block', wordBreak: 'break-word' }}>
          expected {r.expected} · actual {r.actual}
        </Typography>
        {r.reason && (
          <Typography variant="caption" color={r.verdict === 'unsupported' ? 'warning.main' : 'error.main'} sx={{ display: 'block', wordBreak: 'break-word' }}>
            {r.reason}
          </Typography>
        )}
      </Box>
    </Box>
  );
}

/**
 * WS5.5 "matcher test playground": lets a user enter a hypothetical request and a
 * candidate expectation, then see whether the expectation would match — BEFORE
 * registering it. The verdict is computed client-side (a clearly-labelled preview)
 * because MockServer exposes no endpoint to dry-run an *unregistered* expectation.
 */
export default function MatcherPlaygroundDialog({
  open,
  onClose,
  initialExpectation,
}: {
  open: boolean;
  onClose: () => void;
  /** Optional expectation JSON to pre-fill (e.g. "Test this expectation" from a list). */
  initialExpectation?: string;
}) {
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  const [expectationText, setExpectationText] = useState(initialExpectation ?? DEFAULT_EXPECTATION);
  const [sample, setSample] = useState<SampleRequest>(DEFAULT_SAMPLE);
  const [result, setResult] = useState<PreviewResult | null>(null);
  const [parseError, setParseError] = useState<string | null>(null);

  // Re-seed the expectation when a *new* initial value arrives (a fresh "Test this
  // expectation") using React's sanctioned "adjust state during render" pattern:
  // track the last-applied seed in state and compare during render. User edits
  // between re-seeds are preserved because the seed only changes when the incoming
  // prop itself changes.
  const [lastSeed, setLastSeed] = useState(initialExpectation);
  if (initialExpectation != null && initialExpectation !== lastSeed) {
    setLastSeed(initialExpectation);
    setExpectationText(initialExpectation);
    setResult(null);
    setParseError(null);
  }

  const setField = useCallback(
    (key: keyof SampleRequest) => (e: React.ChangeEvent<HTMLInputElement>) =>
      setSample((s) => ({ ...s, [key]: e.target.value })),
    [],
  );

  const runTest = useCallback(() => {
    let expectation: unknown;
    try {
      expectation = JSON.parse(expectationText);
    } catch (err) {
      setParseError(err instanceof Error ? err.message : 'Invalid JSON');
      setResult(null);
      return;
    }
    setParseError(null);
    setResult(previewMatch(expectation, sample));
  }, [expectationText, sample]);

  const verdictChip = useMemo(() => {
    if (!result) return null;
    if (result.matches) {
      return <Chip color="success" icon={<CheckCircleIcon />} label="WOULD MATCH" />;
    }
    if (result.hasUnsupported) {
      return <Chip color="warning" icon={<HelpOutlineIcon />} label="INCONCLUSIVE" />;
    }
    return <Chip color="error" icon={<CancelIcon />} label="WOULD NOT MATCH" />;
  }, [result]);

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth fullScreen={fullScreen}>
      <DialogTitle sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', pr: 1 }}>
        Matcher Test Playground
        <IconButton size="small" onClick={onClose} aria-label="Close">
          <CloseIcon fontSize="small" />
        </IconButton>
      </DialogTitle>
      <DialogContent dividers>
        <Alert severity="info" sx={{ mb: 2 }}>
          Preview only — the verdict is computed in the browser for the common matcher
          types (method, path, headers, query, body), not by the server. Unsupported
          matcher fields are flagged and make the result inconclusive.
        </Alert>

        <Box sx={{ display: 'flex', gap: 2, flexDirection: { xs: 'column', md: 'row' } }}>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography variant="subtitle2" sx={{ mb: 0.5 }}>
              Candidate Expectation (JSON)
            </Typography>
            <TextField
              value={expectationText}
              onChange={(e) => setExpectationText(e.target.value)}
              multiline
              minRows={10}
              fullWidth
              error={parseError !== null}
              helperText={parseError ?? 'Either a full expectation or a bare httpRequest object.'}
              slotProps={{ htmlInput: { 'aria-label': 'Candidate expectation JSON', sx: { typography: 'body2', fontFamily: monospaceFontFamily } } }}
            />
          </Box>

          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography variant="subtitle2" sx={{ mb: 0.5 }}>
              Sample Request
            </Typography>
            <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
              <TextField size="small" label="Method" value={sample.method} onChange={setField('method')}
                slotProps={{ htmlInput: { 'aria-label': 'Sample method' } }} />
              <TextField size="small" label="Path" value={sample.path} onChange={setField('path')}
                slotProps={{ htmlInput: { 'aria-label': 'Sample path' } }} />
              <TextField size="small" label="Query (name=value per line)" value={sample.queryString} onChange={setField('queryString')}
                multiline minRows={2} slotProps={{ htmlInput: { 'aria-label': 'Sample query', sx: { typography: 'body2', fontFamily: monospaceFontFamily } } }} />
              <TextField size="small" label="Headers (Name: value per line)" value={sample.headers} onChange={setField('headers')}
                multiline minRows={2} slotProps={{ htmlInput: { 'aria-label': 'Sample headers', sx: { typography: 'body2', fontFamily: monospaceFontFamily } } }} />
              <TextField size="small" label="Body" value={sample.body} onChange={setField('body')}
                multiline minRows={2} slotProps={{ htmlInput: { 'aria-label': 'Sample body', sx: { typography: 'body2', fontFamily: monospaceFontFamily } } }} />
            </Box>
          </Box>
        </Box>

        {result && (
          <Box sx={{ mt: 2 }}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
              {verdictChip}
              {result.hasUnsupported && (
                <Typography variant="caption" color="warning.main">
                  Some matcher fields could not be evaluated in the browser — register the
                  expectation and use Debug Mismatch for a definitive answer.
                </Typography>
              )}
            </Box>
            {result.results.length === 0 ? (
              <Typography variant="body2" color="text.secondary">
                This expectation has no matcher fields, so it matches every request.
              </Typography>
            ) : (
              <Box sx={{ border: 1, borderColor: 'divider', borderRadius: 1, px: 1 }}>
                {result.results.map((r, i) => (
                  <FieldRow key={`${r.field}-${i}`} r={r} />
                ))}
              </Box>
            )}
          </Box>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={runTest} variant="contained" size="small">
          Test request
        </Button>
        <Button onClick={onClose} size="small">
          Close
        </Button>
      </DialogActions>
    </Dialog>
  );
}
