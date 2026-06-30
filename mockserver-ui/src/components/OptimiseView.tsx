import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Box from '@mui/material/Box';
import Alert from '@mui/material/Alert';
import Paper from '@mui/material/Paper';
import Card from '@mui/material/Card';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Button from '@mui/material/Button';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';
import TextField from '@mui/material/TextField';
import MenuItem from '@mui/material/MenuItem';
import Skeleton from '@mui/material/Skeleton';
import Table from '@mui/material/Table';
import TableHead from '@mui/material/TableHead';
import TableBody from '@mui/material/TableBody';
import TableRow from '@mui/material/TableRow';
import TableCell from '@mui/material/TableCell';
import RefreshIcon from '@mui/icons-material/Refresh';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import DownloadIcon from '@mui/icons-material/Download';
import { useDashboardStore } from '../store';
import { groupBySession } from '../lib/sessionGrouping';
import {
  fetchOptimisationReport,
  fetchOptimisationBrief,
  sortSignalsBySeverity,
  buildVerdictText,
  gradeColor,
  type OptimisationReport,
  type OptimisationSignal,
  type OptimisationVerdict,
  type SignalSeverity,
} from '../lib/optimisation';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import { humanizeError, type HumanError } from '../lib/errorMessage';
import HumanErrorAlert from './HumanErrorAlert';
import { monospaceFontFamily, transitions } from '../theme';
import { trackFeature } from '../lib/analytics';

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

const ALL_SESSIONS = '__ALL__';

function formatCost(usd: number): string {
  if (!Number.isFinite(usd)) return '—';
  if (usd === 0) return '$0.00';
  if (usd < 0.01) return `$${usd.toFixed(6)}`;
  if (usd < 1) return `$${usd.toFixed(4)}`;
  return `$${usd.toFixed(2)}`;
}

function formatMs(ms: number): string {
  if (!Number.isFinite(ms)) return '—';
  if (ms >= 1000) return `${(ms / 1000).toFixed(1)} s`;
  return `${Math.round(ms)} ms`;
}

function severityColor(severity: SignalSeverity | string): 'error' | 'warning' | 'info' | 'default' {
  switch (severity) {
    case 'HIGH': return 'error';
    case 'MEDIUM': return 'warning';
    case 'LOW': return 'info';
    default: return 'default';
  }
}

/** A 0..1 ratio rendered as a whole-number percent, e.g. 0.62 → "62%". */
function formatPercent(ratio: number): string {
  if (!Number.isFinite(ratio)) return '—';
  return `${Math.round(ratio * 100)}%`;
}

/**
 * Return the URL only when it is an absolute http/https URL, otherwise null.
 * Defends an `href` against `javascript:` / `data:` (and other) schemes — the
 * docsUrl comes from the server-rendered report but is still untrusted content.
 */
export function safeHttpUrl(url: unknown): string | null {
  if (typeof url !== 'string') return null;
  const trimmed = url.trim();
  if (trimmed === '') return null;
  try {
    const parsed = new URL(trimmed);
    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? trimmed : null;
  } catch {
    // Relative or malformed URLs throw here; treat as unsafe for an external link.
    return null;
  }
}

// ---------------------------------------------------------------------------
// Session picker options — built from the same grouping the dashboard already
// uses (sessionGrouping.ts), so the keys the user picks line up with what the
// server groups by. The default option requests a report over ALL traffic.
// ---------------------------------------------------------------------------

interface SessionOption {
  /** Value sent as the `session` query param (empty for "all"). */
  value: string;
  label: string;
}

function useSessionOptions(): SessionOption[] {
  const proxiedRequests = useDashboardStore((s) => s.proxiedRequests);
  const recordedRequests = useDashboardStore((s) => s.recordedRequests);
  const activeExpectations = useDashboardStore((s) => s.activeExpectations);

  return useMemo(() => {
    const all = [...proxiedRequests, ...recordedRequests];
    const sessions = groupBySession(all, activeExpectations);
    const options: SessionOption[] = [{ value: ALL_SESSIONS, label: 'All captured LLM traffic' }];
    const seen = new Set<string>();
    for (const session of sessions) {
      // v1 optimisation groups proxied LLM traffic by upstream host. Isolation-
      // scoped (mocked-conversation) sessions are not cost-relevant and the
      // server groups only by host, so we offer host-grouped sessions only —
      // otherwise selecting an isolation-scoped option would silently return an
      // empty report. See docs/code/llm-mocking.md (optimisation export, v1).
      if (session.scenarioName !== '<unscoped>') continue;
      const key = session.isolationKey;
      if (!key || key === '<unscoped>' || seen.has(key)) continue;
      seen.add(key);
      options.push({ value: key, label: key });
    }
    return options;
  }, [proxiedRequests, recordedRequests, activeExpectations]);
}

// ---------------------------------------------------------------------------
// Hero cards
// ---------------------------------------------------------------------------

interface HeroCardProps {
  label: string;
  value: string;
}

function HeroCard({ label, value }: HeroCardProps) {
  return (
    <Card
      elevation={1}
      sx={{
        p: 1.25,
        transition: transitions.forProps(['box-shadow', 'transform']),
        '&:hover': {
          boxShadow: (t) => t.shadows[4],
          transform: 'translateY(-1px)',
        },
      }}
    >
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2, mt: 0.25 }}>{value}</Typography>
    </Card>
  );
}

// ---------------------------------------------------------------------------
// Verdict banner
// ---------------------------------------------------------------------------

/** True when there is nothing to recommend — render the calm grade-A state. */
function isCalmVerdict(verdict: OptimisationVerdict): boolean {
  return gradeColor(verdict.grade) === 'success'
    && verdict.grade.toUpperCase() === 'A'
    && (verdict.totalEstimatedSavingUsd ?? 0) === 0
    && verdict.highCount === 0
    && verdict.mediumCount === 0
    // also require no LOW findings — a LOW signal (e.g. OUTPUT_TOKEN_BLOAT, which
    // reports no $ saving) still lists an opportunity below, so the calm
    // "nothing to recommend" banner would contradict the opportunities panel.
    && verdict.lowCount === 0;
}

function VerdictBanner({ verdict }: { verdict: OptimisationVerdict }) {
  const color = gradeColor(verdict.grade);
  const calm = isCalmVerdict(verdict);
  const pct = formatPercent(verdict.savingFractionOfSpend);
  const money = formatCost(verdict.totalEstimatedSavingUsd);
  const estSuffix = verdict.costIsEstimated ? ' (est.)' : '';

  if (calm) {
    return (
      <Paper variant="outlined" sx={{ p: 1.5, mb: 1.5, display: 'flex', alignItems: 'center', gap: 1.5 }} data-testid="optimise-verdict">
        <Typography variant="h3" sx={{ fontWeight: 800, lineHeight: 1, color: `${color}.main` }}>
          {verdict.grade}
        </Typography>
        <Typography variant="body2" color="text.secondary">
          Grade {verdict.grade} — no optimisation opportunities detected
        </Typography>
      </Paper>
    );
  }

  return (
    <Paper
      variant="outlined"
      sx={{ p: 1.5, mb: 1.5, display: 'flex', alignItems: 'center', gap: 2, borderColor: (t) => t.palette[color].main }}
      data-testid="optimise-verdict"
    >
      <Typography variant="h2" sx={{ fontWeight: 800, lineHeight: 1, color: `${color}.main`, minWidth: 48, textAlign: 'center' }}>
        {verdict.grade}
      </Typography>
      <Box sx={{ minWidth: 0 }}>
        <Typography variant="h6" sx={{ fontWeight: 700, lineHeight: 1.2 }}>
          Est. {money}{estSuffix} recoverable ({pct} of spend)
        </Typography>
        {verdict.rationale && (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
            {verdict.rationale}
          </Typography>
        )}
      </Box>
    </Paper>
  );
}

// ---------------------------------------------------------------------------
// Signals panel
// ---------------------------------------------------------------------------

/** A code block with a small copy-to-clipboard button. */
function SnippetBlock({ label, snippet }: { label: string; snippet: string }) {
  const [copied, setCopied] = useState(false);
  const onCopy = useCallback(() => {
    void navigator.clipboard.writeText(snippet).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  }, [snippet]);
  return (
    <Box sx={{ position: 'relative', mt: 0.5 }}>
      <Tooltip title={copied ? 'Copied!' : `Copy ${label}`}>
        <IconButton
          size="small"
          onClick={onCopy}
          aria-label={`Copy ${label}`}
          sx={{ position: 'absolute', top: 2, right: 2 }}
        >
          <ContentCopyIcon sx={{ fontSize: '0.8rem' }} />
        </IconButton>
      </Tooltip>
      <Box
        component="pre"
        sx={{
          m: 0,
          p: 1,
          pr: 4,
          borderRadius: 1,
          bgcolor: 'action.hover',
          fontFamily: monospaceFontFamily,
          fontSize: '0.7rem',
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
          overflowX: 'auto',
        }}
      >
        {snippet}
      </Box>
    </Box>
  );
}

function SignalCard({ signal }: { signal: OptimisationSignal }) {
  const fix = signal.fix;
  return (
    <Paper variant="outlined" sx={{ p: 1.25, mb: 1 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5, flexWrap: 'wrap' }}>
        <Chip
          size="small"
          label={signal.severity}
          color={severityColor(signal.severity)}
          variant="filled"
          sx={{ height: 20, fontSize: '0.65rem', fontWeight: 700 }}
        />
        <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>{signal.title}</Typography>
        <Box sx={{ flex: 1 }} />
        {signal.estimatedSavingUsd != null && (
          <Chip
            size="small"
            color="success"
            variant="outlined"
            label={`save ~${formatCost(signal.estimatedSavingUsd)}`}
            sx={{ height: 20, fontSize: '0.65rem', fontFamily: monospaceFontFamily }}
          />
        )}
      </Box>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 0.5 }}>{signal.detail}</Typography>
      <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', mb: 0.5 }}>
        {signal.affectedCalls.length > 0 && (
          <Typography variant="caption" color="text.secondary">
            Affected calls: {signal.affectedCalls.join(', ')}
          </Typography>
        )}
        {signal.estimatedWastedInputTokens != null && (
          <Typography variant="caption" color="text.secondary">
            ~{signal.estimatedWastedInputTokens.toLocaleString()} wasted input tokens
          </Typography>
        )}
      </Box>
      {fix ? (
        <Box>
          <Typography variant="body2" sx={{ fontWeight: 700 }}>{fix.summary}</Typography>
          {fix.action && (
            <Typography variant="body2" color="text.secondary" sx={{ mt: 0.25 }}>{fix.action}</Typography>
          )}
          {fix.configSnippet && <SnippetBlock label="config snippet" snippet={fix.configSnippet} />}
          {fix.exampleExpectation && <SnippetBlock label="example expectation" snippet={fix.exampleExpectation} />}
          {(() => {
            const docsHref = safeHttpUrl(fix.docsUrl);
            return docsHref ? (
              <Typography variant="body2" sx={{ mt: 0.5 }}>
                <Box component="a" href={docsHref} target="_blank" rel="noopener noreferrer" sx={{ color: 'primary.main' }}>
                  Learn more
                </Box>
              </Typography>
            ) : null;
          })()}
        </Box>
      ) : (
        <Typography variant="body2" sx={{ fontWeight: 600 }}>
          Recommendation: <Box component="span" sx={{ fontWeight: 400 }}>{signal.recommendation}</Box>
        </Typography>
      )}
    </Paper>
  );
}

// ---------------------------------------------------------------------------
// Main view
// ---------------------------------------------------------------------------

interface OptimiseViewProps {
  connectionParams: ConnectionParams;
}

type LoadState = 'loading' | 'ok' | 'error';

export default function OptimiseView({ connectionParams }: OptimiseViewProps) {
  const sessionOptions = useSessionOptions();
  const [selected, setSelected] = useState<string>(ALL_SESSIONS);
  const [report, setReport] = useState<OptimisationReport | null>(null);
  const [state, setState] = useState<LoadState>('loading');
  const [error, setError] = useState<HumanError | null>(null);

  // Track the live captured-traffic count so we can flag a displayed report as
  // stale when new traffic arrives after it was fetched. The report is fetched
  // once per mount/session-change; without this the cost/verdict silently lags
  // behind newly captured calls. Kept in a ref so `load` need not depend on it
  // (which would otherwise re-fetch on every WebSocket push).
  const proxiedRequests = useDashboardStore((s) => s.proxiedRequests);
  const recordedRequests = useDashboardStore((s) => s.recordedRequests);
  const liveTrafficCount = proxiedRequests.length + recordedRequests.length;
  const liveTrafficCountRef = useRef(liveTrafficCount);
  liveTrafficCountRef.current = liveTrafficCount;
  // Traffic count captured into the currently-displayed report (null until loaded).
  const [loadedTrafficCount, setLoadedTrafficCount] = useState<number | null>(null);
  const [busyAction, setBusyAction] = useState<null | 'copy' | 'copyVerdict' | 'download'>(null);
  const [actionError, setActionError] = useState<HumanError | null>(null);
  const [copied, setCopied] = useState(false);
  const [verdictCopied, setVerdictCopied] = useState(false);

  // Map the picker selection to the query the lib expects (omit for "all").
  const query = useMemo(
    () => (selected === ALL_SESSIONS ? {} : { session: selected }),
    [selected],
  );

  // Runs the fetch and reconciles state. Sets the loading state synchronously so
  // the skeleton shows at once, then resolves to ok/error.
  const load = useCallback(
    (signal?: AbortSignal) => {
      setState('loading');
      setError(null);
      fetchOptimisationReport(connectionParams, query, signal)
        .then((r) => {
          if (signal?.aborted) return;
          setReport(r);
          setState('ok');
          // Snapshot the traffic count this report reflects so later captures mark it stale.
          setLoadedTrafficCount(liveTrafficCountRef.current);
        })
        .catch((e) => {
          if (signal?.aborted) return;
          setReport(null);
          setError(humanizeError(e));
          setState('error');
        });
    },
    [connectionParams, query],
  );

  // Re-fetch on mount and whenever the selected session changes. `load` sets
  // loading state synchronously on purpose so the skeleton appears immediately.
  useEffect(() => {
    const controller = new AbortController();
    // eslint-disable-next-line react-hooks/set-state-in-effect
    load(controller.signal);
    return () => controller.abort();
  }, [load]);

  const handleCopy = useCallback(async () => {
    setBusyAction('copy');
    setActionError(null);
    try {
      const markdown = await fetchOptimisationBrief(connectionParams, query);
      await navigator.clipboard.writeText(markdown);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (e) {
      setActionError(humanizeError(e));
    } finally {
      setBusyAction(null);
    }
  }, [connectionParams, query]);

  // Build a compact plain-text verdict CLIENT-SIDE from the already-loaded JSON
  // report (no fetch) and write it to the clipboard.
  const handleCopyVerdict = useCallback(async () => {
    if (!report) return;
    setBusyAction('copyVerdict');
    setActionError(null);
    try {
      await navigator.clipboard.writeText(buildVerdictText(report));
      setVerdictCopied(true);
      setTimeout(() => setVerdictCopied(false), 2000);
    } catch (e) {
      setActionError(humanizeError(e));
    } finally {
      setBusyAction(null);
    }
  }, [report]);

  const handleDownload = useCallback(async () => {
    setBusyAction('download');
    setActionError(null);
    try {
      const res = await fetchOptimisationReport(connectionParams, query);
      const blob = new Blob([JSON.stringify(res, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      const suffix = selected === ALL_SESSIONS ? 'all' : selected.replace(/[^A-Za-z0-9._-]+/g, '_');
      anchor.download = `optimisation-report-${suffix}.json`;
      document.body.appendChild(anchor);
      anchor.click();
      document.body.removeChild(anchor);
      URL.revokeObjectURL(url);
    } catch (e) {
      setActionError(humanizeError(e));
    } finally {
      setBusyAction(null);
    }
  }, [connectionParams, query, selected]);

  const totals = report?.totals;
  const hasReport = report != null;
  const isEmpty = hasReport && totals != null && totals.callCount === 0;
  const sortedSignals = useMemo(
    () => (report ? sortSignalsBySeverity(report.signals ?? []) : []),
    [report],
  );

  const avgLatencyMs = totals && totals.callCount > 0 ? totals.totalLatencyMs / totals.callCount : 0;

  return (
    <Box sx={{ flex: 1, overflow: 'auto', p: 1.5 }}>
      {/* Header + session picker */}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5, flexWrap: 'wrap' }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>LLM Optimise</Typography>
        <Tooltip title="Captured LLM traffic, analysed for deterministic cost-saving opportunities. Export a brief to paste into any LLM.">
          <Chip size="small" label="LLM cost" variant="outlined" />
        </Tooltip>
        <Box sx={{ flex: 1 }} />
        <TextField
          select
          size="small"
          label="Session"
          value={selected}
          onChange={(e) => setSelected(e.target.value)}
          sx={{ minWidth: 240, '& .MuiInputBase-root': { fontSize: '0.8rem' } }}
          slotProps={{ htmlInput: { 'aria-label': 'Session' } }}
        >
          {sessionOptions.map((opt) => (
            <MenuItem key={opt.value} value={opt.value} sx={{ fontSize: '0.8rem' }}>
              {opt.label}
            </MenuItem>
          ))}
        </TextField>
        <Tooltip title="Refresh report">
          <IconButton size="small" onClick={() => { trackFeature('optimise_run'); load(); }} aria-label="Refresh report">
            <RefreshIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </Box>

      {state === 'error' && error && (
        <HumanErrorAlert error={error} sx={{ mb: 1.5 }} data-testid="optimise-error" />
      )}

      {actionError && (
        <HumanErrorAlert error={actionError} sx={{ mb: 1.5 }} data-testid="optimise-action-error" onClose={() => setActionError(null)} />
      )}

      {state === 'ok' && loadedTrafficCount !== null && liveTrafficCount !== loadedTrafficCount && (
        <Alert
          severity="info"
          data-testid="optimise-stale-banner"
          sx={{ mb: 1.5 }}
          action={
            <Button
              color="inherit"
              size="small"
              onClick={() => { trackFeature('optimise_run'); load(); }}
            >
              Refresh
            </Button>
          }
        >
          New traffic captured since this report — refresh to update.
        </Alert>
      )}

      {state === 'loading' && !report && (
        <Box data-testid="optimise-loading">
          <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))', gap: 1, mb: 1.5 }}>
            {[0, 1, 2, 3, 4].map((i) => (
              <Card key={i} elevation={1} sx={{ p: 1.25 }}>
                <Skeleton variant="text" width="55%" height={14} />
                <Skeleton variant="text" width="70%" height={32} />
              </Card>
            ))}
          </Box>
        </Box>
      )}

      {isEmpty && (
        <Paper variant="outlined" sx={{ p: 3, textAlign: 'center' }}>
          <Typography variant="body2" color="text.secondary">No LLM traffic captured</Typography>
          <Typography variant="caption" color="text.secondary" sx={{ mt: 0.5, display: 'block' }}>
            Proxy LLM calls through MockServer, then return here to analyse them for cost-saving opportunities.
          </Typography>
        </Paper>
      )}

      {hasReport && totals && !isEmpty && (
        <>
          {/* Verdict banner — A–F grade + "$X recoverable" headline */}
          {report.verdict && <VerdictBanner verdict={report.verdict} />}

          {/* Hero cards */}
          <Box
            sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))', gap: 1, mb: 1.5 }}
            data-testid="optimise-hero"
          >
            <HeroCard
              label={totals.costIsEstimated ? 'Total cost (est.)' : 'Total cost'}
              value={formatCost(totals.estimatedCostUsd)}
            />
            <HeroCard label="Input tokens" value={totals.inputTokens.toLocaleString()} />
            <HeroCard label="Output tokens" value={totals.outputTokens.toLocaleString()} />
            <HeroCard label="Calls" value={totals.callCount.toLocaleString()} />
            <HeroCard label="Avg latency" value={formatMs(avgLatencyMs)} />
            <HeroCard label="Cache hit" value={formatPercent(totals.cacheHitRatio)} />
            <HeroCard label="One-shot" value={formatPercent(totals.oneShotRate)} />
          </Box>

          {/* Provider / model summary + actions */}
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5, flexWrap: 'wrap' }}>
            {(report.session.providers ?? []).map((p) => (
              <Chip key={`prov-${p}`} size="small" variant="outlined" label={p} sx={{ height: 20, fontSize: '0.65rem' }} />
            ))}
            {(report.session.models ?? []).map((m) => (
              <Chip key={`model-${m}`} size="small" variant="outlined" color="primary" label={m} sx={{ height: 20, fontSize: '0.65rem', fontFamily: monospaceFontFamily }} />
            ))}
            <Box sx={{ flex: 1 }} />
            <Button
              variant="outlined"
              size="small"
              startIcon={<ContentCopyIcon sx={{ fontSize: '0.875rem' }} />}
              onClick={() => void handleCopyVerdict()}
              disabled={busyAction !== null}
            >
              {verdictCopied ? 'Copied!' : busyAction === 'copyVerdict' ? 'Copying…' : 'Copy verdict'}
            </Button>
            <Button
              variant="outlined"
              size="small"
              startIcon={<ContentCopyIcon sx={{ fontSize: '0.875rem' }} />}
              onClick={() => void handleCopy()}
              disabled={busyAction !== null}
            >
              {copied ? 'Copied!' : busyAction === 'copy' ? 'Copying…' : 'Copy optimisation brief'}
            </Button>
            <Button
              variant="contained"
              size="small"
              startIcon={<DownloadIcon sx={{ fontSize: '0.875rem' }} />}
              onClick={() => void handleDownload()}
              disabled={busyAction !== null}
            >
              {busyAction === 'download' ? 'Downloading…' : 'Download bundle'}
            </Button>
          </Box>

          {/* Detected opportunities */}
          <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }}>
            <Typography variant="subtitle2" sx={{ fontWeight: 700, mb: 1 }}>
              Detected Opportunities {sortedSignals.length > 0 && `(${sortedSignals.length})`}
            </Typography>
            {sortedSignals.length === 0 ? (
              <Typography variant="body2" color="text.secondary">
                No optimisation opportunities detected for this session.
              </Typography>
            ) : (
              <Box data-testid="optimise-signals">
                {sortedSignals.map((signal, i) => (
                  <SignalCard key={`${signal.id}-${i}`} signal={signal} />
                ))}
              </Box>
            )}
          </Paper>

          {/* Per-call table */}
          <Paper variant="outlined" sx={{ p: 1.25 }}>
            <Typography variant="subtitle2" sx={{ fontWeight: 700, mb: 1 }}>Calls</Typography>
            <Box sx={{ overflowX: 'auto' }}>
              <Table size="small" aria-label="Per-call breakdown" data-testid="optimise-call-table">
                <TableHead>
                  <TableRow>
                    <TableCell>#</TableCell>
                    <TableCell>Model</TableCell>
                    <TableCell align="right">In tok</TableCell>
                    <TableCell align="right">Out tok</TableCell>
                    <TableCell align="right">Cost</TableCell>
                    <TableCell align="right">Latency</TableCell>
                    <TableCell align="right">Tools</TableCell>
                    <TableCell>Finish</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {(report.calls ?? []).map((call) => (
                    <TableRow key={call.index} hover>
                      <TableCell>{call.index}</TableCell>
                      <TableCell sx={{ fontFamily: monospaceFontFamily, fontSize: '0.72rem' }}>{call.model}</TableCell>
                      <TableCell align="right">{call.inputTokens.toLocaleString()}</TableCell>
                      <TableCell align="right">{call.outputTokens.toLocaleString()}</TableCell>
                      <TableCell align="right">{formatCost(call.estimatedCostUsd)}</TableCell>
                      <TableCell align="right">{formatMs(call.latencyMs)}</TableCell>
                      <TableCell align="right">{call.toolCalls?.length ?? 0}</TableCell>
                      <TableCell>{call.finishReason ?? '—'}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Box>
          </Paper>
        </>
      )}
    </Box>
  );
}
