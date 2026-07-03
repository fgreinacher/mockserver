import { useCallback, useEffect, useMemo, useState } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Alert from '@mui/material/Alert';
import AlertTitle from '@mui/material/AlertTitle';
import Button from '@mui/material/Button';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';
import TextField from '@mui/material/TextField';
import InputAdornment from '@mui/material/InputAdornment';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import RefreshIcon from '@mui/icons-material/Refresh';
import SearchIcon from '@mui/icons-material/Search';
import { humanizeError } from '../lib/errorMessage';
import { monospaceFontFamily } from '../theme';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import { fetchAuditEntries, type AuditEntry } from '../lib/audit';

interface AuditPanelProps {
  connectionParams: ConnectionParams;
}

/**
 * The audit endpoint returns no JSON error envelope on 404, so a missing
 * endpoint surfaces as the status-line message or the humanized "isn't
 * available" copy. Detect both so the panel shows the "not available on an
 * older server" branch rather than a generic error.
 */
function isUnavailable(message: string): boolean {
  return (
    message.includes('404') ||
    message.includes('Not Found') ||
    message.includes('isn’t available')
  );
}

/** Colour the outcome chip: authorized = success, denied = warning/error. */
function outcomeColor(outcome: string): 'success' | 'warning' | 'error' | 'default' {
  const o = outcome.toUpperCase();
  if (o === 'AUTHORIZED' || o === 'ALLOWED') return 'success';
  if (o === 'FORBIDDEN') return 'error';
  if (o === 'UNAUTHENTICATED') return 'warning';
  return 'default';
}

function formatTime(epochTimeMs: number): string {
  if (!Number.isFinite(epochTimeMs)) return '';
  return new Date(epochTimeMs).toLocaleString();
}

/**
 * Case-insensitive substring match across the human-readable fields of an entry.
 * Audit entries are control-plane mutations, not request/response traffic, so the
 * traffic-oriented status:/method:/path: operators of OperatorSearchField don't
 * map cleanly here — a plain substring filter is the honest, trivially-applicable
 * option.
 */
function matchesSearch(entry: AuditEntry, needle: string): boolean {
  const haystack = [
    entry.method,
    entry.path,
    entry.operation,
    entry.sourceAddress,
    entry.principal ?? '',
    entry.principalSource ?? '',
    entry.outcome,
    entry.summary,
  ]
    .join(' ')
    .toLowerCase();
  return haystack.includes(needle);
}

export default function AuditPanel({ connectionParams }: AuditPanelProps) {
  const [entries, setEntries] = useState<AuditEntry[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState('');
  const [refreshTick, setRefreshTick] = useState(0);

  // Fetch on mount and whenever the user hits Refresh (refreshTick) — no polling
  // (the audit trail is a control-plane history, not live traffic; the user pulls
  // updates explicitly). The async fetch is defined inline so state is only ever
  // set after the awaited call, never synchronously inside the effect.
  useEffect(() => {
    const controller = new AbortController();
    async function load(): Promise<void> {
      try {
        const response = await fetchAuditEntries(connectionParams, { signal: controller.signal });
        if (controller.signal.aborted) return;
        setEntries(response);
        setLoadError(null);
      } catch (e) {
        if (controller.signal.aborted) return;
        setLoadError(humanizeError(e).message);
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    }
    void load();
    return () => controller.abort();
  }, [connectionParams, refreshTick]);

  // Loading is toggled here (an event handler, not the effect) so the refresh
  // spinner shows without a synchronous setState inside the effect.
  const refresh = useCallback(() => {
    setLoading(true);
    setRefreshTick((t) => t + 1);
  }, []);

  const needle = search.trim().toLowerCase();
  const filtered = useMemo(() => {
    const all = entries ?? [];
    if (!needle) return all;
    return all.filter((e) => matchesSearch(e, needle));
  }, [entries, needle]);

  return (
    <Box sx={{ flex: 1, overflow: 'auto', p: 1.5 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5, flexWrap: 'wrap' }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
          Audit Trail
        </Typography>
        {entries && (
          <Chip
            size="small"
            label={`${entries.length} ${entries.length === 1 ? 'entry' : 'entries'}`}
            variant="outlined"
          />
        )}
        <Box sx={{ flex: 1 }} />
        <TextField
          size="small"
          placeholder="Search audit entries"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          slotProps={{
            htmlInput: { 'aria-label': 'Search audit entries' },
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <SearchIcon fontSize="small" />
                </InputAdornment>
              ),
            },
          }}
          sx={{ maxWidth: 260, '& .MuiInputBase-root': { height: 28 } }}
        />
        <Button
          size="small"
          onClick={refresh}
          disabled={loading}
          startIcon={<RefreshIcon fontSize="small" />}
          sx={{ textTransform: 'none' }}
        >
          Refresh
        </Button>
      </Box>

      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
        The most recent control-plane changes to this server — mutations to expectations,
        configuration, and server state. Newest first. Request headers and bodies are never recorded.
      </Typography>

      {loadError && (
        <Alert
          severity={isUnavailable(loadError) ? 'info' : 'error'}
          sx={{ mb: 1.5 }}
          action={
            <IconButton color="inherit" size="small" onClick={refresh} aria-label="Retry">
              <RefreshIcon fontSize="small" />
            </IconButton>
          }
        >
          <AlertTitle>
            {isUnavailable(loadError) ? 'Audit trail not available' : 'Could not load audit trail'}
          </AlertTitle>
          {isUnavailable(loadError)
            ? 'The connected server does not expose an audit trail. This feature requires a newer version of MockServer.'
            : loadError}
        </Alert>
      )}

      <Paper variant="outlined" sx={{ p: 0 }}>
        {filtered.length === 0 ? (
          <Typography variant="body2" color="text.secondary" sx={{ p: 2, textAlign: 'center' }}>
            {entries == null
              ? 'Loading audit trail…'
              : (entries.length === 0
                ? 'No control-plane changes recorded yet.'
                : 'No entries match your search.')}
          </Typography>
        ) : (
          <TableContainer>
            <Table size="small" stickyHeader>
              <TableHead>
                <TableRow>
                  <TableCell>Time</TableCell>
                  <TableCell>Operation</TableCell>
                  <TableCell>Method</TableCell>
                  <TableCell>Path</TableCell>
                  <TableCell>Source</TableCell>
                  <TableCell>Principal</TableCell>
                  <TableCell>Outcome</TableCell>
                  <TableCell>Summary</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {filtered.map((entry, i) => (
                  <TableRow key={`${entry.epochTimeMs}-${i}`}>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>
                      <Typography variant="caption" sx={{ fontFamily: monospaceFontFamily }}>
                        {formatTime(entry.epochTimeMs)}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="caption" sx={{ fontFamily: monospaceFontFamily }}>
                        {entry.operation}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="caption" sx={{ fontFamily: monospaceFontFamily }}>
                        {entry.method}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="caption" sx={{ fontFamily: monospaceFontFamily, wordBreak: 'break-all' }}>
                        {entry.path}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="caption" sx={{ fontFamily: monospaceFontFamily }}>
                        {entry.sourceAddress}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      {entry.principal ? (
                        <Tooltip title={entry.principalSource ? `via ${entry.principalSource}` : ''}>
                          <Typography variant="caption" sx={{ fontFamily: monospaceFontFamily }}>
                            {entry.principal}
                          </Typography>
                        </Tooltip>
                      ) : (
                        <Typography variant="caption" color="text.secondary">
                          —
                        </Typography>
                      )}
                    </TableCell>
                    <TableCell>
                      <Chip
                        size="small"
                        label={entry.outcome}
                        color={outcomeColor(entry.outcome)}
                        variant="outlined"
                        sx={{ height: 20, fontSize: '0.65rem' }}
                      />
                    </TableCell>
                    <TableCell>
                      <Typography variant="caption">{entry.summary}</Typography>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Paper>
    </Box>
  );
}
