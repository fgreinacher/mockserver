import { useCallback, useEffect, useRef, useState } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Alert from '@mui/material/Alert';
import AlertTitle from '@mui/material/AlertTitle';
import Button from '@mui/material/Button';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import TextField from '@mui/material/TextField';
import Tabs from '@mui/material/Tabs';
import Tab from '@mui/material/Tab';
import Checkbox from '@mui/material/Checkbox';
import FormControlLabel from '@mui/material/FormControlLabel';
import FormGroup from '@mui/material/FormGroup';
import MenuItem from '@mui/material/MenuItem';
import RefreshIcon from '@mui/icons-material/Refresh';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import EditIcon from '@mui/icons-material/Edit';
import BlockIcon from '@mui/icons-material/Block';
import DeleteIcon from '@mui/icons-material/Delete';
import AddIcon from '@mui/icons-material/Add';
import StopIcon from '@mui/icons-material/Stop';
import ClearAllIcon from '@mui/icons-material/ClearAll';
import FiberManualRecordIcon from '@mui/icons-material/FiberManualRecord';
import CallReceivedIcon from '@mui/icons-material/CallReceived';
import CallMadeIcon from '@mui/icons-material/CallMade';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import {
  listBreakpointMatchers,
  registerBreakpointMatcher,
  removeBreakpointMatcher,
  clearBreakpointMatchers,
  type BreakpointMatcherEntry,
  type MatcherPhase,
} from '../lib/breakpoints';
import {
  getBreakpointCallbackClient,
  type PausedItem,
  type CallbackClientState,
  type PausedStreamFrame,
  type StreamFrameDecision,
} from '../lib/breakpointCallbackClient';
import { MultiValueField, SingleValueField } from './FilterPanel';
import type { KeyToMultiValue, KeyToValue } from '../types';
import ConfirmDialog from './ConfirmDialog';
import TruncatedText from './TruncatedText';
import { useAutoRefresh } from '../hooks/useAutoRefresh';
import { humanizeError } from '../lib/errorMessage';
import { useDashboardStore } from '../store';
import { trackFeature } from '../lib/analytics';

const MATCHERS_POLL_INTERVAL_MS = 5000;

interface BreakpointsPanelProps {
  connectionParams: ConnectionParams;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const HTTP_METHODS = ['', 'GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS'] as const;

const ALL_PHASES: { value: MatcherPhase; label: string }[] = [
  { value: 'REQUEST', label: 'Request' },
  { value: 'RESPONSE', label: 'Response' },
  { value: 'RESPONSE_STREAM', label: 'Response stream frames' },
  { value: 'INBOUND_STREAM', label: 'Inbound stream frames' },
];

function phaseChipColor(phase: string): 'default' | 'primary' | 'secondary' | 'info' | 'warning' {
  switch (phase) {
    case 'REQUEST': return 'default';
    case 'RESPONSE': return 'secondary';
    case 'RESPONSE_STREAM': return 'info';
    case 'INBOUND_STREAM': return 'warning';
    default: return 'default';
  }
}

let nextItemKey = 0;

// Upper bound on locally-held paused items. A breakpoint matcher with a broad
// pattern (e.g. path `.*`) pauses every exchange, and each held item retains a
// full request/response in React state. Without a cap a busy server would grow
// this array until the tab runs out of memory, so we drop the oldest items
// beyond this bound (each dropped exchange remains paused server-side and will
// auto-resolve via the server's breakpoint timeout).
const MAX_PAUSED_ITEMS = 500;

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function BreakpointsPanel({ connectionParams }: BreakpointsPanelProps) {
  const [tab, setTab] = useState(0);

  // -- Matchers state --
  const [matchers, setMatchers] = useState<BreakpointMatcherEntry[]>([]);
  const [matchersError, setMatchersError] = useState<string | null>(null);

  // Registration form
  const [formMethod, setFormMethod] = useState('');
  const [formPath, setFormPath] = useState('');
  const [formHeaders, setFormHeaders] = useState<KeyToMultiValue[]>([{ name: '', values: [''] }]);
  const [formQueryParams, setFormQueryParams] = useState<KeyToMultiValue[]>([{ name: '', values: [''] }]);
  const [formCookies, setFormCookies] = useState<KeyToValue[]>([{ name: '', value: '' }]);
  const [formPhases, setFormPhases] = useState<Set<MatcherPhase>>(new Set(['REQUEST', 'RESPONSE']));
  const [formSkipCount, setFormSkipCount] = useState('');
  const [formBusy, setFormBusy] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  // -- Callback WS state --
  const [wsState, setWsState] = useState<CallbackClientState>('disconnected');
  const [clientId, setClientId] = useState<string | null>(null);
  const clientRef = useRef(getBreakpointCallbackClient());

  // -- Live paused items (from WS) --
  const [pausedItems, setPausedItems] = useState<(PausedItem & { key: number; receivedAt: number })[]>([]);

  // -- Action state --
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  // Confirmation dialog for destructive actions
  const [confirmOpen, setConfirmOpen] = useState(false);

  // -- Modify dialogs --
  const [modifyTarget, setModifyTarget] = useState<(PausedItem & { key: number }) | null>(null);
  const [modifyJson, setModifyJson] = useState('');
  const [modifyError, setModifyError] = useState<string | null>(null);

  // Frame modify/inject
  const [frameModifyTarget, setFrameModifyTarget] = useState<(PausedItem & { key: number }) | null>(null);
  const [frameModifyBody, setFrameModifyBody] = useState('');
  const [frameModifyError, setFrameModifyError] = useState<string | null>(null);

  const [frameInjectTarget, setFrameInjectTarget] = useState<(PausedItem & { key: number }) | null>(null);
  const [frameInjectBody, setFrameInjectBody] = useState('');
  const [frameInjectError, setFrameInjectError] = useState<string | null>(null);

  // -------------------------------------------------------------------------
  // Callback WS lifecycle
  // -------------------------------------------------------------------------

  useEffect(() => {
    const client = clientRef.current;

    client.onStateChange((state) => {
      setWsState(state);
      setClientId(client.clientId);
      if (state === 'disconnected') {
        // Held items reference the previous clientId's correlationIds. On
        // reconnect the server issues a fresh clientId, so these can never be
        // resolved (continue/modify/abort) from the UI again — drop them rather
        // than leak them into the list forever.
        setPausedItems([]);
      }
    });

    client.onPausedItem((item) => {
      // receivedAt is captured once, at arrival, and never changes — it gives the
      // lists a stable, monotonic sort key so items keep a fixed position by the
      // time their request/response/frame was received, instead of reshuffling.
      const keyed = { ...item, key: nextItemKey++, receivedAt: Date.now() };
      setPausedItems((prev) => {
        const next = [...prev, keyed];
        return next.length > MAX_PAUSED_ITEMS ? next.slice(next.length - MAX_PAUSED_ITEMS) : next;
      });
    });

    // Seed from the singleton so a re-mount after a tab change immediately
    // reflects the live connection + clientId (connect() below is idempotent and
    // will not fire a fresh state-change event when already connected).
    setWsState(client.state);
    setClientId(client.clientId);

    client.connect(connectionParams);

    // Intentionally NO disconnect on unmount: the callback WebSocket is an
    // app-lifetime singleton. Keeping it open across tab changes preserves the
    // server clientId and the breakpoint matchers registered under it, so
    // breakpoints stay active and registered when navigating away from and back
    // to this tab.
  }, [connectionParams]);

  // -------------------------------------------------------------------------
  // Matchers polling (auto-refresh the read-only registered-matcher list)
  // -------------------------------------------------------------------------

  const loadMatchers = useCallback(async (signal?: AbortSignal) => {
    try {
      const resp = await listBreakpointMatchers(connectionParams, signal);
      setMatchers(resp.matchers ?? []);
      setMatchersError(null);
    } catch (e) {
      if (signal?.aborted) return;
      setMatchersError(e instanceof Error ? e.message : String(e));
    }
  }, [connectionParams]);

  useAutoRefresh(loadMatchers, { intervalMs: MATCHERS_POLL_INTERVAL_MS });

  // Manual force-refresh (the existing Refresh button) — same fetch, off-cycle.
  const refreshMatchers = useCallback(() => {
    void loadMatchers();
  }, [loadMatchers]);

  // -------------------------------------------------------------------------
  // "Set breakpoint" handoff from a log row
  // -------------------------------------------------------------------------
  // When the user clicks "Set breakpoint" on a log entry, the store carries the
  // request's method + path here. Seed the registration form with it, jump to the
  // Matchers tab so the form is visible, and clear the handoff so it applies once.
  const breakpointPrefill = useDashboardStore((s) => s.pendingBreakpointPrefill);
  const clearBreakpointPrefill = useDashboardStore((s) => s.clearPendingBreakpointPrefill);

  useEffect(() => {
    // Always clear the one-shot hand-off when this effect tears down (unmount or
    // a new prefill), so a prefill can never survive the panel being unmounted
    // mid-apply and get re-applied on a later re-mount.
    if (!breakpointPrefill) return clearBreakpointPrefill;
    const { method, path } = breakpointPrefill;
    // Consuming a one-shot hand-off from the Zustand store IS the legitimate
    // "sync React state from an external system" case the rule exempts; the
    // effect clears the signal at the end so it runs exactly once per hand-off.
    // (Same pattern the Composer uses to consume its pending-edit hand-off.)
    // setTab/setFormMethod/setFormPath are all this same deliberate sync.
    /* eslint-disable react-hooks/set-state-in-effect */
    setTab(0);
    // Only adopt a method the form's dropdown actually offers; otherwise leave it
    // as "(any)" rather than setting an unselectable value.
    if (method && (HTTP_METHODS as readonly string[]).includes(method.toUpperCase())) {
      setFormMethod(method.toUpperCase());
    }
    if (path) setFormPath(path);
    /* eslint-enable react-hooks/set-state-in-effect */
    clearBreakpointPrefill();
    return clearBreakpointPrefill;
  }, [breakpointPrefill, clearBreakpointPrefill]);

  // -------------------------------------------------------------------------
  // Matcher registration
  // -------------------------------------------------------------------------

  const handleRegister = useCallback(async () => {
    if (!clientId) {
      setFormError('Callback WebSocket not connected. Wait for the connection to establish.');
      return;
    }
    if (formPhases.size === 0) {
      setFormError('At least one phase must be selected.');
      return;
    }
    const httpRequest: Record<string, unknown> = {};
    if (formMethod) httpRequest.method = formMethod;
    if (formPath) httpRequest.path = formPath;
    const validHeaders = formHeaders
      .map((h) => ({ name: h.name, values: h.values.filter((v) => v !== '') }))
      .filter((h) => h.name && h.values.length > 0);
    if (validHeaders.length > 0) httpRequest.headers = validHeaders;
    const validParams = formQueryParams
      .map((q) => ({ name: q.name, values: q.values.filter((v) => v !== '') }))
      .filter((q) => q.name && q.values.length > 0);
    if (validParams.length > 0) httpRequest.queryStringParameters = validParams;
    const validCookies = formCookies.filter((c) => c.name && c.value);
    if (validCookies.length > 0) httpRequest.cookies = validCookies;
    // If no fields specified, it matches everything
    if (Object.keys(httpRequest).length === 0) {
      httpRequest.path = '.*';
    }

    // Optional Nth-hit / skip-count: pause only after this many matching hits.
    let skipCount: number | undefined;
    if (formSkipCount.trim() !== '') {
      const parsed = Number(formSkipCount);
      if (!Number.isInteger(parsed) || parsed < 0) {
        setFormError('Skip count must be a non-negative integer.');
        return;
      }
      skipCount = parsed;
    }

    setFormBusy(true);
    setFormError(null);
    try {
      await registerBreakpointMatcher(
        connectionParams,
        httpRequest,
        [...formPhases],
        clientId,
        skipCount,
      );
      trackFeature('breakpoint_set');
      refreshMatchers();
      // Reset form
      setFormMethod('');
      setFormPath('');
      setFormHeaders([{ name: '', values: [''] }]);
      setFormQueryParams([{ name: '', values: [''] }]);
      setFormCookies([{ name: '', value: '' }]);
      setFormSkipCount('');
    } catch (e) {
      setFormError(humanizeError(e).message);
    } finally {
      setFormBusy(false);
    }
  }, [connectionParams, clientId, formMethod, formPath, formHeaders, formQueryParams, formCookies, formPhases, formSkipCount, refreshMatchers]);

  const handleRemoveMatcher = useCallback(async (id: string) => {
    setBusy(true);
    setActionError(null);
    try {
      await removeBreakpointMatcher(connectionParams, id);
      refreshMatchers();
    } catch (e) {
      setActionError(humanizeError(e).message);
    } finally {
      setBusy(false);
    }
  }, [connectionParams, refreshMatchers]);

  const handleClearMatchers = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    try {
      await clearBreakpointMatchers(connectionParams);
      refreshMatchers();
    } catch (e) {
      setActionError(humanizeError(e).message);
    } finally {
      setBusy(false);
    }
  }, [connectionParams, refreshMatchers]);

  const togglePhase = useCallback((phase: MatcherPhase) => {
    setFormPhases((prev) => {
      const next = new Set(prev);
      if (next.has(phase)) {
        next.delete(phase);
      } else {
        next.add(phase);
      }
      return next;
    });
  }, []);

  // -------------------------------------------------------------------------
  // WS-based resolution (exchanges)
  // -------------------------------------------------------------------------

  const removeItem = useCallback((key: number) => {
    setPausedItems((prev) => prev.filter((item) => item.key !== key));
  }, []);

  const handleContinueExchange = useCallback((item: PausedItem & { key: number }) => {
    const client = clientRef.current;
    if (item.phase === 'REQUEST') {
      client.resolveRequest(item.correlationId, item.request);
    } else if (item.phase === 'RESPONSE') {
      client.resolveResponse(item.correlationId, item.response ?? {});
    }
    removeItem(item.key);
  }, [removeItem]);

  const handleAbortExchange = useCallback((item: PausedItem & { key: number }) => {
    // Abort only genuinely applies to the REQUEST phase, where it sends an
    // HttpResponse instead of forwarding. There is no way to abort a RESPONSE
    // (the upstream response has already been received), so the Abort control is
    // not rendered for RESPONSE-phase items — this is a defensive no-op for them.
    if (item.phase === 'REQUEST') {
      clientRef.current.resolveRequest(item.correlationId, {
        statusCode: 503,
        body: 'Aborted by breakpoint',
      });
      removeItem(item.key);
    }
  }, [removeItem]);

  const openModifyExchangeDialog = useCallback((item: PausedItem & { key: number }) => {
    setModifyTarget(item);
    if (item.phase === 'RESPONSE') {
      setModifyJson(JSON.stringify(item.response ?? {}, null, 2));
    } else if (item.phase === 'REQUEST') {
      setModifyJson(JSON.stringify(item.request ?? {}, null, 2));
    }
    setModifyError(null);
  }, []);

  const handleModifySubmit = useCallback(() => {
    if (!modifyTarget) return;
    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(modifyJson) as Record<string, unknown>;
    } catch {
      setModifyError('Invalid JSON');
      return;
    }
    const client = clientRef.current;
    if (modifyTarget.phase === 'REQUEST') {
      client.resolveRequest(modifyTarget.correlationId, parsed);
    } else if (modifyTarget.phase === 'RESPONSE') {
      client.resolveResponse(modifyTarget.correlationId, parsed);
    }
    removeItem(modifyTarget.key);
    setModifyTarget(null);
  }, [modifyTarget, modifyJson, removeItem]);

  // -------------------------------------------------------------------------
  // WS-based resolution (frames)
  // -------------------------------------------------------------------------

  const resolveFrame = useCallback((item: PausedItem & { key: number }, decision: StreamFrameDecision) => {
    clientRef.current.resolveFrame(decision);
    removeItem(item.key);
  }, [removeItem]);

  const handleFrameContinue = useCallback((item: PausedItem & { key: number }) => {
    if (item.phase !== 'RESPONSE_STREAM' && item.phase !== 'INBOUND_STREAM') return;
    resolveFrame(item, { correlationId: item.frame.correlationId, action: 'CONTINUE' });
  }, [resolveFrame]);

  const handleFrameDrop = useCallback((item: PausedItem & { key: number }) => {
    if (item.phase !== 'RESPONSE_STREAM' && item.phase !== 'INBOUND_STREAM') return;
    resolveFrame(item, { correlationId: item.frame.correlationId, action: 'DROP' });
  }, [resolveFrame]);

  const handleFrameClose = useCallback((item: PausedItem & { key: number }) => {
    if (item.phase !== 'RESPONSE_STREAM' && item.phase !== 'INBOUND_STREAM') return;
    resolveFrame(item, { correlationId: item.frame.correlationId, action: 'CLOSE' });
  }, [resolveFrame]);

  const openFrameModifyDialog = useCallback((item: PausedItem & { key: number }) => {
    setFrameModifyTarget(item);
    if (item.phase === 'RESPONSE_STREAM' || item.phase === 'INBOUND_STREAM') {
      // Decode base64 body for display
      try {
        setFrameModifyBody(atob(item.frame.body));
      } catch {
        setFrameModifyBody(item.frame.body);
      }
    }
    setFrameModifyError(null);
  }, []);

  const handleFrameModifySubmit = useCallback(() => {
    if (!frameModifyTarget) return;
    if (!frameModifyBody) {
      setFrameModifyError('Body is required');
      return;
    }
    if (frameModifyTarget.phase !== 'RESPONSE_STREAM' && frameModifyTarget.phase !== 'INBOUND_STREAM') return;
    resolveFrame(frameModifyTarget, {
      correlationId: frameModifyTarget.frame.correlationId,
      action: 'MODIFY',
      body: btoa(frameModifyBody),
    });
    setFrameModifyTarget(null);
  }, [frameModifyTarget, frameModifyBody, resolveFrame]);

  const openFrameInjectDialog = useCallback((item: PausedItem & { key: number }) => {
    setFrameInjectTarget(item);
    setFrameInjectBody('');
    setFrameInjectError(null);
  }, []);

  const handleFrameInjectSubmit = useCallback(() => {
    if (!frameInjectTarget) return;
    if (!frameInjectBody) {
      setFrameInjectError('Body is required');
      return;
    }
    if (frameInjectTarget.phase !== 'RESPONSE_STREAM' && frameInjectTarget.phase !== 'INBOUND_STREAM') return;
    resolveFrame(frameInjectTarget, {
      correlationId: frameInjectTarget.frame.correlationId,
      action: 'INJECT',
      body: btoa(frameInjectBody),
    });
    setFrameInjectTarget(null);
  }, [frameInjectTarget, frameInjectBody, resolveFrame]);

  // -------------------------------------------------------------------------
  // Derived
  // -------------------------------------------------------------------------

  // Sort by the server-side request timestamp (when MockServer first received the
  // request) so all phases of the same exchange stay grouped together.  Fall back
  // to the client-captured `receivedAt` for servers that do not yet send the header.
  // The monotonic `key` is the final tiebreaker.
  const sortKey = (item: PausedItem & { key: number; receivedAt: number }): number => {
    if (item.phase === 'REQUEST' || item.phase === 'RESPONSE') {
      return item.requestTimestamp ?? item.receivedAt;
    }
    // RESPONSE_STREAM / INBOUND_STREAM
    return (item as unknown as { frame: PausedStreamFrame }).frame.requestTimestamp ?? item.receivedAt;
  };
  const byRequestTime = (
    a: PausedItem & { key: number; receivedAt: number },
    b: PausedItem & { key: number; receivedAt: number },
  ): number => sortKey(a) - sortKey(b) || a.key - b.key;
  const exchangeItems = pausedItems
    .filter((i) => i.phase === 'REQUEST' || i.phase === 'RESPONSE')
    .sort(byRequestTime);
  const frameItems = (
    pausedItems.filter((i) => i.phase === 'RESPONSE_STREAM' || i.phase === 'INBOUND_STREAM') as (PausedItem & { key: number; receivedAt: number; phase: 'RESPONSE_STREAM' | 'INBOUND_STREAM'; frame: PausedStreamFrame })[]
  ).sort(byRequestTime);

  const wsIndicatorColor = wsState === 'connected' ? 'success' : wsState === 'connecting' ? 'warning' : 'error';

  return (
    <Box sx={{ flex: 1, overflow: 'auto', p: 1.5 }}>
      {/* Header */}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5 }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
          Breakpoints
        </Typography>
        <Tooltip title={`Callback WS: ${wsState}${clientId ? ` (${clientId.substring(0, 8)}...)` : ''}`}>
          <FiberManualRecordIcon
            fontSize="small"
            color={wsIndicatorColor as 'success' | 'warning' | 'error'}
            data-testid="ws-indicator"
          />
        </Tooltip>
        {pausedItems.length > 0 && (
          <Chip
            size="small"
            label={`${pausedItems.length} paused`}
            color="warning"
            variant="outlined"
          />
        )}
        <Box sx={{ flex: 1 }} />
      </Box>

      <Tabs value={tab} onChange={(_, v: number) => setTab(v)} sx={{ mb: 1.5 }}>
        <Tab label="Matchers" />
        <Tab
          label={
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
              Live Exchanges
              {exchangeItems.length > 0 && (
                <Chip size="small" label={exchangeItems.length} color="warning" sx={{ height: 18, fontSize: '0.65rem' }} />
              )}
            </Box>
          }
        />
        <Tab
          label={
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
              Live Streams
              {frameItems.length > 0 && (
                <Chip size="small" label={frameItems.length} color="warning" sx={{ height: 18, fontSize: '0.65rem' }} />
              )}
            </Box>
          }
        />
      </Tabs>

      {actionError && (
        <Alert severity="warning" sx={{ mb: 1.5 }} onClose={() => setActionError(null)}>
          {actionError}
        </Alert>
      )}

      {/* ============ TAB 0: Matchers ============ */}
      {tab === 0 && (
        <>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
            Register breakpoint matchers to pause proxied/forwarded exchanges that match. The dashboard resolves them interactively over the callback WebSocket.
          </Typography>
          <Alert severity="info" variant="outlined" sx={{ mb: 1.5, py: 0.25 }}>
            How it works: 1) add a matcher here → 2) send a matching request through MockServer →
            3) it pauses in the <strong>Live Exchanges</strong> tab (or <strong>Live Streams</strong> for stream frames),
            where you continue, modify, or abort it. Registering a matcher needs the callback
            WebSocket connected{wsState === 'connected' ? '' : ` (currently ${wsState})`}.
          </Alert>

          {/* Registration form */}
          <Paper variant="outlined" sx={{ p: 1.5, mb: 1.5 }}>
            <Typography variant="body2" sx={{ fontWeight: 600, mb: 1 }}>
              Register a New Breakpoint Matcher
            </Typography>
            <Box sx={{ display: 'flex', gap: 1, mb: 1, flexWrap: 'wrap' }}>
              <TextField
                select
                label="Method"
                value={formMethod}
                onChange={(e) => setFormMethod(e.target.value)}
                size="small"
                sx={{ width: 140, flexShrink: 0 }}
                slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}
              >
                {HTTP_METHODS.map((m) => (
                  <MenuItem key={m || '__any'} value={m}>
                    {m || '(any)'}
                  </MenuItem>
                ))}
              </TextField>
              <TextField
                label="Path (regex)"
                value={formPath}
                onChange={(e) => setFormPath(e.target.value)}
                size="small"
                placeholder="/api/.*"
                sx={{ flex: 1, minWidth: 200 }}
              />
              <TextField
                label="Skip count"
                value={formSkipCount}
                onChange={(e) => setFormSkipCount(e.target.value)}
                size="small"
                type="number"
                placeholder="0"
                title="Pause only after this many matching hits (0 / blank = pause every time)"
                slotProps={{ htmlInput: { min: 0, step: 1 }, inputLabel: { shrink: true } }}
                sx={{ width: 120, flexShrink: 0 }}
              />
            </Box>
            <MultiValueField label="Headers" items={formHeaders} onChange={setFormHeaders} disabled={formBusy} />
            <MultiValueField label="Query parameters" items={formQueryParams} onChange={setFormQueryParams} disabled={formBusy} />
            <SingleValueField label="Cookies" items={formCookies} onChange={setFormCookies} disabled={formBusy} />
            <FormGroup row sx={{ mb: 1 }}>
              {ALL_PHASES.map((p) => (
                <FormControlLabel
                  key={p.value}
                  control={
                    <Checkbox
                      size="small"
                      checked={formPhases.has(p.value)}
                      onChange={() => togglePhase(p.value)}
                    />
                  }
                  label={<Typography variant="body2">{p.label}</Typography>}
                />
              ))}
            </FormGroup>
            {formError && (
              <Alert severity="error" sx={{ mb: 1 }}>
                {formError}
              </Alert>
            )}
            <Button
              variant="contained"
              size="small"
              startIcon={<AddIcon />}
              disabled={formBusy || wsState !== 'connected'}
              onClick={() => void handleRegister()}
            >
              Register Matcher
            </Button>
          </Paper>

          {/* Matcher list */}
          {matchersError && (
            <Alert
              severity={matchersError.includes('404') || matchersError.includes('Not Found') ? 'info' : 'error'}
              sx={{ mb: 1.5 }}
            >
              <AlertTitle>
                {matchersError.includes('404') || matchersError.includes('Not Found')
                  ? 'Breakpoint matchers not available'
                  : 'Could not load matchers'}
              </AlertTitle>
              {matchersError.includes('404') || matchersError.includes('Not Found')
                ? 'The connected server does not support breakpoint matchers. This feature requires a newer version of MockServer.'
                : matchersError}
            </Alert>
          )}

          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
            <Typography variant="body2" sx={{ fontWeight: 600 }}>
              Registered Matchers ({matchers.length})
            </Typography>
            <Box sx={{ flex: 1 }} />
            <Tooltip title="Refresh matchers">
              <IconButton size="small" onClick={refreshMatchers} aria-label="Refresh matchers">
                <RefreshIcon fontSize="small" />
              </IconButton>
            </Tooltip>
            {matchers.length > 0 && (
              <Tooltip title="Clear all matchers">
                <span>
                  <IconButton size="small" onClick={() => setConfirmOpen(true)} disabled={busy} aria-label="Clear all matchers">
                    <ClearAllIcon fontSize="small" />
                  </IconButton>
                </span>
              </Tooltip>
            )}
          </Box>

          <Paper variant="outlined" sx={{ p: 1.25 }}>
            {matchers.length === 0 ? (
              <Typography variant="body2" color="text.secondary" sx={{ p: 2, textAlign: 'center' }}>
                No matchers registered yet. Add one above, then send a matching request — it will
                pause in the Live Exchanges tab for you to inspect and resolve.
              </Typography>
            ) : (
              <TableContainer>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>ID</TableCell>
                      <TableCell>Matcher</TableCell>
                      <TableCell>Phases</TableCell>
                      <TableCell align="right">Skip</TableCell>
                      <TableCell>Owner</TableCell>
                      <TableCell align="right">Actions</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {matchers.map((m) => (
                      <TableRow key={m.id}>
                        <TableCell>
                          <TruncatedText text={m.id} maxWidth={120} sx={{ fontFamily: 'monospace', fontSize: '0.75rem' }} />
                        </TableCell>
                        <TableCell>
                          <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                            {m.httpRequest ? `${(m.httpRequest.method as string) ?? '*'} ${(m.httpRequest.path as string) ?? '/'}` : '*'}
                          </Typography>
                        </TableCell>
                        <TableCell>
                          <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}>
                            {(m.phases ?? []).map((p) => (
                              <Chip
                                key={p}
                                size="small"
                                label={p}
                                color={phaseChipColor(p)}
                                variant="outlined"
                                sx={{ height: 20, fontSize: '0.6rem' }}
                              />
                            ))}
                          </Box>
                        </TableCell>
                        <TableCell align="right">
                          <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                            {m.skipCount && m.skipCount > 0 ? m.skipCount : '-'}
                          </Typography>
                        </TableCell>
                        <TableCell>
                          <TruncatedText text={m.clientId ?? '-'} maxWidth={100} sx={{ fontFamily: 'monospace', fontSize: '0.75rem' }} />
                        </TableCell>
                        <TableCell align="right">
                          <Tooltip title="Remove matcher">
                            <span>
                              <IconButton
                                size="small"
                                color="error"
                                disabled={busy}
                                onClick={() => void handleRemoveMatcher(m.id)}
                                aria-label={`Remove ${m.id}`}
                              >
                                <DeleteIcon fontSize="small" />
                              </IconButton>
                            </span>
                          </Tooltip>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
          </Paper>
        </>
      )}

      {/* ============ TAB 1: Live Exchanges ============ */}
      {tab === 1 && (
        <>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
            Paused request/response exchanges dispatched over the callback WebSocket. Resolve each interactively.
          </Typography>

          {wsState !== 'connected' && (
            <Alert severity="info" sx={{ mb: 1.5 }}>
              Callback WebSocket is {wsState}. Paused exchanges will appear here once connected and matchers are registered.
            </Alert>
          )}

          <Paper variant="outlined" sx={{ p: 1.25 }}>
            {exchangeItems.length === 0 ? (
              <Typography variant="body2" color="text.secondary" sx={{ p: 2, textAlign: 'center' }}>
                No paused exchanges. Register a breakpoint matcher (Matchers tab) to pause matching forwarded requests or responses.
              </Typography>
            ) : (
              <TableContainer>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>Phase</TableCell>
                      <TableCell>Method / Status</TableCell>
                      <TableCell>Path / Reason</TableCell>
                      <TableCell>Breakpoint</TableCell>
                      <TableCell align="right">Actions</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {exchangeItems.map((item) => {
                      const isResponse = item.phase === 'RESPONSE';
                      return (
                        <TableRow key={item.key}>
                          <TableCell>
                            <Chip
                              size="small"
                              label={item.phase}
                              color={isResponse ? 'secondary' : 'default'}
                              variant="outlined"
                              sx={{ height: 20, fontSize: '0.65rem' }}
                            />
                          </TableCell>
                          <TableCell>
                            <Chip
                              size="small"
                              label={isResponse && item.response
                                ? String(item.response.statusCode ?? '?')
                                : ((item.phase === 'REQUEST' ? item.request?.method : '?') ?? '?')}
                              color={isResponse ? 'secondary' : 'primary'}
                              variant="outlined"
                              sx={{ height: 20, fontSize: '0.65rem' }}
                            />
                          </TableCell>
                          <TableCell>
                            <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                              {isResponse && item.response
                                ? (item.response.reasonPhrase ?? '-')
                                : ((item.phase === 'REQUEST' ? item.request?.path : '-') ?? '/')}
                            </Typography>
                          </TableCell>
                          <TableCell>
                            <TruncatedText text={item.breakpointId ?? '-'} maxWidth={100} sx={{ fontFamily: 'monospace', fontSize: '0.75rem' }} />
                          </TableCell>
                          <TableCell align="right">
                            <Box sx={{ display: 'flex', gap: 0.5, justifyContent: 'flex-end' }}>
                              <Tooltip title="Continue (forward unchanged)">
                                <span>
                                  <IconButton
                                    size="small"
                                    color="success"
                                    disabled={busy}
                                    onClick={() => handleContinueExchange(item)}
                                    aria-label={`Continue ${item.key}`}
                                  >
                                    <PlayArrowIcon fontSize="small" />
                                  </IconButton>
                                </span>
                              </Tooltip>
                              <Tooltip title={isResponse ? 'Modify response' : 'Modify request'}>
                                <span>
                                  <IconButton
                                    size="small"
                                    color="info"
                                    disabled={busy}
                                    onClick={() => openModifyExchangeDialog(item)}
                                    aria-label={`Modify ${item.key}`}
                                  >
                                    <EditIcon fontSize="small" />
                                  </IconButton>
                                </span>
                              </Tooltip>
                              {/* Abort only genuinely applies to the REQUEST phase (send an
                                  error response instead of forwarding). A RESPONSE has already
                                  been received upstream and cannot be aborted, so we render a
                                  clearly-labelled disabled control there instead of a red button
                                  that silently behaves like Continue. */}
                              {isResponse ? (
                                <Tooltip title="Abort not applicable — the response has already been received and can only be continued or modified">
                                  <span>
                                    <IconButton
                                      size="small"
                                      color="error"
                                      disabled
                                      aria-label={`Abort ${item.key} (not applicable for responses)`}
                                    >
                                      <BlockIcon fontSize="small" />
                                    </IconButton>
                                  </span>
                                </Tooltip>
                              ) : (
                                <Tooltip title="Abort (do not forward)">
                                  <span>
                                    <IconButton
                                      size="small"
                                      color="error"
                                      disabled={busy}
                                      onClick={() => handleAbortExchange(item)}
                                      aria-label={`Abort ${item.key}`}
                                    >
                                      <BlockIcon fontSize="small" />
                                    </IconButton>
                                  </span>
                                </Tooltip>
                              )}
                            </Box>
                          </TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
          </Paper>
        </>
      )}

      {/* ============ TAB 2: Live Streams ============ */}
      {tab === 2 && (
        <>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
            Paused stream frames dispatched over the callback WebSocket. Continue, modify, drop, inject, or close each frame.
          </Typography>

          {wsState !== 'connected' && (
            <Alert severity="info" sx={{ mb: 1.5 }}>
              Callback WebSocket is {wsState}. Paused stream frames will appear here once connected.
            </Alert>
          )}

          <Paper variant="outlined" sx={{ p: 1.25 }}>
            {frameItems.length === 0 ? (
              <Typography variant="body2" color="text.secondary" sx={{ p: 2, textAlign: 'center' }}>
                No paused stream frames. Register a breakpoint matcher with stream phases (Matchers tab) to pause matching streaming frames.
              </Typography>
            ) : (
              <TableContainer>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>Phase</TableCell>
                      <TableCell>Direction</TableCell>
                      <TableCell>Stream</TableCell>
                      <TableCell>Seq</TableCell>
                      <TableCell>Method</TableCell>
                      <TableCell>Path</TableCell>
                      <TableCell>Body (Base64)</TableCell>
                      <TableCell align="right">Actions</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {frameItems.map((item) => (
                      <TableRow key={item.key}>
                        <TableCell>
                          <Chip
                            size="small"
                            label={item.phase}
                            color={phaseChipColor(item.phase)}
                            variant="outlined"
                            sx={{ height: 20, fontSize: '0.6rem' }}
                          />
                        </TableCell>
                        <TableCell>
                          <Chip
                            size="small"
                            icon={item.frame.direction === 'INBOUND' ? <CallReceivedIcon /> : <CallMadeIcon />}
                            label={item.frame.direction === 'INBOUND' ? 'Inbound' : 'Outbound'}
                            color={item.frame.direction === 'INBOUND' ? 'info' : 'default'}
                            variant="outlined"
                            sx={{ height: 20, fontSize: '0.6rem' }}
                          />
                        </TableCell>
                        <TableCell>
                          <TruncatedText text={item.frame.streamId} maxWidth={120} sx={{ fontFamily: 'monospace', fontSize: '0.75rem' }} />
                        </TableCell>
                        <TableCell>
                          <Chip size="small" label={`#${item.frame.sequenceNumber}`} variant="outlined" sx={{ height: 20, fontSize: '0.65rem' }} />
                        </TableCell>
                        <TableCell>
                          <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                            {item.frame.requestMethod ?? '-'}
                          </Typography>
                        </TableCell>
                        <TableCell>
                          <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                            {item.frame.requestPath ?? '-'}
                          </Typography>
                        </TableCell>
                        <TableCell>
                          <TruncatedText text={item.frame.body || '-'} maxWidth={150} sx={{ fontFamily: 'monospace', fontSize: '0.75rem' }} />
                        </TableCell>
                        <TableCell align="right">
                          <Box sx={{ display: 'flex', gap: 0.5, justifyContent: 'flex-end' }}>
                            <Tooltip title="Continue (write frame unchanged)">
                              <span>
                                <IconButton size="small" color="success" disabled={busy} onClick={() => handleFrameContinue(item)} aria-label={`Continue ${item.key}`}>
                                  <PlayArrowIcon fontSize="small" />
                                </IconButton>
                              </span>
                            </Tooltip>
                            <Tooltip title="Modify frame body">
                              <span>
                                <IconButton size="small" color="info" disabled={busy} onClick={() => openFrameModifyDialog(item)} aria-label={`Modify ${item.key}`}>
                                  <EditIcon fontSize="small" />
                                </IconButton>
                              </span>
                            </Tooltip>
                            <Tooltip title="Drop (discard frame)">
                              <span>
                                <IconButton size="small" color="error" disabled={busy} onClick={() => handleFrameDrop(item)} aria-label={`Drop ${item.key}`}>
                                  <DeleteIcon fontSize="small" />
                                </IconButton>
                              </span>
                            </Tooltip>
                            <Tooltip title="Inject extra frame">
                              <span>
                                <IconButton size="small" color="primary" disabled={busy} onClick={() => openFrameInjectDialog(item)} aria-label={`Inject ${item.key}`}>
                                  <AddIcon fontSize="small" />
                                </IconButton>
                              </span>
                            </Tooltip>
                            <Tooltip title="Close stream">
                              <span>
                                <IconButton size="small" color="warning" disabled={busy} onClick={() => handleFrameClose(item)} aria-label={`Close ${item.key}`}>
                                  <StopIcon fontSize="small" />
                                </IconButton>
                              </span>
                            </Tooltip>
                          </Box>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
          </Paper>
        </>
      )}

      {/* Modify dialog (request/response exchanges) */}
      <Dialog open={modifyTarget !== null} onClose={() => setModifyTarget(null)} maxWidth="sm" fullWidth>
        <DialogTitle>
          {modifyTarget?.phase === 'RESPONSE' ? 'Modify Response' : 'Modify Request'}
        </DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
            {modifyTarget?.phase === 'RESPONSE'
              ? 'Edit the response JSON, then send the modified response.'
              : 'Edit the request JSON, then send the modified request.'}
          </Typography>
          {modifyError && (
            <Alert severity="error" sx={{ mb: 1 }}>
              {modifyError}
            </Alert>
          )}
          <TextField
            multiline
            minRows={6}
            maxRows={20}
            fullWidth
            value={modifyJson}
            onChange={(e) => setModifyJson(e.target.value)}
            slotProps={{
              input: {
                sx: { fontFamily: 'monospace', fontSize: '0.8rem' },
              },
            }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setModifyTarget(null)}>Cancel</Button>
          <Button variant="contained" disabled={busy} onClick={handleModifySubmit}>
            Send Modified
          </Button>
        </DialogActions>
      </Dialog>

      {/* Frame modify dialog */}
      <Dialog open={frameModifyTarget !== null} onClose={() => setFrameModifyTarget(null)} maxWidth="sm" fullWidth>
        <DialogTitle>Modify Stream Frame</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
            Edit the frame body, then send the modified frame.
          </Typography>
          {frameModifyError && (
            <Alert severity="error" sx={{ mb: 1 }}>
              {frameModifyError}
            </Alert>
          )}
          <TextField
            multiline
            minRows={4}
            maxRows={16}
            fullWidth
            value={frameModifyBody}
            onChange={(e) => setFrameModifyBody(e.target.value)}
            slotProps={{
              input: {
                sx: { fontFamily: 'monospace', fontSize: '0.8rem' },
              },
            }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setFrameModifyTarget(null)}>Cancel</Button>
          <Button variant="contained" disabled={busy} onClick={handleFrameModifySubmit}>
            Send Modified Frame
          </Button>
        </DialogActions>
      </Dialog>

      {/* Frame inject dialog */}
      <Dialog open={frameInjectTarget !== null} onClose={() => setFrameInjectTarget(null)} maxWidth="sm" fullWidth>
        <DialogTitle>Inject Extra Frame</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
            Enter the body for the extra frame to inject after the held frame.
          </Typography>
          {frameInjectError && (
            <Alert severity="error" sx={{ mb: 1 }}>
              {frameInjectError}
            </Alert>
          )}
          <TextField
            multiline
            minRows={4}
            maxRows={16}
            fullWidth
            value={frameInjectBody}
            onChange={(e) => setFrameInjectBody(e.target.value)}
            slotProps={{
              input: {
                sx: { fontFamily: 'monospace', fontSize: '0.8rem' },
              },
            }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setFrameInjectTarget(null)}>Cancel</Button>
          <Button variant="contained" disabled={busy} onClick={handleFrameInjectSubmit}>
            Inject Frame
          </Button>
        </DialogActions>
      </Dialog>

      <ConfirmDialog
        open={confirmOpen}
        title="Clear all breakpoint matchers?"
        message="This removes every registered breakpoint matcher. Any paused exchanges will remain held server-side until the breakpoint timeout expires. This cannot be undone."
        confirmLabel="Clear all matchers"
        onConfirm={() => void handleClearMatchers()}
        onClose={() => setConfirmOpen(false)}
      />
    </Box>
  );
}
