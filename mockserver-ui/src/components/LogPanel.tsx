import { memo, useRef, useMemo, useState } from 'react';
import Typography from '@mui/material/Typography';
import { useDashboardStore } from '../store';
import { isLogGroup } from '../types';
import Panel from './Panel';
import LogEntry from './LogEntry';
import LogGroup from './LogGroup';
import ProgressiveList from './ProgressiveList';
import { useExpansion } from '../hooks/useExpansion';
import { useHeldItems } from '../hooks/useHeldItems';
import { matchesLogSearch, isForwardedLogEntry } from '../lib/searchMatcher';
import { LOG_FILTER_OPTIONS } from '../lib/filterDSL';

// Log rows carry no httpRequest/httpResponse, so `matchesLogSearch` can satisfy
// no field operator at all. Declaring that to the search box (rather than
// leaving the full vocabulary advertised) is what turns a typed `status:>=400`
// into a visible "not supported here" instead of a silent empty list.
const LOG_SEARCH_FIELDS = LOG_FILTER_OPTIONS.fields ?? [];

// Stable key accessor for useHeldItems — module scope, so its identity never changes.
const keyOf = (e: { key: string }) => e.key;

function LogPanel() {
  const logMessages = useDashboardStore((s) => s.logMessages);
  const search = useDashboardStore((s) => s.logSearch);
  const setSearch = useDashboardStore((s) => s.setLogSearch);
  const showForwarded = useDashboardStore((s) => s.logShowForwarded);
  const searchRef = useRef<HTMLInputElement>(null);

  const filtered = useMemo(() => {
    let rows = logMessages;
    if (!showForwarded) rows = rows.filter((m) => !isForwardedLogEntry(m));
    if (search) rows = rows.filter((m) => matchesLogSearch(m, search));
    return rows;
  }, [logMessages, search, showForwarded]);

  const expansion = useExpansion();
  // Hold what the reader is reading. The live window is capped at 100 rows and
  // drops the oldest as new ones arrive, so under load an open or scrolled-to row
  // is DELETED from the feed within seconds. While the reader is mid-read those
  // rows are held; new rows still arrive and prepend above them.
  const [scrolledAway, setScrolledAway] = useState(false);
  const shown = useHeldItems(filtered, keyOf, scrolledAway || expansion.anyExpanded);

  return (
    <Panel
      title="Log Messages"
      count={logMessages.length}
      filteredCount={(search || !showForwarded) ? filtered.length : undefined}
      searchValue={search}
      onSearchChange={setSearch}
      onScrolledAwayChange={setScrolledAway}
      hasOpenItem={expansion.anyExpanded}
      searchInputRef={searchRef}
      searchFields={LOG_SEARCH_FIELDS}
      liveRegion
    >
      {shown.length === 0 ? (
        <Typography variant="body2" color="text.secondary" sx={{ p: 2, textAlign: 'center' }}>
          {logMessages.length === 0 ? 'No log messages yet — server activity appears here as requests are handled.' : 'No matching log messages'}
        </Typography>
      ) : (
        <ProgressiveList
          count={shown.length}
          getKey={(i) => shown[i]!.key}
          anchorAtTop={expansion.anyExpanded}
          anchorKey={expansion.expandedKey}
          renderRow={(i) => {
            const message = shown[i]!;
            return isLogGroup(message) ? (
              <LogGroup
                group={message}
                open={expansion.isExpanded(message.key)}
                onToggleOpen={expansion.toggle}
              />
            ) : (
              <LogEntry
                entry={message.value}
                entryKey={message.key}
                expanded={expansion.isExpanded(message.key)}
                onToggleExpand={expansion.toggle}
                divider
                collapsible
              />
            );
          }}
        />
      )}
    </Panel>
  );
}

// Memoized because `DashboardGrid` subscribes to `recordedRequests` and
// `proxiedRequests` in order to pass them to its two `RequestPanel` children,
// so the GRID re-renders whenever EITHER traffic array changes — which
// re-renders all four panels, including the ones whose own data did not change.
// Each panel already subscribes to (or is handed) exactly the state it needs,
// so a parent-driven re-render is pure waste. `memo` makes the panel skip it;
// its own Zustand subscriptions still re-render it whenever ITS data changes,
// so nothing displayed changes. Measured in
// `src/__tests__/perf-panelIsolation.test.tsx`.
export default memo(LogPanel);
