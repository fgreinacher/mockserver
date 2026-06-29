import { useMemo, useState, useCallback, useRef, memo } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import TextField from '@mui/material/TextField';
import InputAdornment from '@mui/material/InputAdornment';
import Chip from '@mui/material/Chip';
import Tabs from '@mui/material/Tabs';
import Tab from '@mui/material/Tab';
import Divider from '@mui/material/Divider';
import Button from '@mui/material/Button';
import Tooltip from '@mui/material/Tooltip';
import Checkbox from '@mui/material/Checkbox';
import ToggleButton from '@mui/material/ToggleButton';
import Alert from '@mui/material/Alert';
import CircularProgress from '@mui/material/CircularProgress';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useTheme } from '@mui/material/styles';
import SearchIcon from '@mui/icons-material/Search';
import SaveAltIcon from '@mui/icons-material/SaveAlt';
import ReplayIcon from '@mui/icons-material/Replay';
import CompareArrowsIcon from '@mui/icons-material/CompareArrows';
import { useDashboardStore } from '../store';
import { useConnectionParams } from '../hooks/useConnectionParams';
import { useDragResize } from '../hooks/useDragResize';
import JsonViewer from './JsonViewer';
import ErrorBoundary from './ErrorBoundary';
import CaptureAsMockDialog from './CaptureAsMockDialog';
import DiffRequestsDialog from './DiffRequestsDialog';
import LlmUsageDetail from './LlmUsageDetail';
import {
  AnthropicConversationView,
  OpenAiConversationView,
  GeminiConversationView,
  OllamaConversationView,
  OpenAiResponsesConversationView,
  ScriptedTurnsPanel,
} from './ConversationView';
import type { ScriptedTurn } from './ConversationView';
import type { JsonListItem } from '../types';
import { isCapturableTraffic } from '../lib/expectationFromCapture';
import { replayRequests } from '../lib/replay';
import { humanizeError, type HumanError } from '../lib/errorMessage';
import HumanErrorAlert from './HumanErrorAlert';
import { monospaceFontFamily, transitions } from '../theme';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import {
  summarizeTraffic,
  extractBodyContent,
  getModelLabel,
  getTokenSummary,
  getTimingLabel,
  type TrafficSummary,
  type ParsedTraffic,
  type RequestTiming,
  type AnthropicParsed,
  type OpenAiParsed,
  type OpenAiResponsesParsed,
  type GeminiParsed,
  type OllamaParsed,
  type McpParsed,
  type SseEvent,
} from '../lib/llmTraffic';

// ---------------------------------------------------------------------------
// Helper: status color
// ---------------------------------------------------------------------------

function statusColor(code: number | null): 'success' | 'error' | 'warning' | 'default' {
  if (code === null) return 'default';
  if (code >= 200 && code < 300) return 'success';
  if (code >= 400) return 'error';
  if (code >= 300) return 'warning';
  return 'default';
}

function kindLabel(parsed: ParsedTraffic): string {
  switch (parsed.kind) {
    case 'anthropic': return 'Anthropic';
    case 'openai': return 'OpenAI';
    case 'openai_responses': return 'OpenAI Resp';
    case 'gemini': return 'Gemini';
    case 'ollama': return 'Ollama';
    case 'mcp': return 'MCP';
    case 'generic': return 'HTTP';
  }
}

function kindColor(parsed: ParsedTraffic): 'primary' | 'secondary' | 'info' | 'default' {
  switch (parsed.kind) {
    case 'anthropic': return 'primary';
    case 'openai': return 'secondary';
    case 'openai_responses': return 'secondary';
    case 'gemini': return 'info';
    case 'ollama': return 'info';
    case 'mcp': return 'info';
    case 'generic': return 'default';
  }
}

// ---------------------------------------------------------------------------
// Secret-header masking (UI guard)
// ---------------------------------------------------------------------------

// Header names whose values carry credentials. Captured traffic is shown verbatim
// in the Raw JSON / Diff views (and flows into Replay), so mask these known secret
// headers before rendering. This is a UI guard only — server-side redaction is a
// separate concern. Matched case-insensitively.
const SECRET_HEADER_NAMES = new Set([
  'authorization',
  'proxy-authorization',
  'cookie',
  'set-cookie',
  'x-api-key',
  'api-key',
  'apikey',
  'anthropic-api-key',
  'openai-api-key',
  'x-goog-api-key',
  'x-api-token',
]);

/** Mask a bare token, keeping the last 4 chars for correlation when long enough. */
function maskToken(token: string): string {
  const trimmed = token.trim();
  if (trimmed.length <= 4) return '••••';
  return `••••${trimmed.slice(-4)}`;
}

/**
 * Mask a secret header value. Preserves a leading auth scheme (Bearer/Basic/…) so
 * the kind of credential stays visible, e.g. `Bearer sk-abc…1234` → `Bearer ••••1234`.
 * Non-scheme values (raw API keys, cookies) become `••••1234` / `••••`.
 */
export function maskSecretValue(raw: unknown): string {
  if (typeof raw !== 'string' || raw.length === 0) return typeof raw === 'string' ? raw : '••••';
  const schemeMatch = /^(\S+)\s+(.+)$/.exec(raw);
  if (schemeMatch && /^(bearer|basic|digest|token)$/i.test(schemeMatch[1]!)) {
    return `${schemeMatch[1]} ${maskToken(schemeMatch[2]!)}`;
  }
  return maskToken(raw);
}

/**
 * Return a copy of MockServer-format headers with any secret header values masked.
 * Handles both the array form `[{name, values:[…]}]` and the object form
 * `{name: [values]}`. Returns the original reference when nothing was masked so
 * callers can cheaply detect "no change".
 */
function maskHeaders(headers: unknown): unknown {
  if (Array.isArray(headers)) {
    let changed = false;
    const out = headers.map((h) => {
      if (h && typeof h === 'object' && !Array.isArray(h)) {
        const entry = h as Record<string, unknown>;
        const name = entry['name'];
        if (typeof name === 'string' && SECRET_HEADER_NAMES.has(name.toLowerCase())) {
          const values = entry['values'];
          if (Array.isArray(values)) {
            changed = true;
            return { ...entry, values: values.map((v) => maskSecretValue(String(v))) };
          }
        }
      }
      return h;
    });
    return changed ? out : headers;
  }
  if (headers && typeof headers === 'object') {
    const map = headers as Record<string, unknown>;
    let changed = false;
    const out: Record<string, unknown> = {};
    for (const key of Object.keys(map)) {
      const v = map[key];
      if (SECRET_HEADER_NAMES.has(key.toLowerCase())) {
        changed = true;
        if (Array.isArray(v)) out[key] = v.map((x) => maskSecretValue(String(x)));
        else if (typeof v === 'string') out[key] = maskSecretValue(v);
        else out[key] = v;
      } else {
        out[key] = v;
      }
    }
    return changed ? out : headers;
  }
  return headers;
}

/**
 * Return a shallow copy of a captured request value with secret headers masked on
 * its httpRequest / httpResponse / forwarded-request sections. Only the touched
 * paths are cloned; everything else shares references. Returns the original
 * reference when nothing was masked.
 */
export function maskSecretsInValue(value: Record<string, unknown>): Record<string, unknown> {
  let changed = false;
  const out: Record<string, unknown> = { ...value };
  for (const section of ['httpRequest', 'httpResponse', 'httpOverrideForwardedRequest'] as const) {
    const sec = value[section];
    if (sec && typeof sec === 'object' && !Array.isArray(sec)) {
      const secObj = sec as Record<string, unknown>;
      if ('headers' in secObj) {
        const maskedHeaders = maskHeaders(secObj['headers']);
        if (maskedHeaders !== secObj['headers']) {
          out[section] = { ...secObj, headers: maskedHeaders };
          changed = true;
        }
      }
    }
  }
  return changed ? out : value;
}

/**
 * Extract the decoded (non-stream) response body text from a captured value, or
 * undefined when the body parsed cleanly as JSON / is streamed / is absent. Used
 * to flag a truncated-or-malformed non-stream response in the conversation view.
 */
function nonStreamResponseBodyText(
  value: Record<string, unknown>,
  parsed: ParsedTraffic,
): string | undefined {
  if ('streamed' in parsed && parsed.streamed) return undefined;
  if ('sseEvents' in parsed && parsed.sseEvents) return undefined;
  const httpResponse = value['httpResponse'];
  if (!httpResponse || typeof httpResponse !== 'object') return undefined;
  const body = (httpResponse as Record<string, unknown>)['body'];
  const content = extractBodyContent(body);
  // A string here means MockServer stored a non-JSON (or unparsed) body; an object
  // means the body already parsed cleanly, so no truncation warning is warranted.
  return typeof content === 'string' ? content : undefined;
}

/**
 * Classify an MCP JSON-RPC exchange as an error. An error is present when the
 * JSON-RPC `error` object exists, or the HTTP status is non-2xx. Exposes the
 * numeric JSON-RPC error code and message when available.
 */
export function mcpErrorInfo(
  parsed: McpParsed,
  statusCode: number | null,
): { isError: boolean; code: number | null; message: string | null } {
  const errObj =
    parsed.error && typeof parsed.error === 'object' && !Array.isArray(parsed.error)
      ? (parsed.error as Record<string, unknown>)
      : null;
  const code = errObj && typeof errObj['code'] === 'number' ? (errObj['code'] as number) : null;
  const message = errObj && typeof errObj['message'] === 'string' ? (errObj['message'] as string) : null;
  const isError =
    parsed.error != null || (statusCode != null && (statusCode < 200 || statusCode >= 300));
  return { isError, code, message };
}

// ---------------------------------------------------------------------------
// Scripted turns extraction from active expectations
// ---------------------------------------------------------------------------

import { scenarioStateSortKey } from '../lib/scenarioState';

/**
 * Gather scripted turns from expectations sharing the same scenarioName.
 * Each expectation with httpLlmResponse + conversationPredicates within the
 * same scenario constitutes one turn.
 */
function gatherScriptedTurns(expectations: JsonListItem[]): ScriptedTurn[] {
  // Group expectations by scenarioName. Only LLM expectations are considered;
  // a single shared scenarioName forms one conversation.
  const scenarioGroups = new Map<string, JsonListItem[]>();
  for (const exp of expectations) {
    const llm = exp.value['httpLlmResponse'] as Record<string, unknown> | undefined;
    if (!llm) continue;
    // scenarioName is a top-level Expectation field (ExpectationDTO), not nested inside
    // httpLlmResponse — read it from the top level (consistent with sessionGrouping).
    const scenarioName = exp.value['scenarioName'] as string | undefined;
    if (!scenarioName) continue;
    if (!scenarioGroups.has(scenarioName)) {
      scenarioGroups.set(scenarioName, []);
    }
    scenarioGroups.get(scenarioName)!.push(exp);
  }

  // Collect turns from EVERY eligible scenario group. The previous version
  // returned the first matching group and silently dropped the rest, which
  // produced incorrect output when multiple conversations were loaded
  // simultaneously. We now flatten across groups; the ScriptedTurnsPanel
  // displays a separator between conversations via the scenarioName field.
  const allTurns: ScriptedTurn[] = [];
  for (const [scenarioName, group] of scenarioGroups) {
    const hasPredicates = group.some((e) => {
      const llm = e.value['httpLlmResponse'] as Record<string, unknown> | undefined;
      return llm && 'conversationPredicates' in llm;
    });
    if (!hasPredicates && group.length < 2) continue;

    // Sort by scenario state transition order. scenarioState is a top-level Expectation field.
    const sorted = [...group].sort((a, b) => {
      const aState = (a.value['scenarioState'] as string | undefined) ?? 'Started';
      const bState = (b.value['scenarioState'] as string | undefined) ?? 'Started';
      return scenarioStateSortKey(aState) - scenarioStateSortKey(bState);
    });

    const turns: ScriptedTurn[] = sorted.map((exp, i) => {
      const llm = exp.value['httpLlmResponse'] as Record<string, unknown>;
      const predicates = (llm['conversationPredicates'] as Record<string, unknown>) ?? {};
      const completion = (llm['completion'] as Record<string, unknown>) ?? {};
      const toolCalls = (completion['toolCalls'] as Array<{ name: string; arguments?: string }>) ?? [];

      return {
        turnIndex: (predicates['turnIndex'] as number | undefined) ?? i,
        predicates: Object.fromEntries(
          Object.entries(predicates).filter(([, v]) => v != null),
        ),
        response: {
          text: (completion['text'] as string | undefined),
          toolCalls: toolCalls.length > 0 ? toolCalls : undefined,
          stopReason: (completion['stopReason'] as string | undefined)
            ?? (llm['stopReason'] as string | undefined),
          // streaming lives inside completion (Completion.streaming), not at the llm top level.
          streaming: (completion['streaming'] as boolean | undefined),
        },
        // scenarioState / newScenarioState are top-level Expectation fields.
        scenarioState: (exp.value['scenarioState'] as string | undefined) ?? 'Started',
        newScenarioState: (exp.value['newScenarioState'] as string | undefined) ?? '__done',
        scenarioName,
      };
    });

    allTurns.push(...turns);
  }
  return allTurns;
}

// ---------------------------------------------------------------------------
// Search match helper
// ---------------------------------------------------------------------------

// Per-item parse cache. `summarizeTraffic` fully parses + reassembles SSE streams
// and base64-decodes bodies; the store replaces the traffic arrays on every
// WebSocket push but preserves each unchanged item's `value` reference
// (reconcileByKey), so caching on that reference avoids re-parsing every captured
// request on every push and on every search keystroke. The WeakMap lets entries
// be collected once the item object is gone.
const summaryCache = new WeakMap<Record<string, unknown>, TrafficSummary>();

function cachedSummarize(value: Record<string, unknown>): TrafficSummary {
  const hit = summaryCache.get(value);
  if (hit) return hit;
  const summary = summarizeTraffic(value);
  summaryCache.set(value, summary);
  return summary;
}

// Per-item lowercased full-text search index. The fallback branch of
// `matchesSearch` previously re-ran `JSON.stringify(item.value).toLowerCase()`
// for every non-field-matching row on every search keystroke. Caching the
// stringified+lowercased text on the item's `value` reference (preserved across
// WebSocket pushes by reconcileByKey, like `summaryCache`) means each item is
// stringified at most once regardless of how many keystrokes the user types.
const searchTextCache = new WeakMap<Record<string, unknown>, string>();

function cachedSearchText(value: Record<string, unknown>): string {
  const hit = searchTextCache.get(value);
  if (hit !== undefined) return hit;
  // Index the raw JSON AND the decoded request/response body text. A BINARY/base64
  // body is stored (and JSON.stringify'd) as base64, so without the decoded text a
  // search for a prompt/response word would never match. extractBodyContent
  // base64-decodes BINARY bodies and unwraps STRING/JSON bodies.
  const parts = [JSON.stringify(value)];
  for (const section of ['httpRequest', 'httpResponse'] as const) {
    const sec = value[section];
    if (sec && typeof sec === 'object') {
      const content = extractBodyContent((sec as Record<string, unknown>)['body']);
      if (typeof content === 'string') {
        parts.push(content);
      } else if (content && typeof content === 'object') {
        try { parts.push(JSON.stringify(content)); } catch { /* ignore unserialisable */ }
      }
    }
  }
  const text = parts.join(' ').toLowerCase();
  searchTextCache.set(value, text);
  return text;
}

function matchesSearch(item: JsonListItem, summary: TrafficSummary, term: string): boolean {
  const lower = term.toLowerCase();
  const parts = [
    summary.host,
    summary.method,
    summary.path,
    summary.statusCode?.toString(),
    getModelLabel(summary.parsed),
    kindLabel(summary.parsed),
  ].filter(Boolean);
  if (parts.some((p) => p!.toLowerCase().includes(lower))) return true;
  return cachedSearchText(item.value).includes(lower);
}

// ---------------------------------------------------------------------------
// Master list row
// ---------------------------------------------------------------------------

interface TrafficRowProps {
  summary: TrafficSummary;
  /** Stable identity of the request this row renders; passed back to the handlers. */
  itemKey: string;
  index: number;
  selected: boolean;
  /** Stable handler — receives this row's `itemKey`. */
  onSelect: (key: string) => void;
  /** When set, a comparison checkbox is rendered and reflects this checked state. */
  compareMode?: boolean;
  compareChecked?: boolean;
  compareDisabled?: boolean;
  /** Stable handler — receives this row's `itemKey`. */
  onCompareToggle?: (key: string) => void;
}

// `TrafficRow` is wrapped in `React.memo` (see the `export`/assignment below) so
// that re-rendering the parent (e.g. a search keystroke, a selection change, or a
// WebSocket-driven list refresh) only re-renders the rows whose props actually
// changed. For that to hold, the parent passes STABLE handlers that take the
// row's key (`onSelect`/`onCompareToggle`) rather than fresh per-row arrow
// closures; the per-row DOM callbacks are re-derived here with `useCallback`
// keyed on `itemKey`, mirroring the `entryKey`/`onToggleExpand` pattern used by
// the memoized `LogEntry`.
function TrafficRowImpl({
  summary,
  itemKey,
  index,
  selected,
  onSelect,
  compareMode,
  compareChecked,
  compareDisabled,
  onCompareToggle,
}: TrafficRowProps) {
  const model = getModelLabel(summary.parsed);
  const tokens = getTokenSummary(summary.parsed);
  const timingLabel = getTimingLabel(summary.timing);

  const handleClick = useCallback(() => onSelect(itemKey), [onSelect, itemKey]);
  const handleCompareToggle = useCallback(
    () => onCompareToggle?.(itemKey),
    [onCompareToggle, itemKey],
  );

  return (
    <Box
      onClick={handleClick}
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 0.75,
        px: 1,
        py: 0.5,
        cursor: 'pointer',
        bgcolor: selected ? 'action.selected' : 'transparent',
        transition: transitions.forProps(['background-color']),
        '&:hover': { bgcolor: selected ? 'action.selected' : 'action.hover' },
        borderBottom: 1,
        borderColor: 'divider',
        minHeight: 36,
        flexWrap: 'wrap',
      }}
    >
      {compareMode && (
        <Checkbox
          size="small"
          checked={!!compareChecked}
          disabled={!compareChecked && compareDisabled}
          onClick={(e) => e.stopPropagation()}
          onChange={handleCompareToggle}
          slotProps={{ input: { 'aria-label': `Select request ${index} to compare` } }}
          sx={{ p: 0.25, flexShrink: 0 }}
        />
      )}
      <Typography
        variant="caption"
        sx={{ fontFamily: monospaceFontFamily, color: 'text.secondary', minWidth: 24, flexShrink: 0 }}
      >
        {index}
      </Typography>
      <Chip
        label={kindLabel(summary.parsed)}
        size="small"
        color={kindColor(summary.parsed)}
        variant="outlined"
        sx={{ height: 18, fontSize: '0.6rem', '& .MuiChip-label': { px: 0.5 } }}
      />
      <Typography
        variant="caption"
        sx={{
          fontFamily: monospaceFontFamily,
          fontWeight: 600,
          color: 'primary.main',
          flexShrink: 0,
        }}
      >
        {summary.method ?? '?'}
      </Typography>
      <Tooltip title={`${summary.host ?? ''}${summary.path ?? ''}`}>
        <Typography
          variant="caption"
          noWrap
          sx={{
            fontFamily: monospaceFontFamily,
            flex: 1,
            minWidth: 80,
            overflow: 'hidden',
            textOverflow: 'ellipsis',
          }}
        >
          {summary.host ? `${summary.host}` : ''}{summary.path ?? ''}
        </Typography>
      </Tooltip>
      {summary.statusCode !== null && (
        <Chip
          label={summary.statusCode}
          size="small"
          color={statusColor(summary.statusCode)}
          sx={{ height: 18, fontSize: '0.6rem', '& .MuiChip-label': { px: 0.5 } }}
        />
      )}
      {model && (
        <Chip
          label={model}
          size="small"
          variant="outlined"
          sx={{ height: 18, fontSize: '0.6rem', '& .MuiChip-label': { px: 0.5 } }}
        />
      )}
      {tokens && (
        <Typography variant="caption" color="text.secondary" sx={{ fontSize: '0.6rem', flexShrink: 0 }}>
          {tokens}
        </Typography>
      )}
      {timingLabel && (
        <Chip
          label={timingLabel}
          size="small"
          variant="outlined"
          color="info"
          sx={{ height: 18, fontSize: '0.6rem', '& .MuiChip-label': { px: 0.5 } }}
        />
      )}
    </Box>
  );
}

const TrafficRow = memo(TrafficRowImpl);

// ---------------------------------------------------------------------------
// Messages panel: Anthropic
// ---------------------------------------------------------------------------

function AnthropicMessagesPanel({ parsed }: { parsed: AnthropicParsed }) {
  return (
    <Box>
      {parsed.system != null && (
        <Box sx={{ mb: 1 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>System</Typography>
          <Box sx={{ mt: 0.5 }}>
            {typeof parsed.system === 'string' ? (
              <Typography variant="body2" sx={{ fontFamily: monospaceFontFamily, fontSize: '0.75rem', whiteSpace: 'pre-wrap' }}>
                {parsed.system}
              </Typography>
            ) : (
              <JsonViewer data={parsed.system as Record<string, unknown>} collapsed={2} />
            )}
          </Box>
        </Box>
      )}
      {parsed.messages.length > 0 && (
        <Box>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Messages ({parsed.messages.length})</Typography>
          {parsed.messages.map((msg, i) => (
            <Box key={i} sx={{ mt: 0.5, pl: 1, borderLeft: 2, borderColor: 'divider' }}>
              <JsonViewer data={msg as Record<string, unknown>} collapsed={1} />
            </Box>
          ))}
        </Box>
      )}
      {parsed.tools && parsed.tools.length > 0 && (
        <Box sx={{ mt: 1 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Tools ({parsed.tools.length})</Typography>
          <JsonViewer data={parsed.tools as unknown[]} collapsed={1} />
        </Box>
      )}
      <Box sx={{ mt: 1, display: 'flex', gap: 1, flexWrap: 'wrap' }}>
        {parsed.model && <Chip label={`Model: ${parsed.model}`} size="small" variant="outlined" />}
        {parsed.maxTokens !== null && <Chip label={`Max tokens: ${parsed.maxTokens}`} size="small" variant="outlined" />}
        {parsed.stream && <Chip label="Streaming" size="small" color="info" variant="outlined" />}
      </Box>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Messages panel: OpenAI
// ---------------------------------------------------------------------------

function OpenAiMessagesPanel({ parsed }: { parsed: OpenAiParsed }) {
  return (
    <Box>
      {parsed.messages.length > 0 && (
        <Box>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Messages ({parsed.messages.length})</Typography>
          {parsed.messages.map((msg, i) => (
            <Box key={i} sx={{ mt: 0.5, pl: 1, borderLeft: 2, borderColor: 'divider' }}>
              <JsonViewer data={msg as Record<string, unknown>} collapsed={1} />
            </Box>
          ))}
        </Box>
      )}
      {parsed.tools && parsed.tools.length > 0 && (
        <Box sx={{ mt: 1 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Tools ({parsed.tools.length})</Typography>
          <JsonViewer data={parsed.tools as unknown[]} collapsed={1} />
        </Box>
      )}
      <Box sx={{ mt: 1, display: 'flex', gap: 1, flexWrap: 'wrap' }}>
        {parsed.model && <Chip label={`Model: ${parsed.model}`} size="small" variant="outlined" />}
        {parsed.stream && <Chip label="Streaming" size="small" color="info" variant="outlined" />}
      </Box>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// MCP panel (content only, no wrapping tabs)
// ---------------------------------------------------------------------------

function McpDetailPanel({ parsed, statusCode }: { parsed: McpParsed; statusCode: number | null }) {
  const { isError, code, message } = mcpErrorInfo(parsed, statusCode);
  return (
    <Box>
      <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', mb: 1 }}>
        <Chip
          label="MCP JSON-RPC"
          size="small"
          color={isError ? 'error' : 'info'}
          variant={isError ? 'filled' : 'outlined'}
        />
        {parsed.method && <Chip label={`Method: ${parsed.method}`} size="small" variant="outlined" />}
        {parsed.id != null && <Chip label={`ID: ${String(parsed.id)}`} size="small" variant="outlined" />}
        {isError && (
          <Chip
            label={code != null ? `Error ${code}` : 'Error'}
            size="small"
            color="error"
            variant="outlined"
          />
        )}
      </Box>
      {parsed.params != null && (
        <Box sx={{ mb: 1 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Params</Typography>
          <JsonViewer data={parsed.params as Record<string, unknown>} collapsed={2} />
        </Box>
      )}
      {parsed.result != null && (
        <Box sx={{ mb: 1 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Result</Typography>
          <JsonViewer data={parsed.result as Record<string, unknown>} collapsed={2} />
        </Box>
      )}
      {parsed.error != null && (
        <Box sx={{ mb: 1 }}>
          <Typography variant="caption" color="error" sx={{ fontWeight: 600 }}>
            Error{code != null ? ` (code ${code})` : ''}{message ? `: ${message}` : ''}
          </Typography>
          <JsonViewer data={parsed.error as Record<string, unknown>} collapsed={2} />
        </Box>
      )}
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Messages panel: OpenAI Responses API
// ---------------------------------------------------------------------------

function OpenAiResponsesMessagesPanel({ parsed }: { parsed: OpenAiResponsesParsed }) {
  return (
    <Box>
      {parsed.input.length > 0 && (
        <Box>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Input ({parsed.input.length})</Typography>
          {parsed.input.map((item, i) => (
            <Box key={i} sx={{ mt: 0.5, pl: 1, borderLeft: 2, borderColor: 'divider' }}>
              <JsonViewer data={item as Record<string, unknown>} collapsed={1} />
            </Box>
          ))}
        </Box>
      )}
      {parsed.output.length > 0 && (
        <Box sx={{ mt: 1 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Output ({parsed.output.length})</Typography>
          {parsed.output.map((item, i) => (
            <Box key={i} sx={{ mt: 0.5, pl: 1, borderLeft: 2, borderColor: 'divider' }}>
              <JsonViewer data={item as Record<string, unknown>} collapsed={1} />
            </Box>
          ))}
        </Box>
      )}
      <Box sx={{ mt: 1, display: 'flex', gap: 1, flexWrap: 'wrap' }}>
        {parsed.model && <Chip label={`Model: ${parsed.model}`} size="small" variant="outlined" />}
        {parsed.stream && <Chip label="Streaming" size="small" color="info" variant="outlined" />}
      </Box>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Messages panel: Gemini
// ---------------------------------------------------------------------------

function GeminiMessagesPanel({ parsed }: { parsed: GeminiParsed }) {
  return (
    <Box>
      {parsed.contents.length > 0 && (
        <Box>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Contents ({parsed.contents.length})</Typography>
          {parsed.contents.map((item, i) => (
            <Box key={i} sx={{ mt: 0.5, pl: 1, borderLeft: 2, borderColor: 'divider' }}>
              <JsonViewer data={item as Record<string, unknown>} collapsed={1} />
            </Box>
          ))}
        </Box>
      )}
      {parsed.candidates.length > 0 && (
        <Box sx={{ mt: 1 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Candidates ({parsed.candidates.length})</Typography>
          {parsed.candidates.map((item, i) => (
            <Box key={i} sx={{ mt: 0.5, pl: 1, borderLeft: 2, borderColor: 'divider' }}>
              <JsonViewer data={item as Record<string, unknown>} collapsed={1} />
            </Box>
          ))}
        </Box>
      )}
      <Box sx={{ mt: 1, display: 'flex', gap: 1, flexWrap: 'wrap' }}>
        {parsed.model && <Chip label={`Model: ${parsed.model}`} size="small" variant="outlined" />}
      </Box>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Messages panel: Ollama
// ---------------------------------------------------------------------------

function OllamaMessagesPanel({ parsed }: { parsed: OllamaParsed }) {
  return (
    <Box>
      {parsed.messages.length > 0 && (
        <Box>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Messages ({parsed.messages.length})</Typography>
          {parsed.messages.map((msg, i) => (
            <Box key={i} sx={{ mt: 0.5, pl: 1, borderLeft: 2, borderColor: 'divider' }}>
              <JsonViewer data={msg as Record<string, unknown>} collapsed={1} />
            </Box>
          ))}
        </Box>
      )}
      {parsed.responseMessage != null && (
        <Box sx={{ mt: 1 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Response</Typography>
          <Box sx={{ mt: 0.5, pl: 1, borderLeft: 2, borderColor: 'divider' }}>
            <JsonViewer data={parsed.responseMessage as Record<string, unknown>} collapsed={1} />
          </Box>
        </Box>
      )}
      <Box sx={{ mt: 1, display: 'flex', gap: 1, flexWrap: 'wrap' }}>
        {parsed.model && <Chip label={`Model: ${parsed.model}`} size="small" variant="outlined" />}
        {parsed.stream && <Chip label="Streaming" size="small" color="info" variant="outlined" />}
        {parsed.done && <Chip label="Done" size="small" color="success" variant="outlined" />}
      </Box>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// SSE Timeline
// ---------------------------------------------------------------------------

function SseTimeline({ events }: { events: SseEvent[] }) {
  const [expandedIndex, setExpandedIndex] = useState<number | null>(null);

  return (
    <Box>
      <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600, mb: 0.5, display: 'block' }}>
        SSE Events ({events.length})
      </Typography>
      {events.map((evt, i) => {
        const parsedData = (() => {
          if (evt.data === '[DONE]') return null;
          try { return JSON.parse(evt.data); } catch { return null; }
        })();

        return (
          <Box
            key={i}
            sx={{
              borderLeft: 2,
              borderColor: evt.event === 'error' ? 'error.main' : 'primary.main',
              pl: 1,
              py: 0.25,
              mb: 0.25,
              cursor: 'pointer',
              '&:hover': { bgcolor: 'action.hover' },
            }}
            onClick={() => setExpandedIndex(expandedIndex === i ? null : i)}
          >
            <Box sx={{ display: 'flex', gap: 0.5, alignItems: 'center' }}>
              <Typography variant="caption" sx={{ fontFamily: monospaceFontFamily, color: 'text.secondary', minWidth: 24 }}>
                {i + 1}
              </Typography>
              {evt.event && (
                <Chip
                  label={evt.event}
                  size="small"
                  variant="outlined"
                  sx={{ height: 16, fontSize: '0.6rem', '& .MuiChip-label': { px: 0.5 } }}
                />
              )}
              <Typography
                variant="caption"
                noWrap
                sx={{ fontFamily: monospaceFontFamily, fontSize: '0.65rem', color: 'text.secondary', flex: 1 }}
              >
                {evt.data.length > 80 ? evt.data.slice(0, 80) + '...' : evt.data}
              </Typography>
            </Box>
            {expandedIndex === i && parsedData && (
              <Box sx={{ mt: 0.5, ml: 3 }}>
                <JsonViewer data={parsedData} collapsed={2} />
              </Box>
            )}
            {expandedIndex === i && !parsedData && (
              <Typography variant="body2" sx={{ mt: 0.5, ml: 3, fontFamily: monospaceFontFamily, fontSize: '0.7rem', whiteSpace: 'pre-wrap' }}>
                {evt.data}
              </Typography>
            )}
          </Box>
        );
      })}
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Per-request timing waterfall
// ---------------------------------------------------------------------------

function TimingWaterfall({ timing }: { timing: RequestTiming }) {
  const total = timing.totalTimeInMillis;
  if (total === null || total === 0) return null;

  const connect = timing.connectionTimeInMillis ?? 0;
  // Wait/TTFB is the gap between connection established and first byte
  const ttfb = timing.timeToFirstByteInMillis ?? 0;
  const waitMs = Math.max(0, ttfb - connect);
  const receiveMs = Math.max(0, total - ttfb);

  // Calculate percentages for the waterfall bar
  const connectPct = (connect / total) * 100;
  const waitPct = (waitMs / total) * 100;
  const receivePct = (receiveMs / total) * 100;

  return (
    <Box
      data-testid="timing-waterfall"
      sx={{
        display: 'flex',
        flexDirection: 'column',
        gap: 0.5,
        px: 1,
        py: 0.5,
        borderBottom: 1,
        borderColor: 'divider',
        bgcolor: 'action.hover',
        flexShrink: 0,
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, flexWrap: 'wrap' }}>
        <Typography variant="caption" sx={{ fontSize: '0.65rem', fontWeight: 600, color: 'text.primary' }}>
          Timing
        </Typography>
        {timing.connectionTimeInMillis !== null && (
          <Chip
            label={`connect ${timing.connectionTimeInMillis}ms`}
            size="small"
            variant="outlined"
            sx={{ height: 18, fontSize: '0.6rem', '& .MuiChip-label': { px: 0.5 } }}
          />
        )}
        {timing.timeToFirstByteInMillis !== null && (
          <Chip
            label={`TTFB ${timing.timeToFirstByteInMillis}ms`}
            size="small"
            variant="outlined"
            sx={{ height: 18, fontSize: '0.6rem', '& .MuiChip-label': { px: 0.5 } }}
          />
        )}
        <Chip
          label={`total ${total}ms`}
          size="small"
          variant="outlined"
          color="info"
          sx={{ height: 18, fontSize: '0.6rem', '& .MuiChip-label': { px: 0.5 } }}
        />
      </Box>
      {/* Inline waterfall bar: connect -> wait/TTFB -> receive */}
      <Tooltip
        title={`Connect: ${connect}ms | Wait: ${waitMs}ms | Receive: ${receiveMs}ms`}
        placement="top"
        arrow
      >
        <Box
          data-testid="timing-bar"
          sx={{
            display: 'flex',
            height: 8,
            borderRadius: 1,
            overflow: 'hidden',
            bgcolor: 'background.default',
          }}
        >
          {connectPct > 0 && (
            <Box
              sx={{
                width: `${connectPct}%`,
                bgcolor: 'warning.main',
                minWidth: connectPct > 0 ? 2 : 0,
              }}
            />
          )}
          {waitPct > 0 && (
            <Box
              sx={{
                width: `${waitPct}%`,
                bgcolor: 'info.main',
                minWidth: waitPct > 0 ? 2 : 0,
              }}
            />
          )}
          {receivePct > 0 && (
            <Box
              sx={{
                width: `${receivePct}%`,
                bgcolor: 'success.main',
                minWidth: receivePct > 0 ? 2 : 0,
              }}
            />
          )}
        </Box>
      </Tooltip>
      <Box sx={{ display: 'flex', gap: 1.5, justifyContent: 'flex-start' }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.25 }}>
          <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: 'warning.main' }} />
          <Typography variant="caption" sx={{ fontSize: '0.55rem', color: 'text.secondary' }}>Connect</Typography>
        </Box>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.25 }}>
          <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: 'info.main' }} />
          <Typography variant="caption" sx={{ fontSize: '0.55rem', color: 'text.secondary' }}>Wait</Typography>
        </Box>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.25 }}>
          <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: 'success.main' }} />
          <Typography variant="caption" sx={{ fontSize: '0.55rem', color: 'text.secondary' }}>Receive</Typography>
        </Box>
      </Box>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// ReplayDialog — replays a request via PUT /mockserver/replay and shows result
// ---------------------------------------------------------------------------

interface ReplayDialogProps {
  open: boolean;
  onClose: () => void;
  item: JsonListItem;
  connectionParams: ConnectionParams;
}

function ReplayDialog({ open, onClose, item, connectionParams }: ReplayDialogProps) {
  const [loading, setLoading] = useState(false);
  const [replayResponse, setReplayResponse] = useState<Record<string, unknown> | null>(null);
  const [error, setError] = useState<HumanError | null>(null);

  const handleReplay = useCallback(async () => {
    setLoading(true);
    setError(null);
    setReplayResponse(null);
    try {
      const httpRequest = (item.value['httpRequest'] as Record<string, unknown> | undefined) ?? {};
      const result = await replayRequests(connectionParams, httpRequest);
      setReplayResponse(result);
    } catch (err) {
      // Route both server rejections (ReplayError) and network failures through
      // the shared humaniser so the displayed message is consistent and actionable,
      // with the raw server body kept behind a Details expander.
      setError(humanizeError(err));
    } finally {
      setLoading(false);
    }
  }, [connectionParams, item]);

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle sx={{ fontSize: '0.95rem' }}>Replay Request</DialogTitle>
      <DialogContent dividers>
        {!replayResponse && !error && !loading && (() => {
          const httpRequest = (item.value['httpRequest'] as Record<string, unknown> | undefined) ?? {};
          const method = typeof httpRequest['method'] === 'string' ? httpRequest['method'] : 'GET';
          const isNonGet = method !== 'GET';
          return (
            <>
              <Alert severity="warning" sx={{ mb: 1 }}>
                This will make a real outbound HTTP request to the original upstream target.
                {isNonGet && ` The method is ${method}, which may mutate state or incur costs (e.g. LLM API charges).`}
                {!isNonGet && ' It may incur costs if the target is a paid API (e.g. LLM provider).'}
              </Alert>
            </>
          );
        })()}
        {loading && (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, py: 2 }}>
            <CircularProgress size={20} />
            <Typography variant="body2">Sending request...</Typography>
          </Box>
        )}
        {error && <HumanErrorAlert error={error} sx={{ mb: 1 }} />}
        {replayResponse && (
          <Box sx={{ mt: 1 }}>
            <Typography variant="subtitle2" sx={{ mb: 0.5, fontWeight: 600 }}>
              Upstream Response
            </Typography>
            <JsonViewer data={replayResponse} collapsed={3} />
          </Box>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} size="small">Close</Button>
        <Button
          onClick={handleReplay}
          disabled={loading}
          variant="contained"
          size="small"
          startIcon={<ReplayIcon sx={{ fontSize: '0.875rem' }} />}
        >
          {replayResponse ? 'Replay Again' : 'Replay'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

// ---------------------------------------------------------------------------
// Detail pane wrapper — single-level, adaptive tab row
// ---------------------------------------------------------------------------

interface DetailPaneProps {
  item: JsonListItem;
  summary: TrafficSummary;
  scriptedTurns: ScriptedTurn[];
  onCaptureAsMock?: () => void;
  onReplay?: () => void;
}

/** Build the tab list dynamically from the traffic kind. */
function buildTabs(parsed: ParsedTraffic, hasScriptedTurns: boolean): string[] {
  switch (parsed.kind) {
    case 'anthropic':
    case 'openai':
    case 'openai_responses':
    case 'gemini':
    case 'ollama': {
      const tabs = ['Messages', 'Conversation'];
      if (hasScriptedTurns) tabs.push('Scripted Turns');
      if (parsed.sseEvents) tabs.push('SSE Timeline');
      tabs.push('Raw JSON');
      return tabs;
    }
    case 'mcp':
      return ['MCP', 'Raw JSON'];
    case 'generic':
      return []; // no tabs — render Raw JSON directly
  }
}

function DetailPane({ item, summary, scriptedTurns, onCaptureAsMock, onReplay }: DetailPaneProps) {
  const tabs = buildTabs(summary.parsed, scriptedTurns.length > 0);
  const [detailTab, setDetailTab] = useState(0);
  const canCapture = isCapturableTraffic(summary.parsed);
  // Mask known secret headers before rendering the Raw JSON view so credentials
  // (Authorization, x-api-key, Cookie, …) are not shown verbatim.
  const maskedValue = useMemo(() => maskSecretsInValue(item.value), [item.value]);
  // Decoded non-stream response body text, used to flag a truncated/malformed
  // response in the conversation view (undefined when the body parsed cleanly).
  const rawResponseBody = useMemo(
    () => nonStreamResponseBodyText(item.value, summary.parsed),
    [item.value, summary.parsed],
  );

  // For generic traffic, render Raw JSON directly — no tab bar needed
  if (tabs.length === 0) {
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
        <LlmUsageDetail parsed={summary.parsed} />
        {summary.timing && <TimingWaterfall timing={summary.timing} />}
        <Box sx={{ display: 'flex', alignItems: 'center', px: 1, py: 0.5, flexShrink: 0 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600, fontSize: '0.75rem', flexGrow: 1 }}>
            Raw JSON
          </Typography>
          {onReplay && (
            <Button
              size="small"
              startIcon={<ReplayIcon sx={{ fontSize: '0.875rem' }} />}
              onClick={onReplay}
              sx={{ fontSize: '0.7rem', textTransform: 'none', whiteSpace: 'nowrap', flexShrink: 0, mr: 0.5 }}
            >
              Replay
            </Button>
          )}
          {canCapture && onCaptureAsMock && (
            <Button
              size="small"
              startIcon={<SaveAltIcon sx={{ fontSize: '0.875rem' }} />}
              onClick={onCaptureAsMock}
              sx={{ fontSize: '0.7rem', textTransform: 'none', whiteSpace: 'nowrap', flexShrink: 0 }}
            >
              Capture as mock
            </Button>
          )}
        </Box>
        <Divider />
        <Box sx={{ flex: 1, overflowY: 'auto', p: 1 }}>
          <JsonViewer data={maskedValue} collapsed={2} />
        </Box>
      </Box>
    );
  }

  // Clamp tab index defensively (e.g. if tabs array shrank before the effect runs)
  const safeTab = Math.min(detailTab, tabs.length - 1);
  const activeLabel = tabs[safeTab];

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
      <LlmUsageDetail parsed={summary.parsed} />
      {summary.timing && <TimingWaterfall timing={summary.timing} />}
      <Box sx={{ display: 'flex', alignItems: 'center', flexShrink: 0 }}>
        <Tabs
          value={safeTab}
          onChange={(_, v: number) => setDetailTab(v)}
          variant="scrollable"
          scrollButtons="auto"
          sx={{ flexGrow: 1, minHeight: 32, '& .MuiTab-root': { minHeight: 32, py: 0.5, fontSize: '0.75rem' } }}
        >
          {tabs.map((label) => (
            <Tab key={label} label={label} />
          ))}
        </Tabs>
        {onReplay && (
          <Button
            size="small"
            startIcon={<ReplayIcon sx={{ fontSize: '0.875rem' }} />}
            onClick={onReplay}
            sx={{ mr: 0.5, fontSize: '0.7rem', textTransform: 'none', whiteSpace: 'nowrap', flexShrink: 0 }}
          >
            Replay
          </Button>
        )}
        {canCapture && onCaptureAsMock && (
          <Button
            size="small"
            startIcon={<SaveAltIcon sx={{ fontSize: '0.875rem' }} />}
            onClick={onCaptureAsMock}
            sx={{ mr: 0.5, fontSize: '0.7rem', textTransform: 'none', whiteSpace: 'nowrap', flexShrink: 0 }}
          >
            Capture as mock
          </Button>
        )}
      </Box>
      <Divider />
      <Box sx={{ flex: 1, overflowY: 'auto', p: 1, minHeight: 0 }}>
        {activeLabel === 'Messages' && summary.parsed.kind === 'anthropic' && (
          <AnthropicMessagesPanel parsed={summary.parsed} />
        )}
        {activeLabel === 'Messages' && summary.parsed.kind === 'openai' && (
          <OpenAiMessagesPanel parsed={summary.parsed} />
        )}
        {activeLabel === 'Messages' && summary.parsed.kind === 'openai_responses' && (
          <OpenAiResponsesMessagesPanel parsed={summary.parsed} />
        )}
        {activeLabel === 'Messages' && summary.parsed.kind === 'gemini' && (
          <GeminiMessagesPanel parsed={summary.parsed} />
        )}
        {activeLabel === 'Messages' && summary.parsed.kind === 'ollama' && (
          <OllamaMessagesPanel parsed={summary.parsed} />
        )}
        {activeLabel === 'Conversation' && summary.parsed.kind === 'anthropic' && (
          <AnthropicConversationView parsed={summary.parsed} rawResponseBody={rawResponseBody} />
        )}
        {activeLabel === 'Conversation' && summary.parsed.kind === 'openai' && (
          <OpenAiConversationView parsed={summary.parsed} rawResponseBody={rawResponseBody} />
        )}
        {activeLabel === 'Conversation' && summary.parsed.kind === 'openai_responses' && (
          <OpenAiResponsesConversationView parsed={summary.parsed} rawResponseBody={rawResponseBody} />
        )}
        {activeLabel === 'Conversation' && summary.parsed.kind === 'gemini' && (
          <GeminiConversationView parsed={summary.parsed} rawResponseBody={rawResponseBody} />
        )}
        {activeLabel === 'Conversation' && summary.parsed.kind === 'ollama' && (
          <OllamaConversationView parsed={summary.parsed} rawResponseBody={rawResponseBody} />
        )}
        {activeLabel === 'Scripted Turns' && scriptedTurns.length > 0 && (
          <ScriptedTurnsPanel turns={scriptedTurns} />
        )}
        {activeLabel === 'SSE Timeline' && 'sseEvents' in summary.parsed && summary.parsed.sseEvents && (
          <SseTimeline events={summary.parsed.sseEvents} />
        )}
        {activeLabel === 'MCP' && summary.parsed.kind === 'mcp' && (
          <McpDetailPanel parsed={summary.parsed} statusCode={summary.statusCode} />
        )}
        {activeLabel === 'Raw JSON' && (
          <JsonViewer data={maskedValue} collapsed={2} />
        )}
      </Box>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Main TrafficInspector component
// ---------------------------------------------------------------------------

export default function TrafficInspector() {
  // Show every captured request, regardless of whether it was proxied through to
  // a real upstream or matched a registered mock expectation. The MockServer
  // backend logs the two cases into separate arrays (proxiedRequests vs
  // recordedRequests); the user thinks of them both as "traffic".
  const proxiedRequests = useDashboardStore((s) => s.proxiedRequests);
  const recordedRequests = useDashboardStore((s) => s.recordedRequests);
  const activeExpectations = useDashboardStore((s) => s.activeExpectations);
  const trafficSearch = useDashboardStore((s) => s.trafficSearch);
  const setTrafficSearch = useDashboardStore((s) => s.setTrafficSearch);
  const selectedKey = useDashboardStore((s) => s.selectedTrafficKey);
  const setSelectedKey = useDashboardStore((s) => s.setSelectedTrafficKey);
  const connectionParams = useConnectionParams();
  const theme = useTheme();
  // On narrow screens the side-by-side master/detail split squashes the detail
  // pane to a sliver; stack master-over-detail (column) on small screens.
  const stacked = useMediaQuery(theme.breakpoints.down('md'));

  // Side-by-side master/detail split width (pixels), drag-resizable via a
  // vertical divider between the master list and the detail pane. Persisted so
  // the chosen width survives reloads. Min keeps the list usable; max is a
  // generous cap (the detail pane stays flex:1 and re-clamps if the row is
  // narrower, since computeFromPointer clamps to the live container width).
  const rowRef = useRef<HTMLDivElement | null>(null);
  const MASTER_MIN = 260;
  const MASTER_MAX = 900;
  const masterWidth = useDragResize({
    orientation: 'vertical',
    initial: 380,
    min: MASTER_MIN,
    max: MASTER_MAX,
    step: 16,
    storageKey: 'mockserver-traffic-master-width',
    ariaLabel: 'Resize traffic list',
    computeFromPointer: (event) => {
      const rect = rowRef.current?.getBoundingClientRect();
      if (!rect) return 380;
      // Clamp the upper bound to leave at least ~360px for the detail pane.
      const next = event.clientX - rect.left;
      const dynamicMax = Math.max(MASTER_MIN, rect.width - 360);
      return Math.min(next, dynamicMax);
    },
  });

  const [captureDialogOpen, setCaptureDialogOpen] = useState(false);
  const [replayDialogOpen, setReplayDialogOpen] = useState(false);

  // Compare mode: pick two requests from the list and diff them field-by-field via the shared
  // DiffRequestsDialog (PUT /mockserver/diff). compareKeys holds the (max two) selected item keys.
  const [compareMode, setCompareMode] = useState(false);
  const [compareKeys, setCompareKeys] = useState<string[]>([]);
  const [diffDialogOpen, setDiffDialogOpen] = useState(false);

  const toggleCompareMode = useCallback(() => {
    setCompareMode((prev) => {
      // Leaving compare mode clears any pending selection.
      if (prev) setCompareKeys([]);
      return !prev;
    });
  }, []);

  const toggleCompareKey = useCallback((key: string) => {
    setCompareKeys((prev) => {
      if (prev.includes(key)) return prev.filter((k) => k !== key);
      if (prev.length >= 2) return prev; // cap at two; row checkbox is disabled past this
      return [...prev, key];
    });
  }, []);

  // Gather scripted turns from active expectations
  const scriptedTurns = useMemo(
    () => gatherScriptedTurns(activeExpectations),
    [activeExpectations],
  );

  // Build summaries for every captured request (proxied + mocked).
  const allRequests = useMemo(
    () => [...proxiedRequests, ...recordedRequests],
    [proxiedRequests, recordedRequests],
  );
  const summaries = useMemo(
    () => allRequests.map((item) => ({ item, summary: cachedSummarize(item.value) })),
    [allRequests],
  );

  // Filter by search
  const filtered = useMemo(
    () =>
      trafficSearch
        ? summaries.filter(({ item, summary }) => matchesSearch(item, summary, trafficSearch))
        : summaries,
    [summaries, trafficSearch],
  );

  // Select by stable item key, not array position: the store fully replaces the traffic lists
  // on every WebSocket refresh and `filtered` is re-derived on each search keystroke, so a
  // positional index would point at a different (or missing) request after any update.
  const selectedEntry = useMemo(() => {
    if (selectedKey === null) return null;
    return filtered.find(({ item }) => item.key === selectedKey) ?? null;
  }, [filtered, selectedKey]);

  const handleRowClick = useCallback(
    (key: string) => {
      setSelectedKey(selectedKey === key ? null : key);
    },
    [selectedKey, setSelectedKey],
  );

  // Single stable per-row select handler passed to the memoized TrafficRow. In
  // compare mode a click toggles the comparison selection; otherwise it selects
  // the row for the detail pane. Keeping this stable (rather than a fresh arrow
  // per row in the map below) is what lets React.memo skip unchanged rows.
  const handleRowSelect = useCallback(
    (key: string) => {
      if (compareMode) {
        toggleCompareKey(key);
      } else {
        handleRowClick(key);
      }
    },
    [compareMode, toggleCompareKey, handleRowClick],
  );

  // Resolve the two selected requests to the JSON the diff endpoint expects (the request
  // definition — `httpRequest` if present, otherwise the whole captured value). Preserve the
  // user's pick order: the first selected is "expected", the second "actual".
  // Selected keys whose request still exists (a WebSocket refresh can remove one),
  // so a stale selection can't produce an empty/invalid diff payload.
  const validCompareKeys = useMemo(
    () => compareKeys.filter((key) => allRequests.some((item) => item.key === key)),
    [compareKeys, allRequests],
  );

  const compareJson = useMemo(() => {
    const toRequestJson = (item: JsonListItem): string => {
      // Mask secret headers before the diff so credentials aren't shown verbatim
      // in the comparison editor (and don't flow into a replay from there).
      const masked = maskSecretsInValue(item.value);
      const request = (masked['httpRequest'] as Record<string, unknown> | undefined) ?? masked;
      return JSON.stringify(request, null, 2);
    };
    return validCompareKeys.map((key) => {
      const entry = allRequests.find((item) => item.key === key);
      return entry ? toRequestJson(entry) : '';
    });
  }, [validCompareKeys, allRequests]);

  const canDiff = validCompareKeys.length === 2;

  // The side-by-side master/detail split is user-resizable only when the detail
  // pane is actually shown (not stacked, an entry selected, not comparing).
  const resizableSplit = !stacked && Boolean(selectedEntry) && !compareMode;

  return (
    <Box
      ref={rowRef}
      sx={{
        flex: 1,
        display: 'flex',
        // Stack master-over-detail on small screens, side-by-side on md+.
        flexDirection: stacked ? 'column' : 'row',
        gap: 1,
        p: 1,
        overflow: 'hidden',
        minHeight: 0,
      }}
    >
      {/* Master list */}
      <Paper
        variant="outlined"
        sx={{
          display: 'flex',
          flexDirection: 'column',
          // When stacked, the list takes full width and a bounded share of the
          // height so the detail pane below it stays usable. Side-by-side with a
          // detail pane the width is user-resizable (pixels); otherwise full width.
          width: stacked
            ? '100%'
            : resizableSplit
              ? masterWidth.value
              : '100%',
          flexShrink: stacked
            ? selectedEntry && !compareMode
              ? 0
              : undefined
            : resizableSplit
              ? 0
              : undefined,
          minWidth: stacked ? 0 : 300,
          height: stacked && selectedEntry && !compareMode ? '45%' : undefined,
          overflow: 'hidden',
          // Disable the width transition while actively dragging so the pane
          // tracks the pointer 1:1.
          transition:
            stacked || masterWidth.dragging ? undefined : 'width 0.2s ease',
        }}
      >
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1,
            px: 1,
            py: 0.25,
            borderBottom: 1,
            borderColor: 'divider',
            flexShrink: 0,
          }}
        >
          <Typography variant="subtitle2" sx={{ fontWeight: 600, fontSize: '0.79rem' }}>
            Traffic
          </Typography>
          {allRequests.length > 0 && (
            <Chip
              label={allRequests.length > 999 ? '999+' : allRequests.length}
              color="primary"
              size="small"
              sx={{ height: 18, fontSize: '0.65rem', '& .MuiChip-label': { px: 0.75 } }}
            />
          )}
          <TextField
            id="traffic-inspector-search"
            size="small"
            placeholder="Search..."
            value={trafficSearch}
            onChange={(e) => setTrafficSearch(e.target.value)}
            slotProps={{
              input: {
                startAdornment: (
                  <InputAdornment position="start">
                    <SearchIcon fontSize="small" />
                  </InputAdornment>
                ),
              },
            }}
            sx={{
              ml: 'auto',
              maxWidth: 200,
              '& .MuiInputBase-root': { height: 28, fontSize: '0.75rem' },
              '& .MuiSvgIcon-root': { fontSize: '0.875rem' },
            }}
          />
          <Tooltip title="Pick two requests to diff field-by-field">
            <ToggleButton
              value="compare"
              size="small"
              selected={compareMode}
              onChange={toggleCompareMode}
              aria-label="Compare requests"
              sx={{ height: 28, px: 1, fontSize: '0.7rem', textTransform: 'none', flexShrink: 0 }}
            >
              <CompareArrowsIcon sx={{ fontSize: '0.95rem', mr: 0.5 }} />
              Compare
            </ToggleButton>
          </Tooltip>
          {compareMode && (
            <Button
              size="small"
              variant="contained"
              disabled={!canDiff}
              onClick={() => setDiffDialogOpen(true)}
              sx={{ height: 28, px: 1, fontSize: '0.7rem', textTransform: 'none', flexShrink: 0 }}
            >
              Diff ({validCompareKeys.length}/2)
            </Button>
          )}
        </Box>
        <Box sx={{ flex: 1, overflowY: 'auto', bgcolor: 'background.default' }}>
          {filtered.length === 0 ? (
            <Typography variant="body2" color="text.secondary" sx={{ p: 2, textAlign: 'center' }}>
              {allRequests.length === 0 ? 'No captured requests yet' : 'No matching requests'}
            </Typography>
          ) : (
            filtered.map(({ item, summary }, index) => (
              <TrafficRow
                key={item.key}
                itemKey={item.key}
                summary={summary}
                index={filtered.length - index}
                selected={compareMode ? validCompareKeys.includes(item.key) : selectedKey === item.key}
                onSelect={handleRowSelect}
                compareMode={compareMode}
                compareChecked={validCompareKeys.includes(item.key)}
                compareDisabled={validCompareKeys.length >= 2}
                onCompareToggle={toggleCompareKey}
              />
            ))
          )}
        </Box>
      </Paper>

      {/* Drag handle between master list and detail pane (side-by-side only). */}
      {resizableSplit && (
        <Box
          data-testid="traffic-master-resizer"
          {...masterWidth.getHandleProps()}
          sx={{
            flexShrink: 0,
            width: 8,
            mx: -0.5,
            borderRadius: 1,
            backgroundColor: 'transparent',
            transition: theme.transitions.create('background-color', {
              duration: theme.transitions.duration.shorter,
            }),
            '&:hover, &:focus-visible, &:active': {
              backgroundColor: theme.palette.primary.main,
              opacity: 0.55,
            },
            '&:focus-visible': { outline: 'none' },
            cursor: 'col-resize',
          }}
        />
      )}

      {/* Detail pane (hidden while picking requests to compare) */}
      {selectedEntry && !compareMode && (
        <Paper
          variant="outlined"
          sx={{
            flex: 1,
            display: 'flex',
            flexDirection: 'column',
            overflow: 'hidden',
            minWidth: 0,
          }}
        >
          <ErrorBoundary key={selectedEntry.item.key} label="detail pane">
            <DetailPane
              item={selectedEntry.item}
              summary={selectedEntry.summary}
              scriptedTurns={scriptedTurns}
              onCaptureAsMock={() => setCaptureDialogOpen(true)}
              onReplay={() => setReplayDialogOpen(true)}
            />
          </ErrorBoundary>
        </Paper>
      )}

      {/* Capture as mock dialog */}
      {selectedEntry && isCapturableTraffic(selectedEntry.summary.parsed) && (
        <CaptureAsMockDialog
          open={captureDialogOpen}
          onClose={() => setCaptureDialogOpen(false)}
          parsed={selectedEntry.summary.parsed}
          path={selectedEntry.summary.path ?? ''}
          connectionParams={connectionParams}
          itemValue={selectedEntry.item.value}
        />
      )}

      {/* Replay dialog — re-issue a captured request to its original target */}
      {selectedEntry && (
        <ReplayDialog
          open={replayDialogOpen}
          onClose={() => setReplayDialogOpen(false)}
          item={selectedEntry.item}
          connectionParams={connectionParams}
        />
      )}

      {/* Diff two picked requests, reusing the shared dialog + PUT /mockserver/diff endpoint.
          Mount only while open and key on the selection so the dialog seeds fresh inputs each time. */}
      {diffDialogOpen && (
        <DiffRequestsDialog
          key={compareKeys.join('|')}
          open
          onClose={() => setDiffDialogOpen(false)}
          connectionParams={connectionParams}
          initialExpected={compareJson[0] ?? ''}
          initialActual={compareJson[1] ?? ''}
        />
      )}

    </Box>
  );
}
