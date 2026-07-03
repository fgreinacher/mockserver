import { memo, useMemo } from 'react';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import type { JsonListItem } from '../types';
import Panel from './Panel';
import JsonListItemComponent from './JsonListItem';
import ProgressiveList from './ProgressiveList';
import CopyButton from './CopyButton';
import { useExpansion } from '../hooks/useExpansion';
import { useConnectionParams } from '../hooks/useConnectionParams';
import { matchesItemSearch } from '../lib/searchMatcher';
import { monospaceFontFamily } from '../theme';

interface RequestPanelProps {
  title: string;
  items: JsonListItem[];
  searchValue: string;
  onSearchChange: (value: string) => void;
}

// ---------------------------------------------------------------------------
// W3C traceparent extraction (F8)
// ---------------------------------------------------------------------------

interface TraceparentInfo {
  raw: string;
  traceId: string;
  parentId: string;
  flags: string;
}

const TRACEPARENT_REGEX = /^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$/i;

function findTraceparentInHeaders(headers: unknown): TraceparentInfo | null {
  if (!headers) return null;

  const tryParse = (val: string): TraceparentInfo | null => {
    const match = TRACEPARENT_REGEX.exec(val.trim());
    if (!match) return null;
    return { raw: val.trim(), traceId: match[2]!, parentId: match[3]!, flags: match[4]! };
  };

  if (Array.isArray(headers)) {
    for (const h of headers) {
      if (typeof h !== 'object' || h === null) continue;
      const entry = h as Record<string, unknown>;
      const name = entry['name'];
      if (typeof name === 'string' && name.toLowerCase() === 'traceparent') {
        const values = entry['values'];
        if (Array.isArray(values) && values.length > 0 && typeof values[0] === 'string') {
          return tryParse(values[0]);
        }
      }
    }
  } else if (typeof headers === 'object' && headers !== null) {
    const map = headers as Record<string, unknown>;
    for (const key of Object.keys(map)) {
      if (key.toLowerCase() === 'traceparent') {
        const val = map[key];
        if (typeof val === 'string') return tryParse(val);
        if (Array.isArray(val) && val.length > 0 && typeof val[0] === 'string') return tryParse(val[0]);
      }
    }
  }
  return null;
}

function extractTraceparentFromItem(item: JsonListItem): TraceparentInfo | null {
  const value = item.value;
  // Check top-level headers
  const direct = findTraceparentInHeaders(value['headers']);
  if (direct) return direct;
  // Check nested httpRequest / httpResponse
  for (const key of ['httpRequest', 'httpResponse']) {
    const nested = value[key];
    if (nested && typeof nested === 'object' && !Array.isArray(nested)) {
      const found = findTraceparentInHeaders((nested as Record<string, unknown>)['headers']);
      if (found) return found;
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
          '& .MuiChip-label': { px: 0.5 },
        }}
      />
    </Tooltip>
  );
}

// ---------------------------------------------------------------------------

// One row of the request list. Memoized so unchanged rows skip re-rendering on
// the once-per-second WebSocket push (the store preserves `item` references for
// unchanged entries). traceparent extraction is done here via useMemo keyed on
// the stable `item`, rather than in the parent's `.map()` where it re-ran for
// every item on every parent render.
const RequestRow = memo(function RequestRow({
  item,
  index,
  expanded,
  onToggleExpand,
}: {
  item: JsonListItem;
  index: number;
  expanded: boolean;
  onToggleExpand: (key: string) => void;
}) {
  const tp = useMemo(() => extractTraceparentFromItem(item), [item]);
  return (
    <Box>
      <JsonListItemComponent item={item} index={index} expanded={expanded} onToggleExpand={onToggleExpand} />
      {tp && (
        <Box sx={{ pl: 6, pb: 0.5 }}>
          <TraceparentPill info={tp} />
        </Box>
      )}
    </Box>
  );
});

export default function RequestPanel({
  title,
  items,
  searchValue,
  onSearchChange,
}: RequestPanelProps) {
  const filtered = useMemo(
    () => (searchValue ? items.filter((e) => matchesItemSearch(e.value, searchValue)) : items),
    [items, searchValue],
  );

  const expansion = useExpansion();
  const connectionParams = useConnectionParams();
  const curlExample = `curl -x http://${connectionParams.host}:${connectionParams.port} http://example.com`;

  return (
    <Panel
      title={title}
      count={items.length}
      filteredCount={searchValue ? filtered.length : undefined}
      searchValue={searchValue}
      onSearchChange={onSearchChange}
    >
      {filtered.length === 0 ? (
        items.length === 0 ? (
          <Box sx={{ p: 2, textAlign: 'center', color: 'text.secondary' }}>
            <Typography variant="body2" sx={{ mb: 1 }}>No requests yet.</Typography>
            <Typography variant="caption" component="div" sx={{ mb: 1 }}>
              Send a request through MockServer — as a proxy or to a mock — and it appears here. For example:
            </Typography>
            <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, maxWidth: '100%' }}>
              <Box
                component="code"
                sx={{
                  fontFamily: monospaceFontFamily,
                  fontSize: '0.72rem',
                  px: 1,
                  py: 0.5,
                  bgcolor: 'action.hover',
                  borderRadius: 1,
                  overflowX: 'auto',
                  textAlign: 'left',
                  whiteSpace: 'nowrap',
                }}
              >
                {curlExample}
              </Box>
              <CopyButton text={curlExample} />
            </Box>
          </Box>
        ) : (
          <Typography variant="body2" color="text.secondary" sx={{ p: 2, textAlign: 'center' }}>
            No matching requests
          </Typography>
        )
      ) : (
        <ProgressiveList
          count={filtered.length}
          getKey={(i) => filtered[i]!.key}
          renderRow={(i) => (
            <RequestRow
              item={filtered[i]!}
              index={filtered.length - i}
              expanded={expansion.isExpanded(filtered[i]!.key)}
              onToggleExpand={expansion.toggle}
            />
          )}
        />
      )}
    </Panel>
  );
}
