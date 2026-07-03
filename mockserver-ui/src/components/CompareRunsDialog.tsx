import { useState, useMemo, useCallback } from 'react';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useTheme } from '@mui/material/styles';
import Button from '@mui/material/Button';
import TextField from '@mui/material/TextField';
import Box from '@mui/material/Box';
import Alert from '@mui/material/Alert';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Paper from '@mui/material/Paper';
import CircularProgress from '@mui/material/CircularProgress';
import ToggleButton from '@mui/material/ToggleButton';
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup';
import { useDashboardStore } from '../store';
import { monospaceFontFamily } from '../theme';
import { useConnectionParams } from '../hooks/useConnectionParams';
import { groupBySession, shortenScenarioName, type Session } from '../lib/sessionGrouping';
import {
  extractTrajectory,
  compareTrajectories,
  type DiffReport,
  type TrajectorySkeleton,
} from '../lib/trajectoryDiff';
import { diffRuns, type RunDiffResult, type RunDiffFilter } from '../lib/runDiff';
import { humanizeError, type HumanError } from '../lib/errorMessage';
import HumanErrorAlert from './HumanErrorAlert';

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface CompareRunsDialogProps {
  open: boolean;
  onClose: () => void;
}

// ---------------------------------------------------------------------------
// Verdict banner
// ---------------------------------------------------------------------------

function VerdictBanner({ report }: { report: DiffReport }) {
  const severity = report.verdict === 'identical' ? 'success' : 'warning';
  let message: string;

  if (report.verdict === 'identical') {
    message = `IDENTICAL -- both runs have ${report.turnCountA} turn(s) with the same structural skeleton`;
  } else if (report.verdict === 'different-length') {
    message = `DIFFERENT LENGTHS -- Run A has ${report.turnCountA} turn(s), Run B has ${report.turnCountB} turn(s)`;
  } else {
    const d = report.firstDivergence;
    if (d) {
      message = `DIVERGENT at turn ${d.turn} (${d.kind}): Run A = "${d.a}" vs Run B = "${d.b}"`;
    } else {
      message = 'DIVERGENT';
    }
  }

  return (
    <Alert severity={severity} sx={{ mb: 2 }}>
      <Typography variant="subtitle2" sx={{ fontWeight: 700, fontSize: '0.8rem' }}>
        {message}
      </Typography>
    </Alert>
  );
}

// ---------------------------------------------------------------------------
// Tool call step chain
// ---------------------------------------------------------------------------

function ToolCallChain({
  label,
  turns,
}: {
  label: string;
  turns: TrajectorySkeleton['turns'];
}) {
  return (
    <Box sx={{ flex: 1, minWidth: 0 }}>
      <Typography variant="caption" sx={{ fontWeight: 600, mb: 0.5, display: 'block' }}>
        {label}
      </Typography>
      <Box
        sx={{
          display: 'flex',
          gap: 0.5,
          flexWrap: 'wrap',
          alignItems: 'center',
        }}
      >
        {turns.map((turn, i) => {
          const toolLabel =
            turn.toolCalls.length > 0 ? turn.toolCalls.join(', ') : '(no tools)';
          const isError = turn.statusCode !== null && turn.statusCode >= 400;
          return (
            <Box key={i} sx={{ display: 'flex', alignItems: 'center', gap: 0.25 }}>
              {i > 0 && (
                <Typography variant="caption" color="text.secondary" sx={{ fontSize: '0.7rem' }}>
                  {' -> '}
                </Typography>
              )}
              <Chip
                label={`[${i}] ${toolLabel}`}
                size="small"
                color={isError ? 'error' : 'default'}
                variant="outlined"
                sx={{
                  height: 20,
                  fontSize: '0.6rem',
                  fontFamily: monospaceFontFamily,
                  '& .MuiChip-label': { px: 0.5 },
                }}
              />
            </Box>
          );
        })}
        {turns.length === 0 && (
          <Typography variant="caption" color="text.secondary">
            (empty)
          </Typography>
        )}
      </Box>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Token trajectory table
// ---------------------------------------------------------------------------

function TokenTable({ report }: { report: DiffReport }) {
  if (report.tokenTrajectory.length === 0) return null;

  return (
    <TableContainer component={Paper} variant="outlined" sx={{ mt: 2 }}>
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem' }}>Turn</TableCell>
            <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem' }} align="right">
              A Input
            </TableCell>
            <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem' }} align="right">
              A Output
            </TableCell>
            <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem' }} align="right">
              B Input
            </TableCell>
            <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem' }} align="right">
              B Output
            </TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {report.tokenTrajectory.map((entry) => (
            <TableRow key={entry.turn}>
              <TableCell sx={{ fontSize: '0.7rem' }}>{entry.turn}</TableCell>
              <TableCell align="right" sx={{ fontSize: '0.7rem', fontFamily: monospaceFontFamily }}>
                {entry.aInput ?? '-'}
              </TableCell>
              <TableCell align="right" sx={{ fontSize: '0.7rem', fontFamily: monospaceFontFamily }}>
                {entry.aOutput ?? '-'}
              </TableCell>
              <TableCell align="right" sx={{ fontSize: '0.7rem', fontFamily: monospaceFontFamily }}>
                {entry.bInput ?? '-'}
              </TableCell>
              <TableCell align="right" sx={{ fontSize: '0.7rem', fontFamily: monospaceFontFamily }}>
                {entry.bOutput ?? '-'}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}

// ---------------------------------------------------------------------------
// Server-side decoded-prompt diff
// ---------------------------------------------------------------------------

const UNSCOPED = '<unscoped>';

/** Read a header value from either the array-of-{name,values} or object-map form. */
function headerValue(headers: unknown, name: string): string | null {
  if (!headers || typeof headers !== 'object') return null;
  const lower = name.toLowerCase();
  if (Array.isArray(headers)) {
    for (const h of headers) {
      if (h && typeof h === 'object') {
        const e = h as Record<string, unknown>;
        if (typeof e['name'] === 'string' && (e['name'] as string).toLowerCase() === lower) {
          const v = e['values'];
          if (Array.isArray(v) && v.length > 0) return String(v[0]);
        }
      }
    }
    return null;
  }
  for (const [k, v] of Object.entries(headers as Record<string, unknown>)) {
    if (k.toLowerCase() === lower) {
      if (Array.isArray(v) && v.length > 0) return String(v[0]);
      if (typeof v === 'string') return v;
    }
  }
  return null;
}

function stripPort(host: string): string {
  if (host.startsWith('[')) {
    const close = host.indexOf(']');
    if (close !== -1) return host.slice(1, close);
  }
  const colon = host.lastIndexOf(':');
  return colon > 0 ? host.slice(0, colon) : host;
}

/**
 * Derive the upstream host of a session, which is how the server-side diff
 * (`/mockserver/llm/diffRuns`) groups captured traffic. Unscoped sessions are
 * already grouped by host (their isolationKey IS the host); scoped sessions
 * derive it from the first request's Host header.
 */
function sessionHost(session: Session): string | null {
  if (session.scenarioName === UNSCOPED && session.isolationKey && session.isolationKey !== UNSCOPED) {
    return stripPort(session.isolationKey);
  }
  for (const req of session.requests) {
    const httpRequest = req.item.value['httpRequest'] as Record<string, unknown> | undefined;
    const host = headerValue(httpRequest?.['headers'], 'host');
    if (host) return stripPort(host);
  }
  return null;
}

function ChangeTypeChip({ changeType }: { changeType: string }) {
  const color: 'success' | 'error' | 'warning' | 'default' =
    changeType === 'ADDED' ? 'success'
      : changeType === 'REMOVED' ? 'error'
        : changeType === 'MODIFIED' ? 'warning'
          : 'default';
  return (
    <Chip label={changeType} size="small" color={color} variant="outlined" sx={{ height: 18, fontSize: '0.6rem' }} />
  );
}

function ServerDiffView({ result }: { result: RunDiffResult }) {
  return (
    <Box>
      <Alert severity={result.promptChanged ? 'warning' : 'success'} sx={{ mb: 2 }}>
        <Typography variant="subtitle2" sx={{ fontWeight: 700, fontSize: '0.8rem' }}>
          {result.promptChanged ? 'PROMPTS CHANGED' : 'PROMPTS IDENTICAL'}
          {' — '}
          {result.messageCountBefore} → {result.messageCountAfter} message(s)
        </Typography>
      </Alert>

      {(result.toolCallsAdded.length > 0 || result.toolCallsRemoved.length > 0) && (
        <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap', mb: 2 }}>
          {result.toolCallsAdded.map((t, i) => (
            <Chip key={`add-${i}`} label={`+ ${t}`} size="small" color="success" variant="outlined" sx={{ fontFamily: monospaceFontFamily }} />
          ))}
          {result.toolCallsRemoved.map((t, i) => (
            <Chip key={`rem-${i}`} label={`− ${t}`} size="small" color="error" variant="outlined" sx={{ fontFamily: monospaceFontFamily }} />
          ))}
        </Box>
      )}

      <TableContainer component={Paper} variant="outlined">
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem' }}>Change</TableCell>
              <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem' }}>Role</TableCell>
              <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem' }}>Before</TableCell>
              <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem' }}>After</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {result.messageDiffs.map((m, i) => (
              <TableRow key={i}>
                <TableCell><ChangeTypeChip changeType={m.changeType} /></TableCell>
                <TableCell sx={{ fontSize: '0.7rem' }}>{m.role ?? '-'}</TableCell>
                <TableCell sx={{ fontSize: '0.7rem', fontFamily: monospaceFontFamily, whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxWidth: 260 }}>
                  {m.beforeText ?? ''}
                </TableCell>
                <TableCell sx={{ fontSize: '0.7rem', fontFamily: monospaceFontFamily, whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxWidth: 260 }}>
                  {m.afterText ?? ''}
                </TableCell>
              </TableRow>
            ))}
            {result.messageDiffs.length === 0 && (
              <TableRow>
                <TableCell colSpan={4}>
                  <Typography variant="body2" color="text.secondary">No message-level differences.</Typography>
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>

      {result.tokenDelta && (
        <>
          <Typography variant="subtitle2" sx={{ fontSize: '0.8rem', fontWeight: 600, mt: 2 }}>
            Token / Cost Delta
          </Typography>
          <TableContainer component={Paper} variant="outlined" sx={{ mt: 1 }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem' }}>Metric</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.7rem' }}>Before</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.7rem' }}>After</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.7rem' }}>Delta</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {([
                  ['Input tokens', result.tokenDelta.inputTokensBefore, result.tokenDelta.inputTokensAfter, result.tokenDelta.inputTokensDelta],
                  ['Output tokens', result.tokenDelta.outputTokensBefore, result.tokenDelta.outputTokensAfter, result.tokenDelta.outputTokensDelta],
                  ['Cost (USD)', result.tokenDelta.costUsdBefore, result.tokenDelta.costUsdAfter, result.tokenDelta.costUsdDelta],
                ] as const).map(([label, before, after, delta]) => (
                  <TableRow key={label}>
                    <TableCell sx={{ fontSize: '0.7rem' }}>{label}</TableCell>
                    <TableCell align="right" sx={{ fontSize: '0.7rem', fontFamily: monospaceFontFamily }}>{before ?? '-'}</TableCell>
                    <TableCell align="right" sx={{ fontSize: '0.7rem', fontFamily: monospaceFontFamily }}>{after ?? '-'}</TableCell>
                    <TableCell align="right" sx={{ fontSize: '0.7rem', fontFamily: monospaceFontFamily }}>{delta ?? '-'}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        </>
      )}
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Main component
// ---------------------------------------------------------------------------

/**
 * Body component — usable inline (inside the Library/Runs tab) without
 * the Dialog chrome.
 */
export function CompareRunsBody() {
  // Compare Runs needs to see both upstream-proxied traffic and mock-matched
  // traffic, since users typically iterate on captured mocks rather than
  // running through a real upstream.
  const proxiedRequests = useDashboardStore((s) => s.proxiedRequests);
  const recordedRequests = useDashboardStore((s) => s.recordedRequests);
  const activeExpectations = useDashboardStore((s) => s.activeExpectations);
  const connectionParams = useConnectionParams();

  // Diff mode: the client-side structural comparison (default) or the
  // server-side decoded-prompt diff (PUT /mockserver/llm/diffRuns).
  const [mode, setMode] = useState<'client' | 'server'>('client');
  const [serverResult, setServerResult] = useState<RunDiffResult | null>(null);
  const [serverError, setServerError] = useState<HumanError | null>(null);
  const [serverBusy, setServerBusy] = useState(false);

  const allRequests = useMemo(
    () => [...proxiedRequests, ...recordedRequests],
    [proxiedRequests, recordedRequests],
  );

  const sessions = useMemo(
    () => groupBySession(allRequests, activeExpectations),
    [allRequests, activeExpectations],
  );

  const [runAKey, setRunAKey] = useState('');
  const [runBKey, setRunBKey] = useState('');

  const sessionKey = useCallback(
    (s: Session) => `${s.scenarioName}::${s.isolationKey}`,
    [],
  );

  const sessionLabel = useCallback(
    (s: Session) => {
      const name = shortenScenarioName(s.scenarioName);
      const isUnscoped = s.scenarioName === '<unscoped>';
      return isUnscoped
        ? `Unscoped (${s.requests.length} requests)`
        : `${name} / ${s.isolationKey} (${s.requests.length} requests)`;
    },
    [],
  );

  const runA = useMemo(
    () => sessions.find((s) => sessionKey(s) === runAKey) ?? null,
    [sessions, runAKey, sessionKey],
  );

  const runB = useMemo(
    () => sessions.find((s) => sessionKey(s) === runBKey) ?? null,
    [sessions, runBKey, sessionKey],
  );

  const report: DiffReport | null = useMemo(() => {
    if (!runA || !runB) return null;
    const trajA = extractTrajectory(runA);
    const trajB = extractTrajectory(runB);
    return compareTrajectories(trajA, trajB);
  }, [runA, runB]);

  const trajA = useMemo(() => (runA ? extractTrajectory(runA) : null), [runA]);
  const trajB = useMemo(() => (runB ? extractTrajectory(runB) : null), [runB]);

  const hostA = useMemo(() => (runA ? sessionHost(runA) : null), [runA]);
  const hostB = useMemo(() => (runB ? sessionHost(runB) : null), [runB]);

  const runServerDiff = useCallback(async () => {
    if (!runA || !runB) return;
    setServerBusy(true);
    setServerError(null);
    setServerResult(null);
    try {
      const before: RunDiffFilter = { host: hostA };
      const after: RunDiffFilter = { host: hostB };
      const result = await diffRuns(connectionParams, before, after);
      setServerResult(result);
    } catch (e) {
      setServerError(humanizeError(e));
    } finally {
      setServerBusy(false);
    }
  }, [runA, runB, hostA, hostB, connectionParams]);

  // Drop a stale server result when the selection changes.
  const selectionKey = `${runAKey}|${runBKey}`;
  const [prevSelectionKey, setPrevSelectionKey] = useState(selectionKey);
  if (selectionKey !== prevSelectionKey) {
    setPrevSelectionKey(selectionKey);
    setServerResult(null);
    setServerError(null);
  }

  return (
    <Box>
      {/* Diff mode toggle */}
      <ToggleButtonGroup
        size="small"
        exclusive
        value={mode}
        onChange={(_, v) => { if (v) setMode(v as 'client' | 'server'); }}
        sx={{ mb: 2 }}
      >
        <ToggleButton value="client" sx={{ textTransform: 'none' }}>Structural (Client)</ToggleButton>
        <ToggleButton value="server" sx={{ textTransform: 'none' }}>Server Diff (Decoded Prompts)</ToggleButton>
      </ToggleButtonGroup>

      {/* Session selectors */}
      <Box sx={{ display: 'flex', gap: 2, mb: 2 }}>
        <TextField
          label="Trace A"
          size="small"
          select
          fullWidth
          value={runAKey}
          onChange={(e) => setRunAKey(e.target.value)}
          slotProps={{
            select: { native: true, displayEmpty: true },
            inputLabel: { shrink: true },
          }}
        >
          <option value="">— select a trace —</option>
          {sessions.map((s) => (
            <option key={sessionKey(s)} value={sessionKey(s)}>
              {sessionLabel(s)}
            </option>
          ))}
        </TextField>
        <TextField
          label="Trace B"
          size="small"
          select
          fullWidth
          value={runBKey}
          onChange={(e) => setRunBKey(e.target.value)}
          slotProps={{
            select: { native: true, displayEmpty: true },
            inputLabel: { shrink: true },
          }}
        >
          <option value="">— select a trace —</option>
          {sessions.map((s) => (
            <option key={sessionKey(s)} value={sessionKey(s)}>
              {sessionLabel(s)}
            </option>
          ))}
        </TextField>
      </Box>

      {/* Empty / partial-selection state */}
      {(!runA || !runB) && (
        <Box sx={{ textAlign: 'center', py: 4 }}>
          <Typography variant="body2" color="text.secondary">
            {!runA && !runB && 'Choose two captured traces to compare.'}
            {runA && !runB && 'Trace A selected — choose Trace B to compare.'}
            {!runA && runB && 'Trace B selected — choose Trace A to compare.'}
          </Typography>
        </Box>
      )}

      {/* Client-side structural comparison */}
      {mode === 'client' && report && trajA && trajB && (
        <>
          <VerdictBanner report={report} />

          {/* Side-by-side tool call chains */}
          <Box sx={{ display: 'flex', gap: 2, mb: 2 }}>
            <ToolCallChain label="Run A" turns={trajA.turns} />
            <ToolCallChain label="Run B" turns={trajB.turns} />
          </Box>

          {/* Token trajectory table */}
          <Typography variant="subtitle2" sx={{ fontSize: '0.8rem', fontWeight: 600, mt: 2 }}>
            Token Usage per Turn
          </Typography>
          <TokenTable report={report} />
        </>
      )}

      {/* Server-side decoded-prompt diff */}
      {mode === 'server' && runA && runB && (
        <>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2, flexWrap: 'wrap' }}>
            <Button
              variant="contained"
              size="small"
              disabled={serverBusy}
              onClick={() => void runServerDiff()}
              startIcon={serverBusy ? <CircularProgress size={16} color="inherit" /> : undefined}
            >
              {serverBusy ? 'Diffing…' : 'Compute Server Diff'}
            </Button>
            <Typography variant="caption" color="text.secondary">
              Decodes and diffs the prompts server-side (grouped by upstream host
              {hostA ? ` — A: ${hostA}` : ''}{hostB ? `, B: ${hostB}` : ''}).
            </Typography>
          </Box>
          {serverError && <HumanErrorAlert error={serverError} sx={{ mb: 2 }} />}
          {serverResult && <ServerDiffView result={serverResult} />}
        </>
      )}
    </Box>
  );
}

export default function CompareRunsDialog({ open, onClose }: CompareRunsDialogProps) {
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  return (
    <Dialog
      open={open}
      onClose={onClose}
      maxWidth="lg"
      fullWidth
      fullScreen={fullScreen}
      aria-labelledby="compare-runs-title"
    >
      <DialogTitle id="compare-runs-title">Compare Runs</DialogTitle>
      <DialogContent dividers>
        <CompareRunsBody />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  );
}
