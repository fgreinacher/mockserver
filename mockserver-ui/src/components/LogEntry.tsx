import { useState, useMemo, memo, useDeferredValue, Fragment } from 'react';
import type { ReactNode, MouseEvent as ReactMouseEvent } from 'react';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import ListItemIcon from '@mui/material/ListItemIcon';
import ListItemText from '@mui/material/ListItemText';
import type { SxProps, Theme } from '@mui/material/styles';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import HelpOutlinedIcon from '@mui/icons-material/HelpOutlined';
import AutoFixHighIcon from '@mui/icons-material/AutoFixHigh';
import PauseCircleIcon from '@mui/icons-material/PauseCircle';
import MoreVertIcon from '@mui/icons-material/MoreVert';
import AddCircleOutlineIcon from '@mui/icons-material/AddCircleOutlined';
import FactCheckOutlinedIcon from '@mui/icons-material/FactCheckOutlined';
import BoltIcon from '@mui/icons-material/Bolt';
import type { LogEntryValue, MessagePart } from '../types';
import JsonViewer from './JsonViewer';
import BecauseSection from './BecauseSection';
import CopyButton from './CopyButton';
import { useDebugMismatchContext } from '../hooks/DebugMismatchContext';
import { useGenerateStubContext } from '../hooks/GenerateStubContext';
import { useSetBreakpointContext, type SetBreakpointFn } from '../hooks/SetBreakpointContext';
import { entryToText } from '../lib/logEntryText';
import { parseLogTimestamp, formatCompactTime, formatAbsoluteTime } from '../lib/logEntryTime';
import { monospaceFontFamily } from '../theme';
import { useDashboardStore } from '../store';
import { extractGenericExpectationFromCapture } from '../lib/expectationFromCapture';
import { expectationToJsonObject } from '../lib/llmExpectationCodegen';

// ---------------------------------------------------------------------------
// "Create From This…" launchpad menu — shared by LogEntry (log-row hover) and
// the Traffic detail pane. One captured flow fans out into every subsystem:
// Create Mock (Composer), Set Breakpoint, Verify This Request, Add Chaos. Each
// action is wired by the surface that owns the data; this component is the
// compact overflow presentation (IconButton + Menu) common to both.
// ---------------------------------------------------------------------------

export interface CreateFromMenuAction {
  key: string;
  label: string;
  icon: ReactNode;
  /** Absent => the item is disabled (its target data is missing). */
  onClick?: () => void;
  /** Tooltip explaining why the item is disabled, shown when `onClick` is absent. */
  disabledTooltip?: string;
}

/**
 * Compact overflow menu offering the launchpad actions. A menu item with no
 * `onClick` renders disabled with its `disabledTooltip`, so the same menu can
 * be shown wherever a flow carries a request while gating the individual
 * actions whose target data (method/path/host) is missing.
 */
export function CreateFromMenu({
  actions,
  tooltip = 'Create from this request…',
  iconColor = 'primary.main',
  iconFontSize = '0.9rem',
  buttonSx,
}: {
  actions: CreateFromMenuAction[];
  tooltip?: string;
  iconColor?: string;
  iconFontSize?: string;
  buttonSx?: SxProps<Theme>;
}) {
  const [anchorEl, setAnchorEl] = useState<null | HTMLElement>(null);
  const open = Boolean(anchorEl);
  const handleOpen = (e: ReactMouseEvent<HTMLElement>) => {
    e.stopPropagation();
    setAnchorEl(e.currentTarget);
  };
  const close = () => setAnchorEl(null);

  return (
    <>
      <Tooltip title={tooltip}>
        <IconButton
          size="small"
          aria-label={tooltip}
          aria-haspopup="menu"
          aria-expanded={open}
          onClick={handleOpen}
          sx={{ p: 0, ml: 0.5, '& .MuiSvgIcon-root': { fontSize: iconFontSize }, ...buttonSx }}
        >
          <MoreVertIcon sx={{ color: iconColor }} />
        </IconButton>
      </Tooltip>
      <Menu
        anchorEl={anchorEl}
        open={open}
        onClose={close}
        onClick={(e) => e.stopPropagation()}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
      >
        {actions.map((action) => {
          const disabled = !action.onClick;
          const item = (
            <MenuItem
              disabled={disabled}
              onClick={() => {
                action.onClick?.();
                close();
              }}
              dense
            >
              <ListItemIcon sx={{ minWidth: 32 }}>{action.icon}</ListItemIcon>
              <ListItemText slotProps={{ primary: { variant: 'body2' } }}>{action.label}</ListItemText>
            </MenuItem>
          );
          if (disabled && action.disabledTooltip) {
            // A disabled MenuItem does not emit hover events, so wrap it in a
            // span for the Tooltip to anchor to.
            return (
              <Tooltip key={action.key} title={action.disabledTooltip} placement="left">
                <span>{item}</span>
              </Tooltip>
            );
          }
          return <Fragment key={action.key}>{item}</Fragment>;
        })}
      </Menu>
    </>
  );
}

// ---------------------------------------------------------------------------
// W3C traceparent pill (F8)
// ---------------------------------------------------------------------------

interface TraceparentInfo {
  raw: string;
  version: string;
  traceId: string;
  parentId: string;
  flags: string;
}

const TRACEPARENT_REGEX = /^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$/i;

function parseTraceparent(value: string): TraceparentInfo | null {
  const match = TRACEPARENT_REGEX.exec(value.trim());
  if (!match) return null;
  return {
    raw: value.trim(),
    version: match[1]!,
    traceId: match[2]!,
    parentId: match[3]!,
    flags: match[4]!,
  };
}

/**
 * Search for a `traceparent` header in a JSON object that looks like an HTTP
 * request or response. Headers can be in array-of-{name,values} form or an
 * object map — both are handled.
 */
function findTraceparentInObject(obj: Record<string, unknown>): TraceparentInfo | null {
  const headers = obj['headers'];
  if (!headers) return null;

  if (Array.isArray(headers)) {
    for (const h of headers) {
      if (typeof h !== 'object' || h === null) continue;
      const entry = h as Record<string, unknown>;
      const name = entry['name'];
      if (typeof name === 'string' && name.toLowerCase() === 'traceparent') {
        const values = entry['values'];
        if (Array.isArray(values) && values.length > 0 && typeof values[0] === 'string') {
          const parsed = parseTraceparent(values[0]);
          if (parsed) return parsed;
        }
      }
    }
  } else if (typeof headers === 'object' && headers !== null) {
    const map = headers as Record<string, unknown>;
    for (const key of Object.keys(map)) {
      if (key.toLowerCase() === 'traceparent') {
        const val = map[key];
        if (typeof val === 'string') {
          const parsed = parseTraceparent(val);
          if (parsed) return parsed;
        }
        if (Array.isArray(val) && val.length > 0 && typeof val[0] === 'string') {
          const parsed = parseTraceparent(val[0]);
          if (parsed) return parsed;
        }
      }
    }
  }

  return null;
}

function extractTraceparent(entry: LogEntryValue): TraceparentInfo | null {
  if (!entry.messageParts) return null;
  for (const part of entry.messageParts) {
    if (part.json && typeof part.value === 'object' && part.value !== null && !Array.isArray(part.value)) {
      const obj = part.value as Record<string, unknown>;
      // Check the object itself (may be an httpRequest / httpResponse)
      const found = findTraceparentInObject(obj);
      if (found) return found;
      // Check nested httpRequest / httpResponse
      for (const nested of ['httpRequest', 'httpResponse']) {
        const inner = obj[nested];
        if (inner && typeof inner === 'object' && !Array.isArray(inner)) {
          const innerFound = findTraceparentInObject(inner as Record<string, unknown>);
          if (innerFound) return innerFound;
        }
      }
    }
  }
  return null;
}

function TraceparentPill({ info }: { info: TraceparentInfo }) {
  const abbrev = info.traceId.substring(0, 8);
  const tooltipText = [
    `traceparent: ${info.raw}`,
    `traceId: ${info.traceId}`,
    `parentId: ${info.parentId}`,
    `flags: ${info.flags} (${info.flags === '01' ? 'sampled' : 'not sampled'})`,
  ].join('\n');

  return (
    <Tooltip
      title={<Box component="pre" sx={{ m: 0, fontFamily: monospaceFontFamily, typography: 'caption', whiteSpace: 'pre-wrap' }}>{tooltipText}</Box>}
    >
      <Chip
        label={`[T] ${abbrev}`}
        size="small"
        color="info"
        variant="outlined"
        sx={{
          height: 18,
          fontSize: '0.6rem',
          fontFamily: monospaceFontFamily,
          ml: 0.5,
          '& .MuiChip-label': { px: 0.5 },
        }}
      />
    </Tooltip>
  );
}

// ---------------------------------------------------------------------------
// Log entry timestamp (compact inline time, absolute on hover)
// ---------------------------------------------------------------------------

function LogTime({ timestamp }: { timestamp: string }) {
  const parsed = useMemo(() => parseLogTimestamp(timestamp), [timestamp]);
  const compact = formatCompactTime(parsed);
  const absolute = formatAbsoluteTime(parsed);
  return (
    <Tooltip title={absolute}>
      <Box
        component="time"
        aria-label={`Logged at ${absolute}`}
        {...(parsed.date ? { dateTime: parsed.date.toISOString() } : {})}
        sx={{
          fontFamily: monospaceFontFamily,
          typography: 'caption',
          color: 'text.secondary',
          mr: 0.75,
          flexShrink: 0,
          whiteSpace: 'nowrap',
          cursor: 'default',
        }}
      >
        {compact}
      </Box>
    </Tooltip>
  );
}

// ---------------------------------------------------------------------------

interface LogEntryProps {
  entry: LogEntryValue;
  indent?: boolean;
  divider?: boolean;
  collapsible?: boolean;
  /**
   * Controlled expand state, lifted to the panel so it survives the row being
   * unmounted while scrolled out of a virtualized list. `entryKey` is the
   * stable key of the enclosing log message (LogEntryValue itself carries no
   * key). When omitted the row falls back to its own internal state.
   */
  entryKey?: string;
  expanded?: boolean;
  onToggleExpand?: (key: string) => void;
}

function addLinks(value: string) {
  const urlMatch = value.match(/(https?:\/\/[^\s]*)/);
  if (urlMatch) {
    const matchedUrl = urlMatch[0]!;
    const idx = value.indexOf(matchedUrl);
    return (
      <span>
        {value.substring(0, idx)}
        <a
          href={matchedUrl}
          target="_blank"
          rel="noopener noreferrer"
          style={{ textDecoration: 'underline', color: 'rgb(95, 113, 245)' }}
        >
          {matchedUrl}
        </a>
        {value.substring(idx + matchedUrl.length)}
      </span>
    );
  }
  return value;
}

function renderMessagePart(part: MessagePart) {
  if (part.value === undefined || part.value === null) return null;

  if (!part.argument) {
    return (
      <Box key={part.key} component="span" sx={{ fontFamily: monospaceFontFamily }}>
        {addLinks(String(part.value))}
      </Box>
    );
  }

  if (part.because && Array.isArray(part.value)) {
    return <BecauseSection key={part.key} reasons={part.value as string[]} />;
  }

  if (part.multiline && Array.isArray(part.value)) {
    return <BecauseSection key={part.key} reasons={part.value as string[]} />;
  }

  if (part.json) {
    if (typeof part.value === 'object' && part.value !== null) {
      return (
        // Trailing non-breaking space separates the expandable JSON block from
        // the following text (e.g. "} matched expectation:") which would
        // otherwise butt directly against the closing brace.
        <Fragment key={part.key}>
          <Box sx={{ display: 'inline-block', pl: 0.5 }}>
            <JsonViewer
              data={part.value as Record<string, unknown>}
              collapsed={0}
              enableClipboard={true}
            />
          </Box>
          {'\u00A0'}
        </Fragment>
      );
    }
    return (
      <Box key={part.key} component="span" sx={{ fontFamily: monospaceFontFamily, pl: 0.5 }}>
        {String(part.value)}
      </Box>
    );
  }

  return (
    <Box
      key={part.key}
      component="span"
      sx={{ fontFamily: monospaceFontFamily, pl: 0.5, letterSpacing: '0.08em', whiteSpace: 'pre' }}
    >
      {addLinks(String(part.value))}
    </Box>
  );
}

// entryToText lives in ../lib/logEntryText.ts so this component file only
// exports React components (satisfies react-refresh/only-export-components).

function getSummary(entry: LogEntryValue): string {
  if (!entry.messageParts || entry.messageParts.length === 0) return '';
  const firstTextPart = entry.messageParts.find(
    (p) => typeof p.value === 'string' && p.value.trim().length > 0,
  );
  if (!firstTextPart) return '';
  const text = String(firstTextPart.value).trim().split('\n')[0]!;
  return text.length > 80 ? text.substring(0, 80) + '…' : text;
}

function descriptionText(entry: LogEntryValue): string {
  if (!entry.description) return '';
  if (typeof entry.description === 'string') return entry.description;
  if (entry.description.json === false) {
    return `${entry.description.first} ${entry.description.second}`;
  }
  return entry.description.first;
}

function isNotMatchedEntry(entry: LogEntryValue): boolean {
  const desc = descriptionText(entry);
  return desc.includes('EXPECTATION_NOT_MATCHED');
}

function extractRequestFromEntry(entry: LogEntryValue): Record<string, unknown> | null {
  if (!entry.messageParts) return null;
  const jsonParts = entry.messageParts.filter(
    (p) => p.json && p.argument && typeof p.value === 'object' && p.value !== null,
  );
  if (jsonParts.length >= 2) {
    return jsonParts[1]!.value as Record<string, unknown>;
  }
  if (jsonParts.length === 1) {
    return jsonParts[0]!.value as Record<string, unknown>;
  }
  return null;
}

/**
 * Derive a breakpoint prefill (method + path) from a log row's request. Handles
 * both a flat request object (`{method, path}`) and one nested under
 * `httpRequest` — the two shapes the log message parts use. Returns `null` when
 * neither a method nor a path can be read, so the "Set breakpoint" action is
 * only offered on rows that carry a request.
 */
function extractBreakpointPrefill(entry: LogEntryValue): { method?: string; path?: string } | null {
  const found = extractRequestFromEntry(entry);
  if (!found) return null;
  const nested = found['httpRequest'];
  const request = (nested && typeof nested === 'object' && !Array.isArray(nested)
    ? (nested as Record<string, unknown>)
    : found);
  const method = typeof request['method'] === 'string' ? (request['method'] as string) : undefined;
  const path = typeof request['path'] === 'string' ? (request['path'] as string) : undefined;
  if (!method && !path) return null;
  return { method, path };
}

/** Read the Host header value from MockServer's array or object header shape. */
function hostFromRequestHeaders(headers: unknown): string | undefined {
  if (Array.isArray(headers)) {
    for (const h of headers) {
      if (h && typeof h === 'object' && !Array.isArray(h)) {
        const entry = h as Record<string, unknown>;
        const name = entry['name'];
        const values = entry['values'];
        if (typeof name === 'string' && name.toLowerCase() === 'host'
          && Array.isArray(values) && typeof values[0] === 'string') {
          return values[0];
        }
      }
    }
  } else if (headers && typeof headers === 'object') {
    for (const [k, v] of Object.entries(headers as Record<string, unknown>)) {
      if (k.toLowerCase() === 'host') {
        if (typeof v === 'string') return v;
        if (Array.isArray(v) && typeof v[0] === 'string') return v[0];
      }
    }
  }
  return undefined;
}

interface LaunchpadData {
  /** Captured value wrapping the request (`{ httpRequest, ... }`) for mock extraction. */
  itemValue: Record<string, unknown>;
  method?: string;
  path?: string;
  host?: string;
}

/**
 * Derive the launchpad inputs (method / path / host + a capture value for mock
 * extraction) from a log row's request. Returns `null` when the entry carries
 * no request, so the "Create From This…" menu is only offered on
 * request-bearing rows.
 */
function extractLaunchpadData(entry: LogEntryValue): LaunchpadData | null {
  const found = extractRequestFromEntry(entry);
  if (!found) return null;
  const nested = found['httpRequest'];
  const isWrapped = nested != null && typeof nested === 'object' && !Array.isArray(nested);
  const request = isWrapped ? (nested as Record<string, unknown>) : found;
  const method = typeof request['method'] === 'string' ? (request['method'] as string) : undefined;
  const path = typeof request['path'] === 'string' ? (request['path'] as string) : undefined;
  if (!method && !path) return null;
  const host = hostFromRequestHeaders(request['headers']);
  // extractGenericExpectationFromCapture reads `itemValue.httpRequest`, so pass
  // the wrapped value as-is or synthesise a wrapper around a bare request.
  const itemValue = isWrapped ? found : { httpRequest: request };
  return { itemValue, method, path, host };
}

/**
 * Build the four launchpad actions from a captured flow's data + the breakpoint
 * seeder. Shared shape used by both the log row and the Traffic detail pane so
 * the fan-out (Composer / Breakpoint / Verify / Chaos) stays consistent. Store
 * actions are read via getState() inside the handlers so callers do not
 * subscribe (keeps the memoized log row off the store's update path).
 */
export function buildLaunchpadActions(
  data: { itemValue?: Record<string, unknown>; method?: string; path?: string; host?: string },
  setBreakpoint: SetBreakpointFn | null,
): CreateFromMenuAction[] {
  const { itemValue, method, path, host } = data;
  return [
    {
      key: 'mock',
      label: 'Create Mock',
      icon: <AddCircleOutlineIcon fontSize="small" color="primary" />,
      onClick: itemValue
        ? () => {
            const draft = extractGenericExpectationFromCapture(itemValue);
            useDashboardStore.getState().editExpectation(
              expectationToJsonObject(draft) as Record<string, unknown>,
            );
          }
        : undefined,
      disabledTooltip: 'No request captured to build a mock from',
    },
    {
      key: 'breakpoint',
      label: 'Set Breakpoint',
      icon: <PauseCircleIcon fontSize="small" color="secondary" />,
      onClick: setBreakpoint && (method || path)
        ? () => setBreakpoint({ method, path })
        : undefined,
      disabledTooltip: 'No method or path to break on',
    },
    {
      key: 'verify',
      label: 'Verify This Request',
      icon: <FactCheckOutlinedIcon fontSize="small" color="info" />,
      onClick: method || path
        ? () => useDashboardStore.getState().setVerificationDraft({ method, path })
        : undefined,
      disabledTooltip: 'No method or path to verify',
    },
    {
      key: 'chaos',
      label: 'Add Chaos For This Host/Path',
      icon: <BoltIcon fontSize="small" color="warning" />,
      onClick: host || path
        ? () => useDashboardStore.getState().setChaosDraft({ host, path })
        : undefined,
      disabledTooltip: 'No host or path for a chaos scope',
    },
  ];
}

function LogEntry({ entry, indent = false, divider = false, collapsible = false, entryKey, expanded: expandedProp, onToggleExpand }: LogEntryProps) {
  const style = entry.style ?? {};
  const hasBody = entry.messageParts && entry.messageParts.length > 0;
  const canCollapse = collapsible && hasBody;
  const [internalExpanded, setInternalExpanded] = useState(false);
  const expanded = expandedProp ?? internalExpanded;
  const handleToggle = () => {
    if (onToggleExpand && entryKey !== undefined) onToggleExpand(entryKey);
    else setInternalExpanded((prev) => !prev);
  };
  // Defer the expanded body (which may contain a heavy JsonViewer for JSON
  // message parts) so the click toggling `expanded` repaints the chevron
  // immediately and the body builds in a non-blocking follow-up render.
  const showBody = useDeferredValue(expanded);
  const debugMismatch = useDebugMismatchContext();
  const generateStub = useGenerateStubContext();
  const setBreakpoint = useSetBreakpointContext();
  const isUnmatched = isNotMatchedEntry(entry);
  const showWhyButton = isUnmatched && debugMismatch !== null;
  const showGenerateStubButton = isUnmatched && generateStub !== null;
  const traceparent = useMemo(() => extractTraceparent(entry), [entry]);
  // A breakpoint can be seeded from any row that carries a request (matched or
  // not), so the user can pause future occurrences of this exact method+path.
  const breakpointPrefill = useMemo(() => extractBreakpointPrefill(entry), [entry]);
  const showSetBreakpointButton = setBreakpoint !== null && breakpointPrefill !== null;
  // "Create From This…" launchpad — offered on any request-bearing row so one
  // captured flow can fan out into a mock, breakpoint, verification, or chaos.
  const launchpad = useMemo(() => extractLaunchpadData(entry), [entry]);
  const launchpadActions = useMemo(
    () => (launchpad ? buildLaunchpadActions(launchpad, setBreakpoint) : []),
    [launchpad, setBreakpoint],
  );

  return (
    <Box
      sx={{
        pl: indent ? 4 : 0.5,
        pr: 0.5,
        py: 0.5,
        fontSize: indent ? '0.8em' : '0.85em',
        whiteSpace: style['style.whiteSpace'] || style['whiteSpace'] || 'nowrap',
        overflow: 'auto',
        color: style.color ?? 'inherit',
        position: 'relative',
        '&:hover .copy-btn': { opacity: 1 },
        ...(divider && {
          borderBottom: 1,
          borderColor: 'divider',
          '&:last-child': { borderBottom: 0 },
        }),
      }}
    >
      {canCollapse ? (
        <>
          <Box
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: 0.5,
              cursor: 'pointer',
              userSelect: 'none',
            }}
            onClick={handleToggle}
          >
            {/* The chevron IconButton is the accessible/keyboard control (a real
                button, focusable, with aria-expanded). The surrounding row stays
                click-to-toggle for the mouse but is not itself a button — that
                would nest the action buttons below inside a button (invalid ARIA). */}
            <IconButton
              size="small"
              aria-label={expanded ? 'Collapse' : 'Expand'}
              aria-expanded={expanded}
              onClick={(e) => { e.stopPropagation(); handleToggle(); }}
              sx={{ p: 0, '& .MuiSvgIcon-root': { fontSize: '1rem' } }}
            >
              {expanded ? <ExpandMoreIcon /> : <ChevronRightIcon />}
            </IconButton>
            {entry.timestamp && <LogTime timestamp={entry.timestamp} />}
            <Box
              component="span"
              sx={{ whiteSpace: 'pre', fontFamily: monospaceFontFamily }}
            >
              {descriptionText(entry) || 'SYSTEM_MESSAGE'}
            </Box>
            {traceparent && <TraceparentPill info={traceparent} />}
            {showWhyButton && (
              <Tooltip title="Analyze why this request didn't match">
                <IconButton
                  size="small"
                  sx={{ p: 0, ml: 0.5, '& .MuiSvgIcon-root': { fontSize: '0.9rem' } }}
                  onClick={(e) => {
                    e.stopPropagation();
                    const request = extractRequestFromEntry(entry);
                    if (request && debugMismatch) {
                      void debugMismatch(request);
                    }
                  }}
                >
                  <HelpOutlinedIcon sx={{ color: 'warning.main' }} />
                </IconButton>
              </Tooltip>
            )}
            {showGenerateStubButton && (
              <Tooltip title="Generate an expectation for this unmatched request">
                <IconButton
                  size="small"
                  sx={{ p: 0, ml: 0.5, '& .MuiSvgIcon-root': { fontSize: '0.9rem' } }}
                  onClick={(e) => {
                    e.stopPropagation();
                    const request = extractRequestFromEntry(entry);
                    if (request && generateStub) {
                      void generateStub(request);
                    }
                  }}
                >
                  <AutoFixHighIcon sx={{ color: 'info.main' }} />
                </IconButton>
              </Tooltip>
            )}
            {showSetBreakpointButton && (
              <Tooltip title="Set a breakpoint on this request (method + path)">
                <IconButton
                  size="small"
                  aria-label="Set breakpoint on this request"
                  sx={{ p: 0, ml: 0.5, '& .MuiSvgIcon-root': { fontSize: '0.9rem' } }}
                  onClick={(e) => {
                    e.stopPropagation();
                    if (breakpointPrefill && setBreakpoint) {
                      setBreakpoint(breakpointPrefill);
                    }
                  }}
                >
                  <PauseCircleIcon sx={{ color: 'secondary.main' }} />
                </IconButton>
              </Tooltip>
            )}
            {launchpad && <CreateFromMenu actions={launchpadActions} />}
            {!expanded && (() => {
              const summary = getSummary(entry);
              return (
                <Tooltip title={summary} disableHoverListener={!summary}>
                  <Box
                    component="span"
                    // Hidden on narrow viewports (mobile and the IDE-embedded
                    // dashboard) where it wraps one word per line and looks broken;
                    // the full message is still available by expanding the row.
                    sx={{ fontFamily: monospaceFontFamily, color: 'text.secondary', ml: 1, overflow: 'hidden', textOverflow: 'ellipsis', display: { xs: 'none', md: 'inline-block' } }}
                  >
                    {summary}
                  </Box>
                </Tooltip>
              );
            })()}
          </Box>
          {showBody && (
            <Box sx={{ pl: 2.5, pt: 0.5 }}>
              {entry.messageParts?.map(renderMessagePart)}
            </Box>
          )}
        </>
      ) : (
        <>
          {entry.description ? (
            <Box
              title={descriptionText(entry)}
              sx={{
                whiteSpace: 'pre',
                fontFamily: monospaceFontFamily,
                display: 'flex',
                alignItems: 'center',
              }}
            >
              {entry.timestamp && <LogTime timestamp={entry.timestamp} />}
              {descriptionText(entry)}
              {traceparent && <TraceparentPill info={traceparent} />}
            </Box>
          ) : (
            entry.timestamp && <LogTime timestamp={entry.timestamp} />
          )}
          {entry.messageParts?.map(renderMessagePart)}
        </>
      )}
      <Box className="copy-btn" sx={{ position: 'absolute', top: 2, right: 2, opacity: 0 }}>
        <CopyButton text={entryToText(entry)} />
      </Box>
    </Box>
  );
}

// Memoized: the log panel re-renders on every WebSocket push. With the store
// now preserving the `entry` reference for unchanged entries (reconcileByKey,
// which reuses whole objects so a log group's nested entries are stable too),
// default shallow prop comparison lets unchanged log rows skip re-rendering.
export default memo(LogEntry);
